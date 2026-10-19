/**
 * ReBLLED LED Controller — Hubitat driver
 *
 * Copyright 2026 brossow
 * SPDX-License-Identifier: Apache-2.0
 *
 * For controllers running ReBLLED, the alternative firmware for the BLLED LED controller
 * in Bambu Lab printers. Live status arrives over the controller's /ws push (once a
 * second, no login needed); switching and brightness go through its JSON API
 * (POST /api/settings), which needs the WebUI login when one is set.
 */

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@Field static final String VERSION = "1.0.0"
@Field static final List   MODES = ["follow", "on", "off"]
@Field static final String NONE = "none"   // never "": the hub drops an event whose value is empty
@Field static final long   STALE_MS = 30000L          // no push for this long: the socket is dead
@Field static final int    RETRY_MIN_S = 5
@Field static final int    RETRY_MAX_S = 300
@Field static final long   FULL_PARSE_EVERY_MS = 60000L
@Field static final long   OWN_CLOSE_MS = 2000L       // a "closing" this soon after connecting is our own close()
@Field static final int    RSSI_STEP = 5              // dBm; smaller wobbles aren't worth an event
// Fields that change every push without meaning anything changed. A push that differs from
// the last one only in these isn't parsed (at most once a minute, to keep rssi current).
@Field static final String VOLATILE = /"(uptime|wifi_rssi|rssi|clients|idleOffIn|finishEndsIn|idleFor)":-?\d+/

@Field static final Map PREP_TEXT = [nozzle: "cleaning the nozzle", level: "leveling the bed", extrude: "calibrating extrusion",
                                     scan: "scanning the bed", firstlayer: "inspecting the first layer", lidarcal: "calibrating the lidar"]
// The attention attribute's values: what needs doing at the printer
@Field static final Map ATTENTION_TEXT = [pause: "paused", firstlayer: "first-layer problem", clog: "nozzle clog",
                                          runout: "filament runout", cover: "front cover off", nozzleTemp: "nozzle temperature",
                                          bedTemp: "bed temperature", hmsSerious: "serious HMS error", hmsFatal: "fatal HMS error"]
@Field static final Map GCODE_STATES = [RUNNING: "printing", PREPARE: "preparing", SLICING: "preparing", PAUSE: "paused",
                                        FINISH: "finished", FAILED: "failed", IDLE: "idle"]

// Per-push bookkeeping kept in memory, not state: a push arrives every second and every
// state write is a database write. Lost on a reboot or code save, which only means the
// next push is parsed and its values compared against the device once more.
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long>   lastMessageAt = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long>   lastParsedAt  = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, String> lastKey       = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long>   lastAttemptAt = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long>   socketDownAt  = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Map>    lastSent      = new java.util.concurrent.ConcurrentHashMap()

metadata {
    definition(
        name:      "ReBLLED LED Controller",
        namespace: "brossow",
        author:    "brossow",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/reblled/reblled-controller.groovy",
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"          // on = mode follow (or "always on", see Preferences); off = mode off
        capability "SwitchLevel"     // strip brightness, 0–100 %
        capability "Refresh"
        capability "Initialize"

        attribute "healthStatus",      "enum", ["online", "offline"]   // the controller, as seen from the hub
        attribute "mode",              "enum", ["follow", "on", "off"]
        attribute "strip",             "enum", ["on", "off"]           // whether the strip is lit right now
        attribute "stripColor",        "string"   // "neutral white", "#FF0000, pulse", "off", …
        attribute "status",            "string"   // what the strip shows and why, in words
        attribute "reason",            "string"   // the same, as the controller's code (idle, printing, light_off, …)
        attribute "printerConnection", "string"   // connected | connecting | unreachable | access code rejected | not verified
        attribute "printerStatus",     "string"   // idle | preparing | printing | paused | finished | failed | offline
        attribute "attention",         "string"   // none, or what needs doing: paused, filament runout, nozzle clog, …
        attribute "door",              "enum", ["open", "closed"]
        attribute "printerLight",      "enum", ["on", "off"]
        attribute "lastPrintFinished", "string"   // when the last print finished (sent only when one does)
        attribute "printerModel",      "string"
        attribute "firmware",          "string"
        attribute "rssi",              "number"   // controller Wi-Fi, dBm

        command "setMode", [[name: "mode*", type: "ENUM", constraints: ["follow", "on", "off"],
                             description: "follow: show the printer's status · on: always on · off"]]
    }

    preferences {
        input "address", "text", title: "Controller address",
              description: "IP address or host name of the ReBLLED controller", required: true
        input "webUser", "text", title: "WebUI user name",
              description: "Only needed to switch the strip or change brightness, and only if the controller has a WebUI login. Status works without it.",
              required: false
        input "webPass", "password", title: "WebUI password", required: false
        input "onMode", "enum", title: "Switching on means",
              options: ["follow": "Follow the printer (show its status)", "on": "Always on"], defaultValue: "follow"
        input "logEnable", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
    }
}

