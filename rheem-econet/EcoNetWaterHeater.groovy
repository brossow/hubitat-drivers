/**
 * Rheem EcoNet Water Heater — Hubitat Driver
 * Version: 0.4.0-beta.1
 *
 * Inspired by the Home Assistant pyeconet integration.
 * Uses the ClearBlade cloud REST API for polling and MQTT command publishing.
 *
 * Command endpoint (ClearBlade Go-SDK source):
 *   POST /api/v/1/message/{systemKey}/publish
 *
 * Handles all three EcoNet water heater control styles:
 *   - @MODE only          (enumerated modes, no separate on/off)
 *   - @ENABLED + @MODE    (on/off plus mode selection)
 *   - @ENABLED only       (simple on/off, no modes)
 *
 * Code shared with the thermostat driver (login, polling, unit selection,
 * commands) is copied in at the bottom of this file from shared/EcoNetCommon.groovy.
 * Edit it there and run tools/build.py — see DEVELOPING.md.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(
        name: "Rheem EcoNet Water Heater",
        namespace: "brossow",
        author: "brossow",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/rheem-econet/EcoNetWaterHeater.groovy",
        singleThreaded: true   // a poll and a command must not overwrite each other's state
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"                   // on() / off()
        capability "ThermostatHeatingSetpoint" // heatingSetpoint + setHeatingSetpoint()
        capability "ThermostatOperatingState"  // thermostatOperatingState (heating / idle)
        capability "ThermostatMode"            // thermostatMode, supportedThermostatModes, heat() auto() … — for Rule Machine
        capability "Refresh"
        capability "Initialize"

        attribute "waterHeaterMode",           "string"   // current mode display name
        attribute "supportedWaterHeaterModes", "string"   // JSON array of the modes this heater offers
        attribute "thermostatSetpoint",        "number"   // alias for heatingSetpoint (used by some apps)
        attribute "hotWaterLevel",             "number"   // 0 / 33 / 66 / 100
        attribute "online",                    "enum", ["true", "false"]
        attribute "awayMode",                  "enum", ["away", "home"]

        command "setWaterHeaterMode", [[
            name: "Mode", type: "ENUM",
            constraints: ["off", "electric", "energy saving", "heat pump",
                          "high demand", "gas", "performance", "vacation"]
        ]]
        command "setAwayMode", [[
            name: "Away Mode", type: "ENUM", constraints: ["away", "home"]
        ]]
    }

    preferences {
        input name: "email",        type: "text",     title: "EcoNet Email",     required: true
        input name: "password",     type: "password", title: "EcoNet Password",  required: true
        // Not "required" on purpose: on a new install there is no way to know a serial
        // until the driver has connected once, so requiring it would block the first save.
        // The driver fills it in itself — see adoptSelection().
        input name: "deviceSerial", type: "text",
              title: "Water heater serial number — leave blank to fill in automatically (see README for multiple water heaters)",
              required: false
        input name: "tempUnit",     type: "enum",     title: "Temperature unit",
              options: ["F", "C"], defaultValue: "F", required: true
        input name: "pollInterval", type: "enum",     title: "Poll interval",
              options: ["1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour"],
              defaultValue: "5 minutes", required: true
        input name: "logEnable",    type: "bool",     title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
@Field String DRIVER_VERSION = "0.4.0-beta.1"
@Field String LOG_TAG        = "EcoNet WH"
@Field String UNIT_NOUN      = "water heater"
@Field String UNIT_TITLE     = "Water heater"
@Field String ROSTER_KEY     = "waterHeater"

// Cleaned @MODE enumText string (uppercase, no spaces/underscores/slashes) → display name
// null means "resolve dynamically based on device type" (ELECTRICGAS case)
@Field Map ECONET_MODE_TO_DISPLAY = [
    "OFF"           : "off",
    "ELECTRICMODE"  : "electric",
    "ELECTRIC"      : "electric",
    "ENERGYSAVING"  : "energy saving",
    "ENERGYSAVER"   : "energy saving",   // firmware alias
    "HEATPUMPONLY"  : "heat pump",
    "HEATPUMP"      : "heat pump",       // firmware alias
    "HIGHDEMAND"    : "high demand",
    "GAS"           : "gas",
    "PERFORMANCE"   : "performance",
    "VACATION"      : "vacation",
    "ELECTRICGAS"   : null,              // resolved per-device below
]

// Display name → cleaned enumText key (for command lookup)
@Field Map DISPLAY_TO_ECONET = [
    "off"          : "OFF",
    "electric"     : "ELECTRICMODE",
    "energy saving": "ENERGYSAVING",
    "heat pump"    : "HEATPUMPONLY",
    "high demand"  : "HIGHDEMAND",
    "gas"          : "GAS",
    "performance"  : "PERFORMANCE",
    "vacation"     : "VACATION",
]

// Modes in which the heater is not keeping water hot. Off for the Switch capability as
// well as for thermostatMode, so the two never disagree; on() restores the last other mode.
@Field List INACTIVE_MODES = ["off", "vacation"]

// Water heater display name → Hubitat thermostat mode (for Rule Machine compatibility)
@Field Map WH_MODE_TO_THERMOSTAT = [
    "off"          : "off",
    "vacation"     : "off",           // treated as off from RM's perspective
    "energy saving": "auto",
    "heat pump"    : "heat",
    "electric"     : "heat",
    "gas"          : "heat",
    "performance"  : "heat",
    "high demand"  : "emergency heat",
]

// ---------------------------------------------------------------------------
// Lifecycle  (installed, updated and refresh are in the shared code)
// ---------------------------------------------------------------------------
def initialize() {
    logDebug "Initializing"
    // Start clean, but keep the mode on() restores: initialize also runs at every hub
    // startup, and wiping it there meant on() forgot a mode the user had set.
    def lastActiveMode = state.lastActiveMode
    state.clear()
    if (lastActiveMode) state.lastActiveMode = lastActiveMode
    // Renamed to supportedWaterHeaterModes in 0.4.0; don't leave the old value showing.
    // Cosmetic only, so it must never stop the driver starting.
    try {
        device.deleteCurrentState("supportedModes")
    } catch (Exception e) {
        logDebug "Could not clear the old supportedModes attribute: ${e.message}"
    }
    startSession()
}

// ---------------------------------------------------------------------------
// Hooks for the shared code
// ---------------------------------------------------------------------------
List unitsIn(Map equip) {
    return (equip?.device_type == "WH") ? [equip] : []
}

void applyUnit(Map equip) {
    logDebug "Water heater type ${equip["@TYPE"]}"
    // Device capabilities, for sending commands
    state.genericType   = equip["@TYPE"]   // e.g. gasWaterHeater, tanklessWaterHeater, heatPumpWaterHeater
    state.supportsMode  = (equip["@MODE"] != null)
    state.supportsOnOff = (equip["@ENABLED"] != null)
    state.modeEnumText  = equip["@MODE"]?.constraints?.enumText
    state.setpointLow   = equip["@SETPOINT"]?.constraints?.lowerLimit
    state.setpointHigh  = equip["@SETPOINT"]?.constraints?.upperLimit

    updateAttributes(equip)
}

// ---------------------------------------------------------------------------
// Attributes
// ---------------------------------------------------------------------------
void updateAttributes(Map equip) {
    def unit = tempUnitLabel()

    // Setpoint (water temperature target)
    def sp = equip["@SETPOINT"]?.value
    if (sp != null) {
        def disp = toDisplayTemp(sp as Integer)
        sendEvent(name: "heatingSetpoint",    value: disp, unit: unit)
        sendEvent(name: "thermostatSetpoint", value: disp, unit: unit)
    }

    // Current mode — resolves @ENABLED + @MODE + device type into a single display name
    def modeDisplay = resolveModeDisplay(equip)
    if (modeDisplay != null) {
        sendEvent(name: "waterHeaterMode", value: modeDisplay)
        sendEvent(name: "switch",          value: (modeDisplay in INACTIVE_MODES) ? "off" : "on")
        def tMode = WH_MODE_TO_THERMOSTAT[modeDisplay]
        if (tMode) sendEvent(name: "thermostatMode", value: tMode)
        if (!(modeDisplay in INACTIVE_MODES)) state.lastActiveMode = modeDisplay
    }

    // Supported modes (read dynamically from device — never hardcoded)
    def supportedModes = buildSupportedModes(equip)
    if (supportedModes) {
        sendEvent(name: "supportedWaterHeaterModes", value: JsonOutput.toJson(supportedModes))
        state.supportedModesList = supportedModes
        // Derive the RM thermostat mode subset from the water heater's supported modes
        def tModes = []
        if (supportedModes.any { it in ["heat pump", "electric", "gas", "performance"] }) tModes << "heat"
        if (supportedModes.contains("energy saving")) tModes << "auto"
        if (supportedModes.contains("high demand"))   tModes << "emergency heat"
        if (supportedModes.contains("off") || supportedModes.contains("vacation")) tModes << "off"
        sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(tModes))
    }

    // Operating state — @RUNNING is a non-empty string when active
    def running = equip["@RUNNING"]
    if (running != null) {
        sendEvent(name: "thermostatOperatingState", value: (running != "") ? "heating" : "idle")
    }

    // Hot water tank level (derived from icon name in the API response)
    def hotWater = equip["@HOTWATER"]
    if (hotWater != null) {
        def level = parseHotWaterLevel(hotWater as String)
        if (level != null) sendEvent(name: "hotWaterLevel", value: level, unit: "%")
    }

    // Online / connectivity
    def connected = equip["@CONNECTED"]
    if (connected != null) sendEvent(name: "online", value: connected.toString())

    // Away mode
    def away = equip["@AWAY"]
    if (away != null) sendEvent(name: "awayMode", value: away ? "away" : "home")
}

// Determine the current mode display name from the equipment data.
// Handles all three device control styles (@ENABLED only, @MODE only, both).
def resolveModeDisplay(Map equip) {
    def supportsOnOff = equip["@ENABLED"] != null
    def supportsMode  = equip["@MODE"] != null
    def genericType   = equip["@TYPE"] as String

    // If device uses @ENABLED and it's currently off, short-circuit
    if (supportsOnOff && equip["@ENABLED"]?.value == 0) return "off"

    if (supportsMode) {
        def enumText  = equip["@MODE"]?.constraints?.enumText
        def modeIndex = equip["@MODE"]?.value
        if (enumText && modeIndex != null && modeIndex < enumText.size()) {
            return modeTextToDisplay(enumText[modeIndex] as String, genericType)
        }
    }

    // No mode info — infer from device type
    if (!supportsMode) return inferredMode(genericType)
    return null
}

// The one heating mode of a heater with no @MODE, or what its ELECTRICGAS entry means.
String inferredMode(String genericType) {
    return (genericType == "gasWaterHeater" || genericType == "tanklessWaterHeater") ? "gas" : "electric"
}

// Build the list of supported mode display names from the device's own enumText.
def buildSupportedModes(Map equip) {
    def modes       = []
    def supportsMode  = equip["@MODE"] != null
    def supportsOnOff = equip["@ENABLED"] != null
    def genericType   = equip["@TYPE"] as String

    if (supportsMode) {
        equip["@MODE"]?.constraints?.enumText?.each { text ->
            def display = modeTextToDisplay(text as String, genericType)
            if (display && !modes.contains(display)) modes << display
        }
    }
    // Without @MODE the heater has one heating mode, inferred from its type. It has to be
    // listed even when @ENABLED is present, or heat() and the mode list offer only "off".
    if (!supportsMode) modes << inferredMode(genericType)

    // OFF is controlled via @ENABLED, not enumText — add it explicitly if supported
    if (supportsOnOff && !modes.contains("off")) modes << "off"
    return modes ?: null
}

// Convert a raw @MODE enumText entry to a display name.
// The ELECTRICGAS firmware mode resolves to "electric" or "gas" based on device type.
def modeTextToDisplay(String text, String genericType) {
    if (!text) return null
    def key = text.trim().toUpperCase().replaceAll(/[ _\/]/, "")
    if (key == "ELECTRICGAS") return inferredMode(genericType)
    return ECONET_MODE_TO_DISPLAY[key]
}

// Parse hot water tank level from the @HOTWATER icon name.
// Icon names are somewhat whimsical — the percentages in the names don't match
// the actual levels the app displays, so we use the corrected values from pyeconet.
def parseHotWaterLevel(String icon) {
    if (!icon) return null
    if (icon.contains("hundread")) return 100  // API typo for "hundred"
    if (icon.contains("fourty"))   return 66   // app shows "2/3 full"
    if (icon.contains("ten"))      return 33   // app shows "1/3 full"
    if (icon.contains("empty") || icon.contains("zero")) return 0
    logDebug "Unknown @HOTWATER icon: ${icon}"
    return null
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------
// Switch capability
def on() {
    // Restore last known active mode; fall back to a type-appropriate default
    def mode = state.lastActiveMode as String
    if (!mode && (state.supportsOnOff as Boolean)) {
        // Nothing remembered: power on in whatever mode the unit is already set to,
        // rather than guessing a mode it might not offer
        if (publishCommand(["@ENABLED": 1])) sendEvent(name: "switch", value: "on")
        return
    }
    if (!mode) {
        def t = state.genericType as String
        mode = (t == "gasWaterHeater" || t == "tanklessWaterHeater") ? "gas" : "energy saving"
    }
    setWaterHeaterMode(mode)
}

def off() {
    setWaterHeaterMode("off")
}

def setHeatingSetpoint(temp) {
    logDebug "setHeatingSetpoint(${temp})"
    def t    = temp as BigDecimal
    def lo   = toDisplayTemp(state.setpointLow  as Integer ?: 90)
    def hi   = toDisplayTemp(state.setpointHigh as Integer ?: 140)
    def unit = tempUnitLabel()

    if (t < lo || t > hi) {
        log.error "EcoNet WH: setpoint ${t}${unit} out of range [${lo}–${hi}]"
        return
    }
    int tempF = toFahrenheit(t).intValue()
    if (!publishCommand(["@SETPOINT": tempF])) return
    // Report what was actually sent (whole °F), so the tile doesn't change again on the next poll
    sendEvent(name: "heatingSetpoint",    value: toDisplayTemp(tempF), unit: unit)
    sendEvent(name: "thermostatSetpoint", value: toDisplayTemp(tempF), unit: unit)
}

def setWaterHeaterMode(String mode) {
    logDebug "setWaterHeaterMode(${mode})"
    def payload = buildModePayload(mode)
    if (payload == null) return   // error already logged in buildModePayload
    if (!publishCommand(payload)) return
    sendEvent(name: "waterHeaterMode", value: mode)
    sendEvent(name: "switch",          value: (mode in INACTIVE_MODES) ? "off" : "on")
    def tMode = WH_MODE_TO_THERMOSTAT[mode]
    if (tMode) sendEvent(name: "thermostatMode", value: tMode)
    if (!(mode in INACTIVE_MODES)) state.lastActiveMode = mode
}

def setAwayMode(String mode) {
    logDebug "setAwayMode(${mode})"
    if (publishCommand(["@AWAY": (mode == "away")])) sendEvent(name: "awayMode", value: mode)
}

// ThermostatMode capability — maps RM thermostat modes to water heater modes.
// "heat" resolves to the best available heating mode on this device.
def setThermostatMode(String thermostatMode) {
    logDebug "setThermostatMode(${thermostatMode})"
    def supported = (state.supportedModesList ?: []) as List
    switch (thermostatMode) {
        case "off":
            setWaterHeaterMode("off")
            break
        case "auto":
            setWaterHeaterMode("energy saving")
            break
        case "heat":
            if      (supported.contains("heat pump"))    setWaterHeaterMode("heat pump")
            else if (supported.contains("electric"))     setWaterHeaterMode("electric")
            else if (supported.contains("gas"))          setWaterHeaterMode("gas")
            else if (supported.contains("performance"))  setWaterHeaterMode("performance")
            else log.warn "EcoNet WH: no heat-equivalent mode available on this device"
            break
        case "emergency heat":
            if (supported.contains("high demand")) setWaterHeaterMode("high demand")
            else log.warn "EcoNet WH: 'high demand' mode not available on this device"
            break
        case "cool":
            log.warn "EcoNet WH: a water heater has no cooling mode — '${thermostatMode}' ignored"
            break
        default:
            log.warn "EcoNet WH: unsupported thermostat mode '${thermostatMode}'"
    }
}

def heat()          { setThermostatMode("heat") }
def auto()          { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("emergency heat") }

// The ThermostatMode capability declares cool(), and a dashboard tile or rule can call
// it. Without a definition that call throws MissingMethodException.
def cool()          { setThermostatMode("cool") }

// Build the MQTT payload for a mode change.
// Correctly handles all three device control styles and the ELECTRICGAS dual-mode entry.
def buildModePayload(String display) {
    def payload       = [:]
    def supportsOnOff = state.supportsOnOff as Boolean
    def supportsMode  = state.supportsMode  as Boolean
    def genericType   = state.genericType   as String
    def enumText      = state.modeEnumText  as List

    if (!supportsOnOff && !supportsMode) {
        log.error "EcoNet WH: device doesn't support mode/on-off changes"
        return null
    }

    // @ENABLED controls power on/off independently of mode
    if (supportsOnOff) {
        payload["@ENABLED"] = (display == "off") ? 0 : 1
    }

    // @MODE: find the matching index in the device's enumText.
    // Skip if we're just turning off and @ENABLED handles that.
    if (supportsMode && !(display == "off" && supportsOnOff)) {
        def idx = findModeIndex(enumText, display, genericType)
        if (idx != null) {
            payload["@MODE"] = idx
        } else {
            // Sending @ENABLED alone would turn the heater on in whatever mode it was last in
            // while reporting the one requested, so refuse a mode this unit doesn't offer.
            log.error "EcoNet WH: mode '${display}' not found in device modes: ${enumText}"
            return null
        }
    }

    return payload.isEmpty() ? null : payload
}

// Find the enumText index for a given display name, accounting for the ELECTRICGAS dual-mode entry.
def findModeIndex(List enumText, String display, String genericType) {
    if (!enumText) return null
    for (int i = 0; i < enumText.size(); i++) {
        if (modeTextToDisplay(enumText[i] as String, genericType) == display) return i
    }
    return null
}

// ===== BEGIN SHARED CODE (shared/EcoNetCommon.groovy) =====
// Generated by tools/build.py. Do not edit here: edit the shared file and rebuild.

// ---------------------------------------------------------------------------
// Code shared by the EcoNet Thermostat and Water Heater drivers: login and
// retry backoff, polling, unit selection, command publishing, and helpers.
//
// tools/build.py copies this file into both drivers, between the SHARED
// markers at the bottom of each. Edit it here, never inside a driver.
//
// The including driver provides:
//   imports   groovy.json.JsonOutput, groovy.transform.Field
//   @Fields   DRIVER_VERSION  — e.g. "0.4.0"
//             LOG_TAG         — log prefix: "EcoNet" / "EcoNet WH"
//             UNIT_NOUN       — "thermostat" / "water heater"
//             UNIT_TITLE      — "Thermostat" / "Water heater"
//             ROSTER_KEY      — state-variable stem: "thermostat" / "waterHeater"
//   methods   initialize()            — reset state, then call startSession()
//             List unitsIn(Map equip) — the units in one equipment record that
//                                       this driver controls ([] if none)
//             void applyUnit(Map equip) — cache unit details, publish attributes
// ---------------------------------------------------------------------------

@Field String REST_BASE     = "https://rheem.rheemconnect.com/api/v/1"
@Field String SYSTEM_KEY    = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field String SYSTEM_SECRET = "E2E699CB0BE6C6FADDB1B0BC9A20"
@Field Integer LOGIN_RETRY_MIN = 120    // seconds; doubles with each failed login…
@Field Integer LOGIN_RETRY_MAX = 3600   // …up to this cap

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
def installed() {
    logDebug "Driver installed"
    // A new device follows the hub's temperature scale. Existing devices keep theirs:
    // installed() only runs once, when the device is created.
    def scale = location?.temperatureScale
    if (!settings.tempUnit && scale in ["F", "C"]) device.updateSetting("tempUnit", [value: scale, type: "enum"])
    initialize()
}

def updated() {
    logDebug "Driver updated — re-initializing"
    unschedule()
    if (settings.logEnable) runIn(1800, "logsOff")
    initialize()
}

/** Called by each driver's initialize() once its state is reset. */
void startSession() {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    // A freshly created device has no credentials yet, and nothing prompts the user
    // for them — say where they go rather than sitting silent with no data.
    if (!settings.email || !settings.password) {
        log.warn "${LOG_TAG}: no credentials set. Open this device's Preferences tab, enter the EcoNet email and " +
                 "password you use in the Rheem app, and click Save Preferences. There is no separate login prompt."
        return
    }
    schedulePoll()
    login()
}

