/**
 * Rheem EcoNet Thermostat — Hubitat Driver
 * Version: 0.4.0-beta.1
 *
 * Inspired by the Home Assistant pyeconet integration.
 * Uses the ClearBlade cloud API at rheem.rheemconnect.com.
 *
 * Authentication and data fetching use REST endpoints.
 * Commands are sent via the ClearBlade REST Messaging endpoint,
 * which proxies HTTP POSTs to the underlying MQTT broker.
 *
 * Code shared with the water heater driver (login, polling, unit selection,
 * commands) is copied in at the bottom of this file from shared/EcoNetCommon.groovy.
 * Edit it there and run tools/build.py — see DEVELOPING.md.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(
        name: "Rheem EcoNet Thermostat",
        namespace: "brossow",
        author: "brossow",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/rheem-econet/EcoNetThermostat.groovy",
        singleThreaded: true   // a poll and a command must not overwrite each other's state
    ) {
        capability "Thermostat"
        // Redundant with Thermostat on paper, but without it the Home page files the
        // device under "Other" instead of thermostats. Keep it.
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Initialize"

        // Extra attributes not in the Thermostat capability
        attribute "humidity",       "number"   // relative humidity %; not via the RelativeHumidityMeasurement capability, which reclassifies the device as a multisensor
        attribute "runningState",   "string"   // raw @RUNNINGSTATUS text, for diagnosis — not a stable value to build rules on
        attribute "fanSpeed",       "string"   // auto / low / medium / high / max
        attribute "online",         "enum", ["true", "false"]
        attribute "awayMode",       "enum", ["away", "home"]

        command "setFanSpeed", [
            [name: "Fan Speed", type: "ENUM",
             constraints: ["auto", "low", "medium", "high", "max"]]
        ]
        command "setAwayMode", [
            [name: "Away Mode", type: "ENUM", constraints: ["away", "home"]]
        ]
        // The Thermostat capability's mode enum has no fan-only, so the device
        // page, dashboards and Rule Machine cannot reach it. This exposes it.
        command "fanOnly"
    }

    preferences {
        input name: "email",       type: "text",     title: "EcoNet Email",     required: true
        input name: "password",    type: "password", title: "EcoNet Password",  required: true
        // Not "required" on purpose: on a new install there is no way to know a serial
        // until the driver has connected once, so requiring it would block the first save.
        // The driver fills it in itself — see adoptSelection().
        input name: "deviceSerial", type: "text",
              title: "Thermostat serial number — leave blank to fill in automatically (see README for multiple thermostats)",
              required: false
        input name: "pollInterval", type: "enum",    title: "Poll interval",
              options: ["1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour"],
              defaultValue: "5 minutes", required: true
        input name: "tempUnit",    type: "enum",     title: "Temperature unit",
              options: ["F", "C"], defaultValue: "F", required: true
        input name: "logEnable",   type: "bool",     title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Constants  (@Field = script-level variable, accessible across all methods)
// ---------------------------------------------------------------------------
@Field String DRIVER_VERSION = "0.4.0-beta.1"
@Field String LOG_TAG        = "EcoNet"
@Field String UNIT_NOUN      = "thermostat"
@Field String UNIT_TITLE     = "Thermostat"
@Field String ROSTER_KEY     = "thermostat"

// Maps from pyeconet mode string → Hubitat thermostatMode value
@Field Map ECONET_MODE_TO_HUB = [
    "OFF"           : "off",
    "HEATING"       : "heat",
    "COOLING"       : "cool",
    "AUTO"          : "auto",
    "FANONLY"       : "fan only",
    "EMERGENCYHEAT" : "emergency heat",
]

// Reverse map: Hubitat mode → pyeconet mode string (spelled out to avoid init-order issues)
@Field Map HUB_MODE_TO_ECONET = [
    "off"           : "OFF",
    "heat"          : "HEATING",
    "cool"          : "COOLING",
    "auto"          : "AUTO",
    "fan only"      : "FANONLY",
    "emergency heat": "EMERGENCYHEAT",
]

// Maps from pyeconet fan-speed string → Hubitat fanSpeed value
@Field Map ECONET_FAN_TO_HUB = [
    "AUTO"   : "auto",
    "LOW"    : "low",
    "MEDLO"  : "medium",
    "MEDIUM" : "medium",
    "MEDHI"  : "medium",
    "HIGH"   : "high",
    "MAX"    : "max",
]

// Normalizes @FANSPEED enum text to an ECONET_FAN_TO_HUB key: "Med.Lo" → "MEDLO".
// Reading and commanding must agree on this, or dotted speeds read back as "auto".
String fanKey(String text) {
    return text?.trim()?.replace(" ", "_")?.replace(".", "")?.toUpperCase()
}

// ---------------------------------------------------------------------------
// Lifecycle  (installed, updated and refresh are in the shared code)
// ---------------------------------------------------------------------------
def initialize() {
    logDebug "Initializing"
    state.clear()
    startSession()
}

// ---------------------------------------------------------------------------
// Hooks for the shared code
// ---------------------------------------------------------------------------
/** A thermostat record, plus any zones it controls — each zone is selectable as its own unit. */
List unitsIn(Map equip) {
    if (equip?.device_type != "HVAC") return []
    return [equip] + (equip?.zoning_devices ?: [])
}

