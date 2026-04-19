/**
 * Rheem EcoNet Water Heater — Hubitat Driver
 * Version: 0.1.2
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
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(
        name: "Rheem EcoNet Water Heater",
        namespace: "community",
        author: "brossow"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"                   // on() / off()
        capability "ThermostatHeatingSetpoint" // heatingSetpoint + setHeatingSetpoint()
        capability "ThermostatOperatingState"  // thermostatOperatingState (heating / idle)
        capability "ThermostatMode"            // thermostatMode + setThermostatMode() — RM compatibility
        capability "Refresh"
        capability "Initialize"

        attribute "waterHeaterMode",          "string"   // current mode display name
        attribute "supportedModes",           "string"   // JSON array of water heater mode names
        attribute "supportedThermostatModes", "string"   // JSON array of RM thermostat modes
        attribute "thermostatSetpoint",       "number"   // alias for heatingSetpoint (used by some apps)
        attribute "hotWaterLevel",            "number"   // 0 / 33 / 66 / 100
        attribute "online",                   "enum", ["true", "false"]
        attribute "awayMode",                 "enum", ["away", "home"]

        command "setWaterHeaterMode", [[
            name: "Mode", type: "ENUM",
            constraints: ["off", "electric", "energy saving", "heat pump",
                          "high demand", "gas", "performance", "vacation"]
        ]]
        command "setAwayMode", [[
            name: "Away Mode", type: "ENUM", constraints: ["away", "home"]
        ]]
        command "heat"
        command "auto"
        command "emergencyHeat"
    }

    preferences {
        input name: "email",        type: "text",     title: "EcoNet Email",     required: true
        input name: "password",     type: "password", title: "EcoNet Password",  required: true
        input name: "deviceIndex",  type: "number",   title: "Water heater index (0 = first, 1 = second, …)",
              defaultValue: 0, required: true
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
@Field String REST_BASE     = "https://rheem.clearblade.com/api/v/1"
@Field String SYSTEM_KEY    = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field String SYSTEM_SECRET = "E2E699CB0BE6C6FADDB1B0BC9A20"

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
// Lifecycle
// ---------------------------------------------------------------------------
def installed() {
    logDebug "Driver installed"
    initialize()
}

def updated() {
    logDebug "Driver updated — re-initializing"
    unschedule()
    if (settings.logEnable) runIn(1800, "logsOff")
    initialize()
}

def initialize() {
    logDebug "Initializing"
    state.clear()
    schedulePoll()
    login()
}

def refresh() {
    if (!state.userToken) { login() } else { fetchEquipment() }
}

// Switch capability
def on() {
    // Restore last known active mode; fall back to a type-appropriate default
    def mode = state.lastActiveMode as String
    if (!mode) {
        def t = state.genericType as String
        mode = (t == "gasWaterHeater" || t == "tanklessWaterHeater") ? "gas" : "energy saving"
    }
    setWaterHeaterMode(mode)
}

def off() {
    setWaterHeaterMode("off")
}

// ---------------------------------------------------------------------------
// Authentication  ——  POST /user/auth
// ---------------------------------------------------------------------------
def login() {
    logDebug "Authenticating as ${email}"
    def params = [
        uri        : "${REST_BASE}/user/auth",
        headers    : baseHeaders(),
        body       : JsonOutput.toJson([email: email, password: password]),
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
                    logDebug "Login OK — account ${state.accountId}"
                    fetchEquipment()
                } else {
                    log.error "EcoNet WH login failed: ${data?.options?.message}"
                    // Bad credentials — no automatic retry
                }
            } else {
                log.error "EcoNet WH login HTTP ${resp.status} — retrying in 2 minutes"
                runIn(120, "login")
            }
        }
    } catch (Exception e) {
        log.error "EcoNet WH login exception: ${e.message} — retrying in 2 minutes"
        runIn(120, "login")
    }
}

// ---------------------------------------------------------------------------
// Fetch equipment  ——  POST /code/{systemKey}/getUserDataForApp
// ---------------------------------------------------------------------------
def fetchEquipment() {
    if (!state.userToken) { login(); return }

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
                    log.error "EcoNet WH getUserDataForApp returned success=false"
                }
            } else if (resp.status == 401) {
                log.warn "EcoNet WH token expired — re-authenticating"
                state.userToken = null
                login()
            } else {
                log.error "EcoNet WH getUserDataForApp HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "EcoNet WH fetchEquipment exception: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Parse location/equipment response
// ---------------------------------------------------------------------------
void parseLocations(List locations) {
    def waterHeaters = []
    locations.each { loc ->
        loc?.equiptments?.each { equip ->   // NOTE: "equiptments" is a typo in the API
            if (equip?.device_type == "WH" && !equip?.error) {
                waterHeaters << equip
            }
        }
    }

    if (waterHeaters.isEmpty()) {
        log.warn "EcoNet WH: no water heaters found in account"
        return
    }

    int idx = (settings.deviceIndex ?: 0) as int
    if (idx >= waterHeaters.size()) {
        log.warn "EcoNet WH: deviceIndex ${idx} out of range (${waterHeaters.size()} found) — using 0"
        idx = 0
    }

    def equip = waterHeaters[idx]
    logDebug "Water heater: ${equip["@NAME"]?.value}  id=${equip.device_name}  serial=${equip.serial_number}  type=${equip["@TYPE"]}"

    // Cache identity and device capabilities
    state.deviceId      = equip.device_name
    state.serialNumber  = equip.serial_number
    state.genericType   = equip["@TYPE"]   // e.g. gasWaterHeater, tanklessWaterHeater, heatPumpWaterHeater
    state.supportsMode  = (equip["@MODE"] != null)
    state.supportsOnOff = (equip["@ENABLED"] != null)
    state.modeEnumText  = equip["@MODE"]?.constraints?.enumText
    state.setpointLow   = equip["@SETPOINT"]?.constraints?.lowerLimit
    state.setpointHigh  = equip["@SETPOINT"]?.constraints?.upperLimit

    updateAttributes(equip)
}

void updateAttributes(Map equip) {
    def unit = "°${settings.tempUnit ?: 'F'}"

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
        sendEvent(name: "switch",          value: (modeDisplay == "off") ? "off" : "on")
        def tMode = WH_MODE_TO_THERMOSTAT[modeDisplay]
        if (tMode) sendEvent(name: "thermostatMode", value: tMode)
        if (modeDisplay != "off" && modeDisplay != "vacation") {
            state.lastActiveMode = modeDisplay
        }
    }

    // Supported modes (read dynamically from device — never hardcoded)
    def supportedModes = buildSupportedModes(equip)
    if (supportedModes) {
        sendEvent(name: "supportedModes", value: JsonOutput.toJson(supportedModes))
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
    if (!supportsMode) {
        return (genericType == "gasWaterHeater" || genericType == "tanklessWaterHeater") ? "gas" : "electric"
    }
    return null
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
    // OFF is controlled via @ENABLED, not enumText — add it explicitly if supported
    if (supportsOnOff && !modes.contains("off")) modes << "off"

    // Pure inferred devices
    if (!supportsMode && !supportsOnOff) {
        modes << ((genericType == "gasWaterHeater" || genericType == "tanklessWaterHeater") ? "gas" : "electric")
    }
    return modes ?: null
}

// Convert a raw @MODE enumText entry to a display name.
// The ELECTRICGAS firmware mode resolves to "electric" or "gas" based on device type.
def modeTextToDisplay(String text, String genericType) {
    if (!text) return null
    def key = text.trim().toUpperCase().replaceAll(/[ _\/]/, "")
    if (key == "ELECTRICGAS") {
        return (genericType == "gasWaterHeater" || genericType == "tanklessWaterHeater") ? "gas" : "electric"
    }
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
def setHeatingSetpoint(temp) {
    logDebug "setHeatingSetpoint(${temp})"
    def t    = temp as BigDecimal
    def lo   = toDisplayTemp(state.setpointLow  as Integer ?: 90)
    def hi   = toDisplayTemp(state.setpointHigh as Integer ?: 140)
    def unit = "°${settings.tempUnit ?: 'F'}"

    if (t < lo || t > hi) {
        log.error "EcoNet WH: setpoint ${t}${unit} out of range [${lo}–${hi}]"
        return
    }
    publishCommand(["@SETPOINT": toFahrenheit(t).intValue()])
    sendEvent(name: "heatingSetpoint",    value: t, unit: unit)
    sendEvent(name: "thermostatSetpoint", value: t, unit: unit)
}

def setWaterHeaterMode(String mode) {
    logDebug "setWaterHeaterMode(${mode})"
    def payload = buildModePayload(mode)
    if (payload == null) return   // error already logged in buildModePayload
    publishCommand(payload)
    sendEvent(name: "waterHeaterMode", value: mode)
    sendEvent(name: "switch",          value: (mode == "off") ? "off" : "on")
    def tMode = WH_MODE_TO_THERMOSTAT[mode]
    if (tMode) sendEvent(name: "thermostatMode", value: tMode)
    if (mode != "off" && mode != "vacation") state.lastActiveMode = mode
}

def setAwayMode(String mode) {
    logDebug "setAwayMode(${mode})"
    publishCommand(["@AWAY": (mode == "away")])
    sendEvent(name: "awayMode", value: mode)
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
        default:
            log.warn "EcoNet WH: unsupported thermostat mode '${thermostatMode}'"
    }
}

def heat()          { setThermostatMode("heat") }
def auto()          { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("emergency heat") }

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
        } else if (!supportsOnOff) {
            // @MODE is the only control mechanism and we can't find the mode
            log.error "EcoNet WH: mode '${display}' not found in device modes: ${enumText}"
            return null
        } else {
            log.warn "EcoNet WH: mode '${display}' not in enumText — relying on @ENABLED only"
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

// ---------------------------------------------------------------------------
// Publish command via ClearBlade REST HTTP→MQTT bridge
// ---------------------------------------------------------------------------
void publishCommand(Map fields) {
    if (!state.userToken || !state.deviceId || !state.serialNumber || !state.accountId) {
        log.error "EcoNet WH: missing state — run refresh() or re-initialize"
        return
    }

    def now = new Date().format("yyyy-MM-dd'T'HH:mm:ss")
    def mqttPayload = [
        transactionId : "HUBITAT_${now}",
        device_name   : state.deviceId,
        serial_number : state.serialNumber,
    ] + fields

    def params = [
        uri       : "${REST_BASE}/message/${SYSTEM_KEY}/publish",
        headers   : authedHeaders(),
        body      : JsonOutput.toJson([
            topic : "user/${state.accountId}/device/desired",
            body  : JsonOutput.toJson(mqttPayload),
            qos   : 0,
        ]),
        textParser: true,
        timeout   : 15,
    ]

    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                logDebug "Command published OK: ${fields}"
                runIn(5, "fetchEquipment")
            } else if (resp.status == 401) {
                log.warn "EcoNet WH token expired during command — re-authenticating"
                state.userToken = null
                login()
            } else {
                log.error "EcoNet WH publishCommand HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "EcoNet WH publishCommand exception: ${e.message}"
    }
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
// Temperature unit helpers
// ---------------------------------------------------------------------------
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

void logsOff() {
    log.info "EcoNet WH: debug logging disabled after 30 minutes"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void logDebug(String msg) {
    if (settings.logEnable) log.debug "EcoNet WH: ${msg}"
}