def refresh() {
    if (state.userToken) {
        fetchEquipment()
        return
    }
    // A rule calling refresh() on a timer must not undo the backoff: with a rejected
    // password that would retry it every minute against the account the Rheem app uses.
    if (loginBackoffActive()) {
        int wait = (((state.loginRetryAt as Long) - now()) / 1000) as int
        log.info "${LOG_TAG}: not logged in — the next login attempt is in ${describeDelay(wait)}. " +
                 "Save Preferences on this device to retry now."
        return
    }
    login()
}

// ---------------------------------------------------------------------------
// Authentication  ——  POST /user/auth
// ---------------------------------------------------------------------------
def login() {
    if (!settings.email || !settings.password) {
        log.warn "${LOG_TAG}: no credentials set — enter your EcoNet email and password on this device's Preferences tab."
        return
    }
    logDebug "Authenticating as ${maskEmail(settings.email)}"
    def params = [
        uri        : "${REST_BASE}/user/auth",
        headers    : baseHeaders(),
        body       : JsonOutput.toJson([email: settings.email, password: settings.password]),
        contentType: "application/json",
        timeout    : 15,
    ]
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                def data = resp.data
                if (data?.options?.success) {
                    state.userToken = data.user_token
                    state.accountId = data.options.account_id
                    state.loginAt   = now()
                    clearLoginBackoff()
                    logDebug "Login OK — account ${maskId(state.accountId)}"
                    fetchEquipment()
                } else {
                    loginRejected(data?.options?.message as String)
                }
            } else {
                int delay = scheduleLoginRetry(false)
                log.error "${LOG_TAG} login HTTP ${resp.status} — retrying in ${describeDelay(delay)}"
            }
        }
    } catch (Exception e) {
        def status = httpErrorStatus(e)
        if (status == 401 || status == 403) {
            loginRejected("HTTP ${status}")
        } else {
            int delay = scheduleLoginRetry(false)
            log.error "${LOG_TAG} login exception: ${e.message} — retrying in ${describeDelay(delay)}"
        }
    }
}