void applyUnit(Map equip) {
    // Mode and fan enum text, for sending commands
    state.modeEnumText       = equip["@MODE"]?.constraints?.enumText
    state.fanSpeedEnumText   = equip["@FANSPEED"]?.constraints?.enumText
    state.fanModeEnumText    = equip["@FANMODE"]?.constraints?.enumText

    // Setpoint limits and deadband
    state.heatSpLow  = equip["@HEATSETPOINT"]?.constraints?.lowerLimit
    state.heatSpHigh = equip["@HEATSETPOINT"]?.constraints?.upperLimit
    state.coolSpLow  = equip["@COOLSETPOINT"]?.constraints?.lowerLimit
    state.coolSpHigh = equip["@COOLSETPOINT"]?.constraints?.upperLimit
    state.deadband   = equip["@DEADBAND"]?.value ?: 2

    updateAttributes(equip)
}

// ---------------------------------------------------------------------------
// Attributes
// ---------------------------------------------------------------------------
void updateAttributes(Map equip) {
    def unit = tempUnitLabel()

    // Current temperature (ambient reading from thermostat sensor)
    def currentTemp = equip["@SETPOINT"]?.value
    if (currentTemp != null) sendEvent(name: "temperature", value: toDisplayTemp(currentTemp as Integer), unit: unit)

    // Target setpoints
    def coolSP = equip["@COOLSETPOINT"]?.value
    if (coolSP != null) sendEvent(name: "coolingSetpoint", value: toDisplayTemp(coolSP as Integer), unit: unit)

    def heatSP = equip["@HEATSETPOINT"]?.value
    if (heatSP != null) sendEvent(name: "heatingSetpoint", value: toDisplayTemp(heatSP as Integer), unit: unit)

    // HVAC mode
    def modeIndex   = equip["@MODE"]?.value
    def modeTexts   = equip["@MODE"]?.constraints?.enumText ?: state.modeEnumText
    def hubMode     = null
    if (modeIndex != null && modeTexts && modeIndex < modeTexts.size()) {
        def econetKey  = modeTexts[modeIndex].trim().replace(" ", "").toUpperCase()
        hubMode = ECONET_MODE_TO_HUB[econetKey]
        if (hubMode) {
            sendEvent(name: "thermostatMode", value: hubMode)
            state.remove("unknownModeLogged")
        } else {
            // Reporting an unknown mode as "off" would tell a rule the system is off while
            // it runs. Leave the last known mode in place and say what was seen, once.
            def seen = modeTexts[modeIndex].toString()
            if (state.unknownModeLogged != seen) {
                state.unknownModeLogged = seen
                log.warn "${LOG_TAG}: unrecognized mode '${seen}' — thermostatMode left unchanged"
            }
        }

        // Supported modes list
        def supportedModes = modeTexts.collect { t ->
            ECONET_MODE_TO_HUB[t.trim().replace(" ", "").toUpperCase()]
        }.findAll { it != null }.unique()
        sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(supportedModes))
    }

    // thermostatSetpoint — the active target temperature based on current mode
    if (hubMode == "heat" || hubMode == "emergency heat") {
        def hSP = equip["@HEATSETPOINT"]?.value
        if (hSP != null) sendEvent(name: "thermostatSetpoint", value: toDisplayTemp(hSP as Integer), unit: unit)
    } else if (hubMode == "cool") {
        def cSP = equip["@COOLSETPOINT"]?.value
        if (cSP != null) sendEvent(name: "thermostatSetpoint", value: toDisplayTemp(cSP as Integer), unit: unit)
    } else if (hubMode == "auto") {
        // Report midpoint of heat/cool setpoints as a single reference value
        def hSP = equip["@HEATSETPOINT"]?.value
        def cSP = equip["@COOLSETPOINT"]?.value
        if (hSP != null && cSP != null) {
            sendEvent(name: "thermostatSetpoint", value: toDisplayTemp(Math.round((hSP + cSP) / 2) as Integer), unit: unit)
        }
    }

    // Operating state — @RUNNINGSTATUS identifies the active direction. The
    // thermostat mode may be AUTO while the unit is actively cooling or heating.
    def running = equip["@RUNNINGSTATUS"]
    if (running != null) {
        def opState = "idle"
        if (running != "") {
            def runningText = running.toString().trim().toLowerCase()
            if (runningText.startsWith("cool"))      opState = "cooling"
            else if (runningText.startsWith("heat")) opState = "heating"
            else if (runningText.startsWith("fan"))  opState = "fan only"
            // Unrecognized status text — fall back to the configured mode where it implies a
            // direction. In off or auto it doesn't, so stay idle rather than report a heat call
            // that may not exist.
            else if (hubMode == "cool")                     opState = "cooling"
            else if (hubMode in ["heat", "emergency heat"]) opState = "heating"
            else if (hubMode == "fan only")                 opState = "fan only"
        }
        logDebug "Running status '${running}' -> thermostatOperatingState '${opState}'"
        sendEvent(name: "thermostatOperatingState", value: opState)
        sendEvent(name: "runningState",             value: running ?: "idle")
    }

    // Fan speed
    def fanSpeedIndex = equip["@FANSPEED"]?.value
    def fanSpeedTexts = equip["@FANSPEED"]?.constraints?.enumText ?: state.fanSpeedEnumText
    if (fanSpeedIndex != null && fanSpeedTexts && fanSpeedIndex < fanSpeedTexts.size()) {
        def econetFan = fanKey(fanSpeedTexts[fanSpeedIndex] as String)
        def hubFan    = ECONET_FAN_TO_HUB[econetFan] ?: "auto"
        sendEvent(name: "fanSpeed", value: hubFan)

        def supportedFanModes = fanSpeedTexts.collect { t ->
            ECONET_FAN_TO_HUB[fanKey(t as String)]
        }.findAll { it != null }.unique()
        // Hubitat thermostatFanMode expects "auto" / "circulate" / "on". A unit with
        // @FANMODE is commanded through it, so it is read from it too (below); here the
        // mode is inferred from the speed only for units without one.
        if (equip["@FANMODE"] == null) {
            sendEvent(name: "thermostatFanMode", value: (hubFan == "auto") ? "auto" : "circulate")
        }
        sendEvent(name: "supportedThermostatFanModes",
                  value: JsonOutput.toJson(supportedFanModes.collect { it == "auto" ? "auto" : "circulate" }.unique()))
    }

    // Fan mode, on units that expose it: setThermostatFanMode() writes @FANMODE, so
    // reading the speed instead would flip the tile back on the next poll.
    def fanModeIndex = equip["@FANMODE"]?.value
    def fanModeTexts = equip["@FANMODE"]?.constraints?.enumText ?: state.fanModeEnumText
    if (fanModeIndex != null && fanModeTexts && fanModeIndex < fanModeTexts.size()) {
        def key = (fanModeTexts[fanModeIndex] as String).trim().replace(" ", "_").replace("/", "_").toUpperCase()
        sendEvent(name: "thermostatFanMode", value: (key == "AUTO") ? "auto" : "circulate")
    }

    // Humidity
    def humidity = equip["@HUMIDITY"]?.value
    if (humidity != null) sendEvent(name: "humidity", value: humidity, unit: "%")

    // Online status
    def connected = equip["@CONNECTED"]
    if (connected != null) sendEvent(name: "online", value: connected.toString())

    // Away mode
    def away = equip["@AWAY"]
    if (away != null) sendEvent(name: "awayMode", value: away ? "away" : "home")
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------
def heat()           { setThermostatMode("heat") }
def cool()           { setThermostatMode("cool") }
def auto()           { setThermostatMode("auto") }
def off()            { setThermostatMode("off") }
def emergencyHeat()  { setThermostatMode("emergency heat") }
def fanAuto()        { setThermostatFanMode("auto") }
def fanCirculate()   { setThermostatFanMode("circulate") }
def fanOn()          { setThermostatFanMode("on") }

