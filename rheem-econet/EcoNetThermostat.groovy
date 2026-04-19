/**
 * Rheem EcoNet Thermostat — Hubitat Driver
 * Version: 0.1.2
 *
 * Inspired by the Home Assistant pyeconet integration.
 * Uses the ClearBlade cloud API at rheem.clearblade.com.
 *
 * Authentication and data fetching use REST endpoints.
 * Commands are sent via the ClearBlade REST Messaging endpoint,
 * which proxies HTTP POSTs to the underlying MQTT broker.
 *
 * Command endpoint confirmed via ClearBlade Go-SDK source:
 *   POST /api/v/1/message/{systemKey}/publish
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(
        name: "Rheem EcoNet Thermostat",
        namespace: "community",
        author: "brossow"
    ) {
        capability "Thermostat"
        capability "Refresh"
        capability "Initialize"

        // Extra attributes not in the Thermostat capability
        attribute "humidity",       "number"   // relative humidity % (not using capability to avoid multisensor classification)
        attribute "runningState",   "string"   // raw @RUNNINGSTATUS value
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
    }

    preferences {
        input name: "email",       type: "text",     title: "EcoNet Email",     required: true
        input name: "password",    type: "password", title: "EcoNet Password",  required: true
        input name: "deviceIndex", type: "number",   title: "Thermostat index (0 = first, 1 = second, …)",
              defaultValue: 0, required: true
        input name: "pollInterval", type: "enum",    title: "Poll interval",
              options: ["1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour"],
              defaultValue: "5 minutes", required: true
        input name: "logEnable",   type: "bool",     title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Constants  (@Field = script-level variable, accessible across all methods)
// ---------------------------------------------------------------------------
@Field String REST_BASE     = "https://rheem.clearblade.com/api/v/1"
@Field String SYSTEM_KEY    = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field String SYSTEM_SECRET = "E2E699CB0BE6C6FADDB1B0BC9A20"

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
    if (!state.userToken) {
        login()
    } else {
        fetchEquipment()
    }
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
                    state.userToken  = data.user_token
                    state.accountId  = data.options.account_id
                    logDebug "Login OK — account ${state.accountId}"
                    fetchEquipment()
                } else {
                    log.error "EcoNet login failed: ${data?.options?.message}"
                    // Bad credentials — no point retrying automatically
                }
            } else {
                log.error "EcoNet login HTTP ${resp.status} — retrying in 2 minutes"
                runIn(120, "login")
            }
        }
    } catch (Exception e) {
        log.error "EcoNet login exception: ${e.message} — retrying in 2 minutes"
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
                    log.error "EcoNet getUserDataForApp returned success=false"
                }
            } else if (resp.status == 401) {
                log.warn "EcoNet token expired — re-authenticating"
                state.userToken = null
                login()
            } else {
                log.error "EcoNet getUserDataForApp HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "EcoNet fetchEquipment exception: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Parse location/equipment response
// ---------------------------------------------------------------------------
void parseLocations(List locations) {
    def thermostats = []
    locations.each { loc ->
        // NOTE: "equiptments" is a typo in the actual API response
        loc?.equiptments?.each { equip ->
            if (equip?.device_type == "HVAC" && !equip?.error) {
                thermostats << equip
                equip?.zoning_devices?.each { zone -> thermostats << zone }
            }
        }
    }

    if (thermostats.isEmpty()) {
        log.warn "EcoNet: no thermostats found in account"
        return
    }

    int idx = (settings.deviceIndex ?: 0) as int
    if (idx >= thermostats.size()) {
        log.warn "EcoNet: deviceIndex ${idx} out of range (${thermostats.size()} thermostat(s) found) — using 0"
        idx = 0
    }

    def equip = thermostats[idx]
    logDebug "Thermostat: ${equip["@NAME"]?.value}  device_name=${equip.device_name}  serial=${equip.serial_number}"

    // Cache identity and mode/fan enum text for use when sending commands
    state.deviceId           = equip.device_name
    state.serialNumber       = equip.serial_number
    state.modeEnumText       = equip["@MODE"]?.constraints?.enumText
    state.fanSpeedEnumText   = equip["@FANSPEED"]?.constraints?.enumText
    state.fanModeEnumText    = equip["@FANMODE"]?.constraints?.enumText

    // Cache setpoint limits and deadband
    state.heatSpLow  = equip["@HEATSETPOINT"]?.constraints?.lowerLimit
    state.heatSpHigh = equip["@HEATSETPOINT"]?.constraints?.upperLimit
    state.coolSpLow  = equip["@COOLSETPOINT"]?.constraints?.lowerLimit
    state.coolSpHigh = equip["@COOLSETPOINT"]?.constraints?.upperLimit
    state.deadband   = equip["@DEADBAND"]?.value ?: 2

    updateAttributes(equip)
}

void updateAttributes(Map equip) {
    // Current temperature (ambient reading from thermostat sensor)
    def currentTemp = equip["@SETPOINT"]?.value
    if (currentTemp != null) sendEvent(name: "temperature", value: currentTemp, unit: "°F")

    // Target setpoints
    def coolSP = equip["@COOLSETPOINT"]?.value
    if (coolSP != null) sendEvent(name: "coolingSetpoint", value: coolSP, unit: "°F")

    def heatSP = equip["@HEATSETPOINT"]?.value
    if (heatSP != null) sendEvent(name: "heatingSetpoint", value: heatSP, unit: "°F")

    // HVAC mode
    def modeIndex   = equip["@MODE"]?.value
    def modeTexts   = equip["@MODE"]?.constraints?.enumText ?: state.modeEnumText
    def hubMode     = "off"
    if (modeIndex != null && modeTexts && modeIndex < modeTexts.size()) {
        def econetKey  = modeTexts[modeIndex].trim().replace(" ", "").toUpperCase()
        hubMode = ECONET_MODE_TO_HUB[econetKey] ?: "off"
        sendEvent(name: "thermostatMode", value: hubMode)

        // Supported modes list
        def supportedModes = modeTexts.collect { t ->
            ECONET_MODE_TO_HUB[t.trim().replace(" ", "").toUpperCase()]
        }.findAll { it != null }.unique()
        sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(supportedModes))
    }

    // thermostatSetpoint — the active target temperature based on current mode
    if (hubMode == "heat" || hubMode == "emergency heat") {
        def hSP = equip["@HEATSETPOINT"]?.value
        if (hSP != null) sendEvent(name: "thermostatSetpoint", value: hSP, unit: "°F")
    } else if (hubMode == "cool") {
        def cSP = equip["@COOLSETPOINT"]?.value
        if (cSP != null) sendEvent(name: "thermostatSetpoint", value: cSP, unit: "°F")
    } else if (hubMode == "auto") {
        // Report midpoint of heat/cool setpoints as a single reference value
        def hSP = equip["@HEATSETPOINT"]?.value
        def cSP = equip["@COOLSETPOINT"]?.value
        if (hSP != null && cSP != null) {
            sendEvent(name: "thermostatSetpoint", value: Math.round((hSP + cSP) / 2), unit: "°F")
        }
    }

    // Operating state — @RUNNINGSTATUS is non-empty when active
    def running = equip["@RUNNINGSTATUS"]
    if (running != null) {
        def opState = "idle"
        if (running != "") {
            if (hubMode == "cool")              opState = "cooling"
            else if (hubMode == "fan only")     opState = "fan only"
            else                                opState = "heating"
        }
        sendEvent(name: "thermostatOperatingState", value: opState)
        sendEvent(name: "runningState",             value: running ?: "idle")
    }

    // Fan speed
    def fanSpeedIndex = equip["@FANSPEED"]?.value
    def fanSpeedTexts = equip["@FANSPEED"]?.constraints?.enumText ?: state.fanSpeedEnumText
    if (fanSpeedIndex != null && fanSpeedTexts && fanSpeedIndex < fanSpeedTexts.size()) {
        def econetFan = fanSpeedTexts[fanSpeedIndex].trim().replace(" ", "_").toUpperCase()
        def hubFan    = ECONET_FAN_TO_HUB[econetFan] ?: "auto"
        sendEvent(name: "fanSpeed", value: hubFan)

        def supportedFanModes = fanSpeedTexts.collect { t ->
            ECONET_FAN_TO_HUB[t.trim().replace(" ", "_").toUpperCase()]
        }.findAll { it != null }.unique()
        // Hubitat thermostatFanMode expects "auto" / "circulate" / "on"
        // Map fanSpeed → thermostatFanMode for the capability
        sendEvent(name: "thermostatFanMode", value: (hubFan == "auto") ? "auto" : "circulate")
        sendEvent(name: "supportedThermostatFanModes",
                  value: JsonOutput.toJson(supportedFanModes.collect { it == "auto" ? "auto" : "circulate" }.unique()))
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
// Thermostat commands
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
    if (!econetKey) { log.error "Unknown Hubitat mode: ${hubMode}"; return }

    def enumText = state.modeEnumText as List
    if (!enumText) { log.error "Mode enum not cached — run refresh() first"; return }

    def idx = findEnumIndex(enumText) { text ->
        text.trim().replace(" ", "").toUpperCase() == econetKey
    }
    if (idx == null) { log.error "Mode '${econetKey}' not found in device modes: ${enumText}"; return }

    publishCommand(["@MODE": idx])
    sendEvent(name: "thermostatMode", value: hubMode)
}

def setHeatingSetpoint(BigDecimal temp) {
    logDebug "setHeatingSetpoint(${temp})"
    def lo = state.heatSpLow as BigDecimal ?: 40
    def hi = state.heatSpHigh as BigDecimal ?: 90
    if (temp < lo || temp > hi) {
        log.error "Heating setpoint ${temp}°F out of range [${lo}–${hi}]"
        return
    }
    def payload = ["@HEATSETPOINT": temp.intValue()]
    // In auto mode, also enforce the deadband against the cool setpoint
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "auto") {
        def deadband = (state.deadband as Integer) ?: 2
        def coolSP = device.currentValue("coolingSetpoint") as Integer
        if (coolSP != null && temp.intValue() > coolSP - deadband) {
            payload["@COOLSETPOINT"] = temp.intValue() + deadband
            sendEvent(name: "coolingSetpoint", value: temp.intValue() + deadband, unit: "°F")
        }
    }
    publishCommand(payload)
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
}

def setCoolingSetpoint(BigDecimal temp) {
    logDebug "setCoolingSetpoint(${temp})"
    def lo = state.coolSpLow as BigDecimal ?: 60
    def hi = state.coolSpHigh as BigDecimal ?: 99
    if (temp < lo || temp > hi) {
        log.error "Cooling setpoint ${temp}°F out of range [${lo}–${hi}]"
        return
    }
    def payload = ["@COOLSETPOINT": temp.intValue()]
    // In auto mode, also enforce the deadband against the heat setpoint
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "auto") {
        def deadband = (state.deadband as Integer) ?: 2
        def heatSP = device.currentValue("heatingSetpoint") as Integer
        if (heatSP != null && temp.intValue() < heatSP + deadband) {
            payload["@HEATSETPOINT"] = temp.intValue() - deadband
            sendEvent(name: "heatingSetpoint", value: temp.intValue() - deadband, unit: "°F")
        }
    }
    publishCommand(payload)
    sendEvent(name: "coolingSetpoint", value: temp, unit: "°F")
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
            publishCommand(["@FANMODE": idx])
            sendEvent(name: "thermostatFanMode", value: hubFanMode)
            return
        }
        log.warn "EcoNet: fan mode '${targetKey}' not found in @FANMODE enum — falling back to @FANSPEED"
    }

    // Fall back to @FANSPEED for devices that don't expose @FANMODE
    def fanSpeedEnum = state.fanSpeedEnumText as List
    if (!fanSpeedEnum) { log.warn "EcoNet: no fan mode or fan speed enum available"; return }

    // "auto" → Auto speed; "on"/"circulate" → first non-auto speed
    def targetSpeed = (hubFanMode == "auto") ? "AUTO" : null
    def idx = findEnumIndex(fanSpeedEnum) { text ->
        def key = text.trim().replace(" ", "_").replace(".", "").toUpperCase()
        targetSpeed ? (key == targetSpeed) : (key != "AUTO")
    }
    if (idx == null) { log.warn "EcoNet: no suitable @FANSPEED entry for fan mode '${hubFanMode}'"; return }

    publishCommand(["@FANSPEED": idx])
    sendEvent(name: "thermostatFanMode", value: hubFanMode)
    sendEvent(name: "fanSpeed", value: (hubFanMode == "auto") ? "auto" : fanSpeedEnum[idx].trim().toLowerCase())
}

def setFanSpeed(String speed) {
    logDebug "setFanSpeed(${speed})"
    def targetKey = speed.toUpperCase().replace(" ", "_")

    def enumText = state.fanSpeedEnumText as List
    if (!enumText) { log.warn "Fan speed enum not cached"; return }

    def idx = findEnumIndex(enumText) { text ->
        text.trim().replace(" ", "_").replace(".", "").toUpperCase() == targetKey
    }
    if (idx == null) { log.warn "Fan speed '${targetKey}' not found in ${enumText}"; return }

    publishCommand(["@FANSPEED": idx])
    sendEvent(name: "fanSpeed", value: speed)
}

def setAwayMode(String mode) {
    logDebug "setAwayMode(${mode})"
    def away = (mode == "away")
    publishCommand(["@AWAY": away])
    sendEvent(name: "awayMode", value: mode)
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
void publishCommand(Map fields) {
    if (!state.userToken || !state.deviceId || !state.serialNumber || !state.accountId) {
        log.error "EcoNet: missing state — run refresh() or re-initialize"
        return
    }

    def now = new Date().format("yyyy-MM-dd'T'HH:mm:ss")
    def mqttPayload = [
        transactionId : "HUBITAT_${now}",
        device_name   : state.deviceId,
        serial_number : state.serialNumber,
    ] + fields

    def topic = "user/${state.accountId}/device/desired"

    def params = [
        uri        : "${REST_BASE}/message/${SYSTEM_KEY}/publish",
        headers    : authedHeaders(),
        body       : JsonOutput.toJson([
            topic : topic,
            body  : JsonOutput.toJson(mqttPayload),
            qos   : 0,
        ]),
        textParser : true,   // accept any response body without JSON parsing
        timeout    : 15,
    ]

    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                logDebug "Command published OK: ${fields}"
                // Re-poll after 5 s to confirm the device accepted the change
                runIn(5, "fetchEquipment")
            } else if (resp.status == 401) {
                log.warn "EcoNet token expired during command — re-authenticating"
                state.userToken = null
                login()
            } else {
                log.error "EcoNet publishCommand HTTP ${resp.status} — body: ${resp.data}"
            }
        }
    } catch (Exception e) {
        log.error "EcoNet publishCommand exception: ${e.message}"
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

/** Returns the index of the first item in list where closure returns true, or null. */
def findEnumIndex(List list, Closure predicate) {
    for (int i = 0; i < list.size(); i++) {
        if (predicate(list[i])) return i
    }
    return null
}

void logsOff() {
    log.info "EcoNet: debug logging disabled after 30 minutes"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void logDebug(String msg) {
    if (settings.logEnable) log.debug "EcoNet: ${msg}"
}