// ---------------------------------------------------------------------------
// Login retry backoff
//
// A failed login schedules the next attempt with a doubling delay — 2, 4, 8 …
// minutes, capped at an hour — and polls don't log in while one is pending.
// Without this, an outage or a mistyped password meant a failed login on every
// poll indefinitely. Rejected credentials go straight to the cap: repeating a bad
// password risks locking the account the Rheem app also uses. Saving Preferences
// re-initializes and clears all of it.
// ---------------------------------------------------------------------------
int scheduleLoginRetry(boolean rejected) {
    int failures = ((state.loginFailures ?: 0) as Integer) + 1
    state.loginFailures = failures
    int delay = rejected ? LOGIN_RETRY_MAX
                         : Math.min(LOGIN_RETRY_MIN * (1 << Math.min(failures - 1, 5)), LOGIN_RETRY_MAX)
    state.loginRetryAt = now() + delay * 1000L
    runIn(delay, "login")
    return delay
}

void loginRejected(String reason) {
    int delay = scheduleLoginRetry(true)
    log.error "${LOG_TAG} login rejected (${reason ?: 'no reason given'}). Check the EcoNet email and password on this " +
              "device's Preferences tab and click Save Preferences, which retries immediately. " +
              "Otherwise the next attempt is in ${describeDelay(delay)}."
}