def setThermostatMode(String hubMode) {
    logDebug "setThermostatMode(${hubMode})"
    def econetKey = HUB_MODE_TO_ECONET[hubMode]
    if (!econetKey) { log.error "${LOG_TAG}: unknown thermostat mode '${hubMode}'"; return }

    def enumText = state.modeEnumText as List
    if (!enumText) { log.error "${LOG_TAG}: modes not known yet — run refresh() first"; return }

    def idx = findEnumIndex(enumText) { text ->
        text.trim().replace(" ", "").toUpperCase() == econetKey
    }
    if (idx == null) { log.error "${LOG_TAG}: mode '${econetKey}' not found in this thermostat's modes: ${enumText}"; return }

    if (publishCommand(["@MODE": idx])) sendEvent(name: "thermostatMode", value: hubMode)
}

// Hubitat invokes a command named after the mode when one is picked in the UI.
// The Thermostat capability supplies auto()/cool()/heat()/emergencyHeat()/off()
// but nothing for "fan only", so selecting it threw MissingMethodException.
// Groovy allows a quoted method name, which is what the UI actually calls.
def "fan only"() {
    setThermostatMode("fan only")
}

// Declared command, so dashboards and Rule Machine can reach fan-only too.
def fanOnly() {
    setThermostatMode("fan only")
}

