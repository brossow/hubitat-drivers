/**
 * Rheem EcoNet Thermostat — Hubitat Driver
 * Version: 0.2.0
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
        namespace: "brossow",
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
    def places      = []   // parallel to thermostats: location name for each, may be null
    locations.each { loc ->
        def place = loc?.name ?: loc?.location_name
        // NOTE: "equiptments" is a typo in the actual API response
        loc?.equiptments?.each { equip ->
            if (equip?.device_type == "HVAC" && !equip?.error) {
                thermostats << equip
                places      << place
                equip?.zoning_devices?.each { zone ->
                    thermostats << zone
                    places      << place
                }
            }
        }
    }

    if (thermostats.isEmpty()) {
        log.warn "EcoNet: no thermostats found in account"
        return
    }

    publishRoster(thermostats, places)

    int idx = resolveIndex(thermostats)
    if (idx < 0) {
        // An explicit selection couldn't be honoured — never guess. Drop the cached
        // identity too, so queued commands can't still reach the previous unit.
        state.remove("deviceId")
        state.remove("serialNumber")
        return
    }

    def equip = thermostats[idx]
    logDebug "Thermostat: ${equip["@NAME"]?.value}  device_name=${equip.device_name}  serial=${equip.serial_number}"

    if (!state.rosterLogged) {
        state.rosterLogged = true
        log.info "EcoNet: ${thermostats.size()} thermostat(s) on this account — listed in the " +
                 "thermostat0…thermostat${thermostats.size() - 1} state variables, under State Variables on the device's Commands tab."
        if (thermostats.size() > 1) {
            log.info "EcoNet: this Hubitat device controls ${state['thermostat' + idx]}. Every other thermostat " +
                     "needs its own Hubitat device using this same driver, with that unit's serial number set " +
                     "in its preferences."
        }
    }

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

/**
 * Publish one state variable per discovered thermostat — thermostat0, thermostat1, …
 *
 * One unit per variable is deliberate. Hubitat renders a list-valued state variable
 * as a single row, so a combined list invites the user to copy every thermostat at
 * once; a row per unit makes "copy the one you want" unambiguous.
 */