void clearLoginBackoff() {
    state.remove("loginFailures")
    state.remove("loginRetryAt")
}

boolean loginBackoffActive() {
    return state.loginRetryAt && now() < (state.loginRetryAt as Long)
}

String describeDelay(int seconds) {
    if (seconds >= 3600) return "1 hour"
    int minutes = Math.max(1, Math.ceil(seconds / 60.0) as int)
    return minutes == 1 ? "1 minute" : "${minutes} minutes"
}

// A 401 means the session token expired: log in again — unless we only just did, in
// which case the new token is being refused too and retrying at once would loop.
void handleUnauthorized(String context) {
    state.userToken = null
    if (state.loginAt && now() - (state.loginAt as Long) < 60000) {
        int delay = scheduleLoginRetry(false)
        log.error "${LOG_TAG} ${context}: new session refused — retrying login in ${describeDelay(delay)}"
    } else {
        log.warn "${LOG_TAG} ${context}: session expired — re-authenticating"
        login()
    }
}

// Hubitat's httpPost throws for non-2xx responses instead of calling the closure,
// so the status code has to be read from the exception.
Integer httpErrorStatus(Exception e) {
    return (e instanceof groovyx.net.http.HttpResponseException) ? e.statusCode : null
}

// ---------------------------------------------------------------------------
// Fetch equipment  ——  POST /code/{systemKey}/getUserDataForApp
// ---------------------------------------------------------------------------
def fetchEquipment() {
    if (!state.userToken) {
        // After a failed login a retry is already scheduled — don't add an attempt on every poll
        if (!loginBackoffActive()) login()
        return
    }

    def params = [
        uri        : "${REST_BASE}/code/${SYSTEM_KEY}/getUserDataForApp",
        headers    : authedHeaders(),
        body       : JsonOutput.toJson([resource: "friedrich"]),
        contentType: "application/json",
        timeout    : 15,
    ]
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                def data = resp.data
                if (data?.success) {
                    parseLocations(data.results.locations)
                } else {
                    log.error "${LOG_TAG} getUserDataForApp returned success=false"
                }
            } else if (resp.status == 401) {
                handleUnauthorized("poll")
            } else {
                log.error "${LOG_TAG} getUserDataForApp HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        if (httpErrorStatus(e) == 401) handleUnauthorized("poll")
        else log.error "${LOG_TAG} fetchEquipment exception: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Parse location/equipment response and select this device's unit
//
// The selection rules exist to prevent one failure: a device silently
// controlling the wrong unit. A unit is pinned by unitId(), never by list
// position; a guess is never persisted; and a selection that can't be
// honoured controls nothing at all.
// ---------------------------------------------------------------------------
void parseLocations(List locations) {
    def units       = []
    def places      = []   // parallel to units: location name for each, may be null
    def unavailable = []   // units the API flags with an error
    locations.each { loc ->
        def place = loc?.name ?: loc?.location_name
        // NOTE: "equiptments" is a typo in the actual API response
        loc?.equiptments?.each { equip ->
            def mine = unitsIn(equip)
            if (!mine) return
            if (equip?.error) {
                unavailable.addAll(mine)
            } else {
                mine.each { unit ->
                    units  << unit
                    places << place
                }
            }
        }
    }

    // The pinned unit is on the account but reporting an error — usually offline. Say
    // that, rather than "no unit has this serial", and keep its identity: it is still
    // the right unit, and it will be back.
    def down = unavailable.find { selectionMatches(it) }
    if (down) {
        reportUnavailable(down)
        return
    }

    if (units.isEmpty()) {
        log.warn "${LOG_TAG}: no ${UNIT_NOUN}s found in account"
        return
    }

    publishRoster(units, places)

    int idx = resolveIndex(units)
    if (idx < 0) {
        // An explicit selection couldn't be honoured — never guess. Drop the cached
        // identity too, so queued commands can't still reach the previous unit.
        state.remove("deviceId")
        state.remove("serialNumber")
        return
    }

    def equip = units[idx]
    logDebug "${UNIT_TITLE}: ${unitName(equip)}, device id ${maskId(equip.device_name)}, serial ${maskId(equip.serial_number)}"

    if (!state.rosterLogged) {
        state.rosterLogged = true
        log.info "${LOG_TAG}: ${units.size()} ${UNIT_NOUN}(s) on this account — listed in the " +
                 "${ROSTER_KEY}0…${ROSTER_KEY}${units.size() - 1} state variables, under State Variables on the device's Commands tab."
        if (units.size() > 1) {
            log.info "${LOG_TAG}: this Hubitat device controls ${unitName(equip)} (${ROSTER_KEY}${idx}). Every other " +
                     "${UNIT_NOUN} needs its own Hubitat device using this same driver, with that unit's serial " +
                     "number set in its preferences."
        }
    }

    state.deviceId     = equip.device_name
    state.serialNumber = equip.serial_number

    if (state.unavailableLogged) {
        state.remove("unavailableLogged")
        log.info "${LOG_TAG}: ${UNIT_NOUN} ${maskId(unitId(equip))} is reporting normally again."
    }
    applyUnit(equip)
}

/** True when the serial preference picks out this unit, by the same rule resolveIndex() uses. */
boolean selectionMatches(def equip) {
    def wanted = normalizeSerial(settings.deviceSerial?.trim())
    def id     = normalizeSerial(unitId(equip))
    return wanted && id && (id == wanted || (id.length() >= 6 && wanted.contains(id)))
}

void reportUnavailable(def equip) {
    sendEvent(name: "online", value: "false")
    // Once per outage, not once per poll
    if (!state.unavailableLogged) {
        state.unavailableLogged = true
        log.warn "${LOG_TAG}: ${UNIT_NOUN} ${maskId(unitId(equip))} is reporting an error to EcoNet (${equip.error}) — " +
                 "usually it is offline. Showing it as offline and skipping updates until it recovers."
    }
}

/**
 * Publish one state variable per discovered unit — thermostat0, thermostat1, …
 *
 * One unit per variable is deliberate. Hubitat renders a list-valued state variable
 * as a single row, so a combined list invites the user to copy every unit at once;
 * a row per unit makes "copy the one you want" unambiguous. These rows hold the full
 * serial on purpose: they are where the user copies it from. Logs only ever show
 * the last few characters (maskId), because logs get pasted into public threads.
 */
void publishRoster(List found, List places) {
    found.eachWithIndex { u, i ->
        def where = places[i] ? " @ ${places[i]}" : ""
        state["${ROSTER_KEY}${i}".toString()] = "${unitName(u)}${where} — ${unitId(u)}".toString()
    }

    // Drop rows left over from units no longer on the account
    for (int i = found.size(); i < 64; i++) {
        def key = "${ROSTER_KEY}${i}".toString()
        if (state[key] == null) break
        state.remove(key)
    }
}

/**
 * The identifier a device is pinned to: the unit's serial number, or its ClearBlade
 * device id when it doesn't report one. Zoning devices in particular may have no
 * serial, and every entry has a device id, so this always yields something stable.
 */
String unitId(def equip) {
    return (equip?.serial_number ?: equip?.device_name)?.toString()
}

String unitName(def equip) {
    def name = equip ? equip["@NAME"]?.value : null
    return (name ?: equip?.device_name ?: "unnamed").toString()
}

/** Strip punctuation and case so "03-01-A2", "03:01:a2" and "0301a2" all compare equal. */
String normalizeSerial(def s) {
    return s?.toString()?.replaceAll(/[^A-Za-z0-9]/, "")?.toLowerCase()
}

/**
 * Decide which discovered unit this device controls.
 *
 * Returns -1 when a serial number was configured but could not be honoured. The
 * caller must then do nothing at all: a selection the user made explicitly must
 * never silently degrade into "whichever unit happens to be first".
 */
int resolveIndex(List found) {
    def wanted = settings.deviceSerial?.trim()
    if (!wanted) return adoptSelection(found)

    def wantNorm = normalizeSerial(wanted)
    def exact    = []
    def inside   = []
    found.eachWithIndex { u, i ->
        def n = normalizeSerial(unitId(u))
        if (!n) return
        if (n == wantNorm) exact << i
        // Or the identifier found inside a whole row pasted in. Length-guarded so a
        // short id can't match by coincidence.
        else if (n.length() >= 6 && wantNorm.contains(n)) inside << i
    }
    // An exact match wins outright. Otherwise a zone whose id is its parent's serial
    // plus a suffix would also "contain" the parent, and pinning the zone would fail.
    def hits = exact ?: inside

    if (hits.size() == 1) return hits[0] as int

    String stop = "Not controlling any ${UNIT_NOUN} until this is corrected."
    if (hits.size() > 1) {
        log.error "${LOG_TAG}: the serial number preference matches ${hits.size()} ${UNIT_NOUN}s — enter one serial " +
                  "number only, not the contents of several rows. ${stop}"
        return -1
    }

    // Nothing matched. Name the problem precisely rather than making the user guess.
    def named = found.findIndexOf { u -> unitName(u).trim().equalsIgnoreCase(wanted) }
    if (named >= 0) {
        log.error "${LOG_TAG}: '${wanted}' is a ${UNIT_NOUN}'s name, not its serial number. Copy the serial number " +
                  "from the ${ROSTER_KEY}${named} state variable instead. ${stop}"
    } else {
        log.error "${LOG_TAG}: no ${UNIT_NOUN} on this account has the serial number set in this device's preferences " +
                  "(ending ${maskId(wanted)}). Check the ${ROSTER_KEY}0…${ROSTER_KEY}${found.size() - 1} state " +
                  "variables, under State Variables on the device's Commands tab. ${stop}"
    }
    return -1
}

/**
 * Nothing is pinned yet. Choose a unit and, where the choice isn't a guess, write
 * its identifier into the serial preference so the device stays pinned.
 *
 * Two cases reach here:
 *   - Upgrade from 0.1.x, which selected by an index preference. That input is gone,
 *     but Hubitat keeps the saved value, so it is read once, turned into a serial,
 *     and then deleted. The user's choice is preserved and they never see the index again.
 *   - A new install. With one unit on the account there's nothing to choose, so pin
 *     it. With several, pick the first but don't persist it — that would be writing
 *     a guess into the user's configuration.
 */
int adoptSelection(List found) {
    def legacyIndex = settings.deviceIndex
    int idx = 0

    if (legacyIndex != null) {
        idx = legacyIndex as int
        if (idx < 0 || idx >= found.size()) {
            log.warn "${LOG_TAG}: saved ${UNIT_NOUN} index ${idx} is out of range (${found.size()} found) — using the first"
            idx = 0
        }
    }

    if (legacyIndex != null || found.size() == 1) {
        def id = unitId(found[idx])
        if (id) {
            device.updateSetting("deviceSerial", [value: id, type: "text"])
            if (legacyIndex != null) {
                device.removeSetting("deviceIndex")
                log.info "${LOG_TAG}: upgraded — this device used ${UNIT_NOUN} index ${idx} and is now pinned to " +
                         "serial ${maskId(id)}. The index preference has been retired and removed."
            } else {
                log.info "${LOG_TAG}: pinned this device to serial ${maskId(id)}."
            }
        }
        return idx
    }

    log.warn "${LOG_TAG}: ${found.size()} ${UNIT_NOUN}s on this account and no serial number set — using the first " +
             "(${unitName(found[0])}). Set the ${UNIT_TITLE} serial number preference to choose deliberately; " +
             "each additional ${UNIT_NOUN} needs its own Hubitat device using this driver."
    return 0
}

// ---------------------------------------------------------------------------
// Publish command via ClearBlade REST HTTP→MQTT bridge
//
// Endpoint (from ClearBlade Go-SDK source):
//   POST /api/v/1/message/{systemKey}/publish
// Body: { "topic": "...", "body": "<payload as JSON string>", "qos": 0 }
//
// The "body" field must be the MQTT payload serialized to a string
// (i.e. double-encoded JSON), matching what the mobile app sends via MQTT.
// ---------------------------------------------------------------------------
// Returns true only when the command was accepted, so callers update attributes only
// for commands that actually went out.
boolean publishCommand(Map fields, boolean isRetry = false) {
    if (!state.userToken) {
        log.error "${LOG_TAG}: not logged in — command not sent: ${fields}"
        return false
    }
    if (!state.deviceId || !state.serialNumber || !state.accountId) {
        log.error "${LOG_TAG}: no unit selected — command not sent: ${fields}. Run refresh(), or check the serial number preference."
        return false
    }

    // Millisecond resolution, so commands sent within the same second get distinct IDs
    def stamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSS")
    def mqttPayload = [
        transactionId : "HUBITAT_${stamp}",
        device_name   : state.deviceId,
        serial_number : state.serialNumber,
    ] + fields

    def params = [
        uri        : "${REST_BASE}/message/${SYSTEM_KEY}/publish",
        headers    : authedHeaders(),
        body       : JsonOutput.toJson([
            topic : "user/${state.accountId}/device/desired",
            body  : JsonOutput.toJson(mqttPayload),
            qos   : 0,
        ]),
        textParser : true,   // accept any response body without JSON parsing
        timeout    : 15,
    ]

    boolean ok      = false
    boolean expired = false
    try {
        httpPost(params) { resp ->
            if (resp.status == 200)      ok = true
            else if (resp.status == 401) expired = true
            else log.error "${LOG_TAG} publishCommand HTTP ${resp.status} — body: ${resp.data}"
        }
    } catch (Exception e) {
        if (httpErrorStatus(e) == 401) expired = true
        else log.error "${LOG_TAG} publishCommand exception: ${e.message}"
    }

    if (expired) {
        handleUnauthorized("command")
        // login() is synchronous, so if it worked a new token is already in place: send once more
        if (!isRetry && state.userToken) return publishCommand(fields, true)
        log.error "${LOG_TAG}: command not sent: ${fields}"
        return false
    }
    if (ok) {
        logDebug "Command published OK: ${fields}"
        // Re-poll after 5 s to confirm the device accepted the change
        runIn(5, "fetchEquipment")
    }
    return ok
}

// ---------------------------------------------------------------------------
// Scheduling
// ---------------------------------------------------------------------------
void schedulePoll() {
    unschedule("fetchEquipment")
    switch (settings.pollInterval) {
        case "1 minute":   runEvery1Minute("fetchEquipment");    break
        case "5 minutes":  runEvery5Minutes("fetchEquipment");   break
        case "10 minutes": schedule("0 */10 * ? * *", "fetchEquipment"); break
        case "15 minutes": runEvery15Minutes("fetchEquipment");  break
        case "30 minutes": runEvery30Minutes("fetchEquipment");  break
        case "1 hour":     runEvery1Hour("fetchEquipment");      break
        default:           runEvery5Minutes("fetchEquipment")
    }
    logDebug "Poll scheduled: ${settings.pollInterval}"
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
def baseHeaders() {
    return [
        "ClearBlade-SystemKey"   : SYSTEM_KEY,
        "ClearBlade-SystemSecret": SYSTEM_SECRET,
        "Content-Type"           : "application/json; charset=UTF-8",
    ]
}

def authedHeaders() {
    def h = baseHeaders()
    h["ClearBlade-UserToken"] = state.userToken
    return h
}

String tempUnitLabel() {
    return "°${settings.tempUnit ?: 'F'}"
}

def toDisplayTemp(Number fahrenheit) {
    if (settings.tempUnit == "C") {
        return (((fahrenheit - 32) * 5 / 9) as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
    }
    return fahrenheit as BigDecimal
}

/** To whole °F, as the API takes it. Rounded, not truncated: 70.5 is 71, not 70. */
def toFahrenheit(Number temp) {
    def f = (settings.tempUnit == "C") ? ((temp * 9 / 5) + 32) : temp
    return (f as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP)
}

/**
 * Identifiers in logs show only their last four characters. Logs get pasted into
 * public forum threads when people ask for help; four characters are still enough
 * to tell units apart.
 */
String maskId(def id) {
    def s = id?.toString()
    if (!s) return "(none)"
    return s.length() <= 4 ? "…${s}" : "…${s.substring(s.length() - 4)}"
}

String maskEmail(def email) {
    def s = email?.toString()
    if (!s) return "(none)"
    int at = s.indexOf("@")
    return at > 0 ? "${s[0]}…${s.substring(at)}" : "…"
}

void logsOff() {
    log.info "${LOG_TAG}: debug logging disabled after 30 minutes"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void logDebug(String msg) {
    if (settings.logEnable) log.debug "${LOG_TAG}: ${msg}"
}

// ===== END SHARED CODE =====