def setHeatingSetpoint(BigDecimal temp) {
    logDebug "setHeatingSetpoint(${temp})"
    def unit = tempUnitLabel()
    def lo = toDisplayTemp(state.heatSpLow as Integer ?: 40)
    def hi = toDisplayTemp(state.heatSpHigh as Integer ?: 90)
    if (temp < lo || temp > hi) {
        log.error "${LOG_TAG}: heating setpoint ${temp}${unit} out of range [${lo}–${hi}]"
        return
    }
    // All API communication in Fahrenheit; deadband enforcement in Fahrenheit
    def tempF   = toFahrenheit(temp).intValue()
    def payload = ["@HEATSETPOINT": tempF]
    Integer newCoolF = null
    def coolSP = device.currentValue("coolingSetpoint")
    // In auto, keep the cooling setpoint at least a deadband above. With no cooling setpoint
    // read yet there is nothing to compare against, so leave that to the unit.
    if (device.currentValue("thermostatMode") == "auto" && coolSP != null) {
        def deadband = (state.deadband as Integer) ?: 2
        def coolSPF  = toFahrenheit(coolSP as BigDecimal).intValue()
        if (tempF > coolSPF - deadband) {
            newCoolF = tempF + deadband
            def coolMaxF = (state.coolSpHigh as Integer) ?: 99
            if (newCoolF > coolMaxF) {
                log.error "${LOG_TAG}: heating setpoint ${temp}${unit} is too high for auto mode: the cooling setpoint would have to " +
                          "rise to ${toDisplayTemp(newCoolF)}${unit}, above its maximum of ${toDisplayTemp(coolMaxF)}${unit}"
                return
            }
            payload["@COOLSETPOINT"] = newCoolF
        }
    }
    if (!publishCommand(payload)) return
    sendEvent(name: "heatingSetpoint", value: temp, unit: unit)
    if (newCoolF != null) sendEvent(name: "coolingSetpoint", value: toDisplayTemp(newCoolF), unit: unit)
}

def setCoolingSetpoint(BigDecimal temp) {
    logDebug "setCoolingSetpoint(${temp})"
    def unit = tempUnitLabel()
    def lo = toDisplayTemp(state.coolSpLow as Integer ?: 60)
    def hi = toDisplayTemp(state.coolSpHigh as Integer ?: 99)
    if (temp < lo || temp > hi) {
        log.error "${LOG_TAG}: cooling setpoint ${temp}${unit} out of range [${lo}–${hi}]"
        return
    }
    // All API communication in Fahrenheit; deadband enforcement in Fahrenheit
    def tempF   = toFahrenheit(temp).intValue()
    def payload = ["@COOLSETPOINT": tempF]
    Integer newHeatF = null
    def heatSP = device.currentValue("heatingSetpoint")
    // In auto, keep the heating setpoint at least a deadband below. With no heating setpoint
    // read yet there is nothing to compare against, so leave that to the unit.
    if (device.currentValue("thermostatMode") == "auto" && heatSP != null) {
        def deadband = (state.deadband as Integer) ?: 2
        def heatSPF  = toFahrenheit(heatSP as BigDecimal).intValue()
        if (tempF < heatSPF + deadband) {
            newHeatF = tempF - deadband
            def heatMinF = (state.heatSpLow as Integer) ?: 40
            if (newHeatF < heatMinF) {
                log.error "${LOG_TAG}: cooling setpoint ${temp}${unit} is too low for auto mode: the heating setpoint would have to " +
                          "drop to ${toDisplayTemp(newHeatF)}${unit}, below its minimum of ${toDisplayTemp(heatMinF)}${unit}"
                return
            }
            payload["@HEATSETPOINT"] = newHeatF
        }
    }
    if (!publishCommand(payload)) return
    sendEvent(name: "coolingSetpoint", value: temp, unit: unit)
    if (newHeatF != null) sendEvent(name: "heatingSetpoint", value: toDisplayTemp(newHeatF), unit: unit)
}