void publishRoster(List found, List places) {
    found.eachWithIndex { t, i ->
        def name  = t["@NAME"]?.value ?: t.device_name ?: "unnamed"
        def where = places[i] ? " @ ${places[i]}" : ""
        state["thermostat${i}".toString()] = "${name}${where} — ${unitId(t)}".toString()
    }

    // Drop rows left over from units no longer on the account
    for (int i = found.size(); i < 64; i++) {
        def key = "thermostat${i}".toString()
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

/** Strip punctuation and case so "03-01-A2", "03:01:a2" and "0301a2" all compare equal. */
String normalizeSerial(def s) {
    return s?.toString()?.replaceAll(/[^A-Za-z0-9]/, "")?.toLowerCase()
}

/**
 * Decide which discovered thermostat this device controls.
 *
 * Returns -1 when a serial number was configured but could not be honoured. The
 * caller must then do nothing at all: a selection the user made explicitly must
 * never silently degrade into "whichever thermostat happens to be first".
 */
int resolveIndex(List found) {
    def wanted = settings.deviceSerial?.trim()
    if (!wanted) return adoptSelection(found)

    def wantNorm = normalizeSerial(wanted)
    def hits     = []
    found.eachWithIndex { t, i ->
        def n = normalizeSerial(unitId(t))
        // Exact match, or the identifier found inside a whole row pasted in.
        // Length-guarded so a short id can't match by coincidence.
        if (n && (n == wantNorm || (n.length() >= 6 && wantNorm.contains(n)))) hits << i
    }

    if (hits.size() == 1) return hits[0] as int

    if (hits.size() > 1) {
        log.error "EcoNet: '${wanted}' matches ${hits.size()} thermostats — enter one serial number only, " +
                  "not the contents of several rows. Not controlling any thermostat until this is corrected."
        return -1
    }

    // Nothing matched. Name the problem precisely rather than making the user guess.
    def named = found.findIndexOf { t ->
        ((t["@NAME"]?.value ?: t.device_name)?.toString()?.trim())?.equalsIgnoreCase(wanted)
    }
    if (named >= 0) {
        log.error "EcoNet: '${wanted}' is a thermostat's name, not its serial number. Use " +
                  "${unitId(found[named])} instead. Not controlling any thermostat until this is corrected."
    } else {
        log.error "EcoNet: no thermostat on this account has serial '${wanted}'. Check the thermostat0…" +
                  "thermostat${found.size() - 1} state variables, under State Variables on the device's Commands tab. Not controlling any " +
                  "thermostat until this is corrected."
    }
    return -1
}

/**
 * Nothing is pinned yet. Choose a thermostat and, where the choice isn't a guess,
 * write its identifier into the serial preference so the device stays pinned.
 *
 * Two cases reach here:
 *   - Upgrade from 0.1.x, which selected by a "Thermostat index" preference. That
 *     input is gone, but Hubitat keeps the saved value, so it is read once, turned
 *     into a serial, and then deleted. The user's existing choice is preserved and
 *     they never see the index again.
 *   - A new install. With one thermostat on the account there's nothing to choose,
 *     so pin it. With several, pick the first but don't persist it — that would be
 *     writing a guess into the user's configuration.
 */
int adoptSelection(List found) {
    def legacyIndex = settings.deviceIndex
    int idx = 0

    if (legacyIndex != null) {
        idx = legacyIndex as int
        if (idx < 0 || idx >= found.size()) {
            log.warn "EcoNet: saved thermostat index ${idx} is out of range (${found.size()} found) — using the first"
            idx = 0
        }
    }

    if (legacyIndex != null || found.size() == 1) {
        def id = unitId(found[idx])
        if (id) {
            device.updateSetting("deviceSerial", [value: id, type: "text"])
            if (legacyIndex != null) {
                device.removeSetting("deviceIndex")
                log.info "EcoNet: upgraded — this device used thermostat index ${idx} and is now pinned to " +
                         "serial ${id}. The index preference has been retired and removed."
            } else {
                log.info "EcoNet: pinned this device to serial ${id}."
            }
        }
        return idx
    }

    log.warn "EcoNet: ${found.size()} thermostats on this account and no serial number set — using the first " +
             "(${unitId(found[0])}). Set the Thermostat serial number preference to choose deliberately; " +
             "each additional thermostat needs its own Hubitat device using this driver."
    return 0
}

void updateAttributes(Map equip) {
    def unit = "°${settings.tempUnit ?: 'F'}"

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
    def unit = "°${settings.tempUnit ?: 'F'}"
    def lo = toDisplayTemp(state.heatSpLow as Integer ?: 40)
    def hi = toDisplayTemp(state.heatSpHigh as Integer ?: 90)
    if (temp < lo || temp > hi) {
        log.error "Heating setpoint ${temp}${unit} out of range [${lo}–${hi}]"
        return
    }
    // All API communication in Fahrenheit; deadband enforcement in Fahrenheit
    def tempF   = toFahrenheit(temp).intValue()
    def payload = ["@HEATSETPOINT": tempF]
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "auto") {
        def deadband = (state.deadband as Integer) ?: 2
        def coolSPF  = toFahrenheit(device.currentValue("coolingSetpoint") as BigDecimal ?: 0).intValue()
        if (tempF > coolSPF - deadband) {
            def newCoolF = tempF + deadband
            payload["@COOLSETPOINT"] = newCoolF
            sendEvent(name: "coolingSetpoint", value: toDisplayTemp(newCoolF), unit: unit)
        }
    }
    publishCommand(payload)
    sendEvent(name: "heatingSetpoint", value: temp, unit: unit)
}

def setCoolingSetpoint(BigDecimal temp) {
    logDebug "setCoolingSetpoint(${temp})"
    def unit = "°${settings.tempUnit ?: 'F'}"
    def lo = toDisplayTemp(state.coolSpLow as Integer ?: 60)
    def hi = toDisplayTemp(state.coolSpHigh as Integer ?: 99)
    if (temp < lo || temp > hi) {
        log.error "Cooling setpoint ${temp}${unit} out of range [${lo}–${hi}]"
        return
    }
    // All API communication in Fahrenheit; deadband enforcement in Fahrenheit
    def tempF   = toFahrenheit(temp).intValue()
    def payload = ["@COOLSETPOINT": tempF]
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "auto") {
        def deadband = (state.deadband as Integer) ?: 2
        def heatSPF  = toFahrenheit(device.currentValue("heatingSetpoint") as BigDecimal ?: 0).intValue()
        if (tempF < heatSPF + deadband) {
            def newHeatF = tempF - deadband
            payload["@HEATSETPOINT"] = newHeatF
            sendEvent(name: "heatingSetpoint", value: toDisplayTemp(newHeatF), unit: unit)
        }
    }
    publishCommand(payload)
    sendEvent(name: "coolingSetpoint", value: temp, unit: unit)
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