// ── Lifecycle ─────────────────────────────────────────────────────────────

def installed() {
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences saved (driver ${VERSION})"
    unschedule()
    state.remove("needsLogin")   // new credentials deserve a fresh try
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

def initialize() {
    long id = device.idAsLong
    lastKey.remove(id)
    lastSent.remove(id)
    state.retrySecs = RETRY_MIN_S
    if (!host()) {
        log.warn "${device.displayName}: set the controller address in Preferences"
        closeSocket()
        sendIfChanged("healthStatus", "offline")
        return
    }
    runEvery1Minute("watchdog")
    connectSocket()
}

def uninstalled() {
    unschedule()
    closeSocket()
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Live status over the web socket ───────────────────────────────────────

private String host() {
    String a = settings.address?.toString()?.trim()
    if (!a) return null
    a = a.replaceAll(/(?i)^(https?|wss?):\/\//, "").replaceAll(/\/.*$/, "")
    return a ?: null
}

private void connectSocket() {
    long id = device.idAsLong
    lastAttemptAt[id] = now()
    closeSocket()
    logDebug "connecting to ws://${host()}/ws"
    try {
        interfaces.webSocket.connect("ws://${host()}/ws")
    } catch (Exception e) {
        log.warn "${device.displayName}: couldn't connect to ${host()}: ${e.message}"
        socketDown()
    }
}

private void closeSocket() {
    try { interfaces.webSocket.close() } catch (Exception e) { }
}

def webSocketStatus(String message) {
    logDebug "web socket: ${message}"
    if (message?.startsWith("failure:")) socketDown()
    // connectSocket() closes the old socket first, and its "closing" arrives just after the
    // new connect starts. That one is ours, not a drop.
    else if (message?.startsWith("status: closing") && now() - (lastAttemptAt[device.idAsLong] ?: 0L) > OWN_CLOSE_MS) socketDown()
}

// The socket dropped. Try again after a back-off that doubles up to five minutes, and
// say offline only then: a late "closing" from a socket that was replaced shouldn't
// flap the device, and reconnect() can tell the two apart by whether pushes kept coming.
private void socketDown() {
    socketDownAt[device.idAsLong] = now()
    int s = (state.retrySecs ?: RETRY_MIN_S) as int
    runIn(s, "reconnect")
    state.retrySecs = Math.min(s * 2, RETRY_MAX_S)
}

def reconnect() {
    long id = device.idAsLong
    if (!host()) return
    if ((lastMessageAt[id] ?: 0L) > (socketDownAt[id] ?: 0L)) return   // pushes still arriving: it's fine
    markOffline()
    connectSocket()
}

// Every minute. Catches a socket that died without saying so, and polls instead while
// the socket is down (that needs the login, if the controller has one). A socket that
// reported its failure already has reconnect() scheduled, so that's left to it.
def watchdog() {
    long id = device.idAsLong
    if (!host() || socketFresh()) return
    if (now() - (lastAttemptAt[id] ?: 0L) < STALE_MS) return   // a socket just opened hasn't had time to push
    if (canPoll()) poll() else markOffline()
    if ((socketDownAt[id] ?: 0L) >= (lastAttemptAt[id] ?: 0L)) return
    int retry = (state.retrySecs ?: RETRY_MIN_S) as int
    if (now() - (lastAttemptAt[id] ?: 0L) >= retry * 1000L) {
        state.retrySecs = Math.min(retry * 2, RETRY_MAX_S)
        connectSocket()
    }
}

private boolean socketFresh() {
    return now() - (lastMessageAt[device.idAsLong] ?: 0L) < STALE_MS
}

private void markOffline() {
    lastKey.remove(device.idAsLong)   // so the first push after reconnecting is parsed, and says online
    sendIfChanged("healthStatus", "offline")
}

def parse(String message) {
    long id = device.idAsLong
    long t = now()
    lastMessageAt[id] = t
    String key = message?.replaceAll(VOLATILE, "")
    if (key == lastKey[id] && t - (lastParsedAt[id] ?: 0L) < FULL_PARSE_EVERY_MS) return
    def j
    try { j = new JsonSlurper().parseText(message) } catch (Exception e) {
        logDebug "not JSON: ${message?.take(80)}"
        return
    }
    if (!(j instanceof Map) || !(j.lights instanceof Map)) return
    lastKey[id] = key
    lastParsedAt[id] = t
    if (state.retrySecs != RETRY_MIN_S) state.retrySecs = RETRY_MIN_S
    update(j.lights)
}

// ── Turning the controller's status into attributes ───────────────────────

// The status object, from a push's "lights" key or from GET /api/status
private void update(Map L) {
    Map p = L.printer instanceof Map ? L.printer : [:]
    String prevPrinter = previous("printerStatus")

    sendIfChanged("healthStatus", "online")
    if (L.mode in MODES) {
        sendIfChanged("mode", L.mode, "mode is ${L.mode}")
        sendIfChanged("switch", L.mode == "off" ? "off" : "on")
    }
    if (L.brightness instanceof Number) sendIfChanged("level", L.brightness as int, null, "%")
    sendIfChanged("strip", L.show && L.show != "off" ? "on" : "off", null)
    sendIfChanged("stripColor", colorName(L))
    sendIfChanged("reason", L.reason ?: NONE, null)
    sendIfChanged("status", describe(L), null)
    sendIfChanged("printerConnection", printerConnection(L, p))
    String ps = printerStatus(p)
    sendIfChanged("printerStatus", ps, "printer is ${ps}")
    sendIfChanged("attention", attention(L, p), null)
    if (p.door instanceof Boolean) sendIfChanged("door", p.door ? "open" : "closed", null)
    if (p.light instanceof Boolean) sendIfChanged("printerLight", p.light ? "on" : "off")
    if (p.model) sendIfChanged("printerModel", p.model.toString(), null)
    if (L.firmware) sendIfChanged("firmware", L.firmware.toString(), null)
    if (L.rssi instanceof Number) {
        def last = previous("rssi")
        if (last == null || Math.abs((L.rssi as int) - (last as int)) >= RSSI_STEP) sendIfChanged("rssi", L.rssi as int, null, "dBm")
    }

    // Only a print that was seen running counts: reconnecting to a printer that is still
    // showing FINISH from hours ago isn't a new finish.
    if (ps == "finished" && prevPrinter in ["printing", "paused", "preparing"]) {
        String when = new Date(now()).format("yyyy-MM-dd HH:mm", location.timeZone)
        sendEvent(name: "lastPrintFinished", value: when, isStateChange: true,
                  descriptionText: "${device.displayName}: print finished")
        if (txtEnable) log.info "${device.displayName}: print finished"
    }
}

private String colorName(Map L) {
    if (L.show == "cycle") return "color cycle"
    if (L.show == "wifi") return "Wi-Fi signal"
    if (L.show != "colour" || !(L.colour instanceof Map)) return "off"
    Map c = L.colour
    String name = c.kind == "custom" ? (c.hex ?: "custom").toString().toUpperCase() :
                  c.kind in ["warm", "neutral", "cool"] ? "${c.kind} white" : c.kind == "off" ? "off" : c.kind?.toString() ?: "off"
    return L.rhythm && L.rhythm != "steady" ? "${name}, ${L.rhythm}" : name
}

// A sentence like the setup page's status bar, minus the countdowns: they change every
// second, and an attribute that changes every second is an event every second.
private String describe(Map L) {
    String sub = ATTENTION_TEXT[L.detail?.toString()]
    switch (L.reason) {
        case "mode_off":          return "Off (mode Off)"
        case "mode_on":           return "On (mode Always on)"
        case "tool_test":         return L.preview ? "Previewing a status color" : "Color test (Tools)"
        case "tool_cycle":        return "Color cycle (Tools)"
        case "tool_wifi":         return "Showing Wi-Fi strength (Tools)"
        case "unreachable":       return "Off: the printer is unreachable"
        case "access_rejected":   return "The printer rejected the access code"
        case "cert_rejected":     return "The printer couldn't be verified, so the access code wasn't sent"
        case "connecting":        return "Connecting to the printer"
        case "error":             return "Error: ${sub ?: 'printer error'}"
        case "attention":         return L.detail == "pause" ? "Print paused" : "Needs attention: ${sub ?: 'check the printer'}"
        case "finished":          return "Print finished"
        case "preparing":         return "Preparing: ${PREP_TEXT[L.detail?.toString()] ?: 'getting ready'}"
        case "preparing_no_lidar": return "Preparing"
        case "printing":          return "Printing"
        case "light_off":         return "Off: following the printer's light, which is off"
        case "idle_off":          return "Off: no activity for a while"
        case "idle":              return "Printer idle"
        default:                  return L.reason ? L.reason.toString() : "Unknown"
    }
}

private String printerConnection(Map L, Map p) {
    if (p.connected) return "connected"
    if (p.accessRejected) return "access code rejected"
    if (p.certRejected) return "not verified"
    return L.reason == "connecting" ? "connecting" : "unreachable"
}

private String printerStatus(Map p) {
    if (!p.connected) return "offline"
    String g = p.gcodeState?.toString()
    return GCODE_STATES[g] ?: (g ? g.toLowerCase() : "unknown")
}

// From the controller's own decision when it has one (it knows HMS errors too). It only
// decides that while following the printer, so otherwise read the printer's stage the
// way the controller does (decide.h: errorKind, attentionKind); in follow mode the two agree.
private String attention(Map L, Map p) {
    if (L.state in ["attention", "error"] && ATTENTION_TEXT[L.detail?.toString()]) return ATTENTION_TEXT[L.detail.toString()]
    if (!p.connected) return NONE
    switch (p.stage) {
        case 6:  return ATTENTION_TEXT.runout
        case 17: return ATTENTION_TEXT.cover
        case 20: return ATTENTION_TEXT.nozzleTemp
        case 21: return ATTENTION_TEXT.bedTemp
        case 34: return ATTENTION_TEXT.firstlayer
        case 35: return ATTENTION_TEXT.clog
        case 16: case 30: return ATTENTION_TEXT.pause
    }
    return p.gcodeState == "PAUSE" ? ATTENTION_TEXT.pause : NONE
}

// Sends only what changed. The comparison is against what this driver last sent (in
// memory), falling back to the device after a reboot or code save.
private void sendIfChanged(String name, def value, String text = "", String unit = null) {
    long id = device.idAsLong
    Map sent = lastSent[id]
    if (sent == null) {
        lastSent.putIfAbsent(id, new java.util.concurrent.ConcurrentHashMap())
        sent = lastSent[id]
    }
    if (!sent.containsKey(name)) {
        def cur = device.currentValue(name)
        if (cur != null) sent[name] = cur
    }
    if (sent.containsKey(name) && sent[name]?.toString() == value?.toString()) return
    sent[name] = value
    if (text == "") text = "${name} is ${value}"
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    if (text) e.descriptionText = "${device.displayName}: ${text}"
    sendEvent(e)
    if (text && txtEnable) log.info e.descriptionText
}

private def previous(String name) {
    Map sent = lastSent[device.idAsLong]
    return sent?.containsKey(name) ? sent[name] : device.currentValue(name)
}

// ── Commands (the JSON API) ───────────────────────────────────────────────

def on() {
    patch([mode: onMode()])
}

def off() {
    patch([mode: "off"])
}

def setMode(String mode) {
    if (!(mode in MODES)) {
        log.warn "${device.displayName}: unknown mode '${mode}' (use ${MODES.join(', ')})"
        return
    }
    patch([mode: mode])
}

// 0 switches off and leaves the brightness alone, so switching on again brings it back.
// Any other level also switches on, the way a dimmer does.
def setLevel(level, duration = null) {
    int b = Math.max(0, Math.min(100, (level ?: 0) as int))
    if (b == 0) return off()
    Map body = [brightness: b]
    if (previous("switch") == "off") body.mode = onMode()
    patch(body)
}

def refresh() {
    if (!host()) {
        log.warn "${device.displayName}: set the controller address in Preferences"
        return
    }
    if (!socketFresh()) connectSocket()
    if (canPoll()) poll()
}

private String onMode() {
    return settings.onMode in ["follow", "on"] ? settings.onMode : "follow"
}

private boolean hasLogin() {
    return settings.webUser && settings.webPass
}

// Without a login entered, a poll is worth trying until the controller says it wants one
private boolean canPoll() {
    return hasLogin() || !state.needsLogin
}

private Map request(String path, Map body = null) {
    Map headers = ["X-BLLED": "1"]   // the controller's own pages send it; harmless on the JSON API
    if (hasLogin()) headers.Authorization = "Basic " + "${settings.webUser}:${settings.webPass}".bytes.encodeBase64().toString()
    Map r = [uri: "http://${host()}${path}", contentType: "application/json", headers: headers, timeout: 10]
    if (body != null) {
        r.requestContentType = "application/json"   // the controller ignores a body sent as anything else
        r.body = JsonOutput.toJson(body)
    }
    return r
}

private void patch(Map body) {
    if (!host()) {
        log.warn "${device.displayName}: set the controller address in Preferences"
        return
    }
    logDebug "POST /api/settings ${JsonOutput.toJson(body)}"
    asynchttpPost("patchDone", request("/api/settings", body), [body: body])
}

def patchDone(resp, Map data) {
    if (resp.status == 401) {
        log.warn "${device.displayName}: the controller wants its WebUI login to change settings; " +
                 (hasLogin() ? "check the user name and password in Preferences" : "enter it in Preferences")
        return
    }
    if (resp.hasError()) {
        log.warn "${device.displayName}: couldn't send ${JsonOutput.toJson(data?.body)}: ${errorText(resp)}"
        return
    }
    Map s = json(resp)
    if (s == null) return
    // The reply is the settings now in effect; the next push would say the same a second later
    if (s.mode in MODES) {
        sendIfChanged("mode", s.mode, "mode is ${s.mode}")
        sendIfChanged("switch", s.mode == "off" ? "off" : "on")
    }
    if (s.brightness instanceof Number) sendIfChanged("level", s.brightness as int, null, "%")
}

private void poll() {
    logDebug "GET /api/status"
    asynchttpGet("pollDone", request("/api/status"))
}

def pollDone(resp, Map data) {
    if (resp.status == 401) {
        if (!state.needsLogin) {
            log.warn "${device.displayName}: the controller wants its WebUI login for /api/status" +
                     (hasLogin() ? "; check the user name and password in Preferences" : " (live status over the web socket doesn't)")
        }
        state.needsLogin = true
        if (!socketFresh()) markOffline()
        return
    }
    if (resp.hasError()) {
        logDebug "poll failed: ${errorText(resp)}"
        if (!socketFresh()) markOffline()
        return
    }
    if (state.needsLogin) state.remove("needsLogin")
    Map s = json(resp)
    if (s != null) update(s)
}

private Map json(resp) {
    def j = null
    try { j = resp.json } catch (Exception e) { }
    if (j == null) {
        try { j = new JsonSlurper().parseText(resp.data?.toString()) } catch (Exception e) { }
    }
    if (!(j instanceof Map)) {
        log.warn "${device.displayName}: unexpected reply from the controller"
        return null
    }
    return j
}

private String errorText(resp) {
    String m = null
    try { m = resp.errorMessage } catch (Exception e) { }
    return m ?: "HTTP ${resp.status}"
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}