def setThermostatFanMode(String hubFanMode) {
    logDebug "setThermostatFanMode(${hubFanMode})"

    // Prefer @FANMODE if the device exposes it
    def fanModeEnum = state.fanModeEnumText as List
    if (fanModeEnum) {
        // "auto" → AUTO, "circulate"/"on" → ON_CONTINUOUS
        def targetKey = (hubFanMode == "auto") ? "AUTO" : "ON_CONTINUOUS"
        def idx = findEnumIndex(fanModeEnum) { text ->
            text.trim().replace(" ", "_").replace("/", "_").toUpperCase() == targetKey
        }
        if (idx != null) {
            if (publishCommand(["@FANMODE": idx])) sendEvent(name: "thermostatFanMode", value: reportedFanMode(hubFanMode))
            return
        }
        log.warn "${LOG_TAG}: fan mode '${targetKey}' not found in @FANMODE enum — falling back to @FANSPEED"
    }

    // Fall back to @FANSPEED for devices that don't expose @FANMODE
    def fanSpeedEnum = state.fanSpeedEnumText as List
    if (!fanSpeedEnum) { log.warn "${LOG_TAG}: no fan mode or fan speed enum available"; return }

    // "auto" → Auto speed; "on"/"circulate" → first non-auto speed
    def targetSpeed = (hubFanMode == "auto") ? "AUTO" : null
    def idx = findEnumIndex(fanSpeedEnum) { text ->
        def key = fanKey(text as String)
        targetSpeed ? (key == targetSpeed) : (key != "AUTO")
    }
    if (idx == null) { log.warn "${LOG_TAG}: no suitable @FANSPEED entry for fan mode '${hubFanMode}'"; return }

    if (!publishCommand(["@FANSPEED": idx])) return
    sendEvent(name: "thermostatFanMode", value: reportedFanMode(hubFanMode))
    sendEvent(name: "fanSpeed", value: ECONET_FAN_TO_HUB[fanKey(fanSpeedEnum[idx] as String)] ?: "auto")
}

// Polls report every non-auto fan setting as "circulate" (see updateAttributes), which is
// also all supportedThermostatFanModes advertises, so report "on" the same way rather than
// emitting a value the next poll immediately changes.
String reportedFanMode(String hubFanMode) {
    return (hubFanMode == "auto") ? "auto" : "circulate"
}

def setFanSpeed(String speed) {
    logDebug "setFanSpeed(${speed})"
    def targetKey = speed.toUpperCase().replace(" ", "_")

    def enumText = state.fanSpeedEnumText as List
    if (!enumText) { log.warn "${LOG_TAG}: fan speeds not known yet — run refresh() first"; return }

    def idx = findEnumIndex(enumText) { text ->
        fanKey(text as String) == targetKey
    }
    // Med.Lo and Med.Hi both read back as "medium", so on a unit with no plain Medium
    // "medium" has to be settable too, or the value the driver reports can't be set.
    if (idx == null && targetKey == "MEDIUM") {
        idx = findEnumIndex(enumText) { text -> fanKey(text as String) in ["MEDLO", "MEDHI"] }
    }
    if (idx == null) { log.warn "${LOG_TAG}: fan speed '${targetKey}' not found in ${enumText}"; return }

    if (publishCommand(["@FANSPEED": idx])) sendEvent(name: "fanSpeed", value: speed)
}

def setAwayMode(String mode) {
    logDebug "setAwayMode(${mode})"
    def away = (mode == "away")
    if (publishCommand(["@AWAY": away])) sendEvent(name: "awayMode", value: mode)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
/** Returns the index of the first item in list where closure returns true, or null. */
def findEnumIndex(List list, Closure predicate) {
    for (int i = 0; i < list.size(); i++) {
        if (predicate(list[i])) return i
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

def toFahrenheit(Number temp) {
    if (settings.tempUnit == "C") {
        return (((temp * 9 / 5) + 32) as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP)
    }
    return temp as BigDecimal
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
