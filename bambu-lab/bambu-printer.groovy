/**
 * Bambu Lab Printer — Hubitat driver
 *
 * Copyright 2026 Brent Rossow
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based in part on the Bambu Lab 3D Printer driver by jonnyborbs
 * (https://github.com/jonnyborbs/hubitat-bambu-printers), Apache-2.0. The MQTT
 * connection handling, the device preferences (including the optional MQTT relay)
 * and parts of the status parsing follow that driver; this file has been modified
 * and extended by Brent Rossow.
 */

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@Field static final String VERSION = "1.0.0"
@Field static final Map    SPEED_LABELS = [1: "Quiet", 2: "Standard", 3: "Sport", 4: "Ludicrous"]
@Field static final int    TRAY_EXTERNAL = 254   // tray_now: printing from the external spool
@Field static final int    TRAY_NONE     = 255   // tray_now: nothing loaded
@Field static final long   LAST_UPDATE_EVERY_MS = 60000L

// Per-message bookkeeping kept in memory, not state: printers report about once a
// second while printing, and every state write is a database write. Lost on a hub
// reboot or code save, which only means one extra lastUpdate event.
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long> lastMessageAt  = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.ConcurrentHashMap<Long, Long> lastUpdateSent = new java.util.concurrent.ConcurrentHashMap()

metadata {
    definition(
        name:      "Bambu Lab Printer",
        namespace: "brossow",
        author:    "Brent Rossow",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-printer.groovy",
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        // Connection
        attribute "connectionStatus",  "string"   // connected | disconnected

        // Print state
        attribute "printerState",      "string"   // IDLE | PREPARE | RUNNING | PAUSE | FINISH | FAILED
        attribute "printFile",         "string"
        attribute "printProgress",     "number"   // 0–100 %
        attribute "printElapsed",      "string"   // H:MM since the print started; kept after it finishes
        attribute "remainingTime",     "number"   // minutes (raw, for automations)
        attribute "currentLayer",      "number"
        attribute "totalLayers",       "number"

        // Temperatures
        attribute "nozzleTemp",        "number"
        attribute "nozzleTargetTemp",  "number"
        attribute "bedTemp",           "number"
        attribute "bedTargetTemp",     "number"
        attribute "chamberTemp",       "number"

        // Speed
        attribute "speedLevel",        "string"   // Quiet | Standard | Sport | Ludicrous
        attribute "speedMagnitude",    "number"   // %

        // Filament (currently active tray)
        attribute "filamentType",      "string"
        attribute "filamentColor",     "string"   // #RRGGBB

        // AMS
        attribute "amsSummary",        "string"   // A0T0:PLA/FF0000/75%, …
        attribute "amsTrayNow",        "number"   // global tray index; 255 = none / external spool

        // Other
        attribute "chamberLight",      "string"   // on | off
        attribute "printError",        "string"   // error code; "0" = no error
        attribute "wifiSignal",        "string"
        attribute "cameraUrl",         "string"   // RTSP stream URL
        attribute "lastUpdate",        "string"   // ISO-8601 UTC
        attribute "driverVersion",     "string"

        // Dashboard tile stubs — iframe HTML pushed by the companion app
        attribute "html",              "string"
        attribute "htmlAms",           "string"

        command "connect"
        command "disconnect"
        command "setTileStubs", [
            [name: "tile",    type: "STRING", description: "Combined tile iframe stub"],
            [name: "amsTile", type: "STRING", description: "AMS-only tile iframe stub"],
        ]
    }

    preferences {
        input name: "printerIP",
              type: "text",
              title: "Printer IP Address",
              description: "Local IP — assign a static lease in your router (e.g. 192.168.1.50)",
              required: true

        input name: "printerSerial",
              type: "text",
              title: "Printer Serial Number",
              description: "Touchscreen: Settings → Device Info, or visible in Bambu Studio",
              required: true

        input name: "lanAccessCode",
              type: "password",
              title: "LAN Access Code",
              description: "Touchscreen: Settings → Network",
              required: true

        input name: "refreshInterval",
              type: "integer",
              title: "Status Refresh Interval (seconds)",
              description: "How often to ask the printer for a full status report. Live updates arrive in between; frequent full reports can make P1-series printers lag.",
              defaultValue: 300,
              range: "300..3600",
              required: false

        input name: "mqttRelayHost",
              type: "text",
              title: "MQTT Relay Host (optional)",
              description: "IP of a local Mosquitto relay — leave blank for direct SSL connection to the printer. Use this only if the direct connection does not work on your hub.",
              required: false

        input name: "mqttRelayPort",
              type: "integer",
              title: "MQTT Relay Port",
              description: "Port of the local relay (default: 1883)",
              defaultValue: 1883,
              required: false

        input name: "logEnable",
              type: "bool",
              title: "Enable Debug Logging",
              defaultValue: false
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName}: installed v${VERSION}"
    _initAttributes()
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences saved — reconnecting"
    sendEvent(name: "driverVersion", value: VERSION)
    unschedule()
    // _initAttributes() is intentionally skipped here — preserves printStartTime across
    // preference saves so elapsed tracking is not lost if preferences are edited mid-print.
    _stopMqtt()
    pauseExecution(1000)
    initialize()
}

def initialize() {
    connect()
    _scheduleRefresh()
    runEvery1Minute("_refreshElapsed")
}

def uninstalled() {
    disconnect()
    unschedule()
}

// ── MQTT connection ────────────────────────────────────────────────────────────

def connect() {
    if (!settings.printerSerial) {
        log.warn "${device.displayName}: cannot connect — serial number not configured"
        return
    }

    String clientId = "hubitat-bambu-${settings.printerSerial}"

    if (settings.mqttRelayHost) {
        String broker = "tcp://${settings.mqttRelayHost}:${settings.mqttRelayPort ?: 1883}"
        log.info "${device.displayName}: connecting via relay ${broker}"
        try {
            interfaces.mqtt.connect(broker, clientId, null, null)
        } catch (e) { _onConnectError(e) }
    } else {
        if (!settings.printerIP || !settings.lanAccessCode) {
            log.warn "${device.displayName}: cannot connect — printer IP and LAN access code required"
            return
        }
        String broker = "ssl://${settings.printerIP}:8883"
        log.info "${device.displayName}: connecting to ${broker}"
        try {
            interfaces.mqtt.connect(broker, clientId, "bblp", settings.lanAccessCode as String, ignoreSSLIssues: true)
        } catch (e) { _onConnectError(e) }
    }
}

private void _onConnectError(Exception e) {
    log.error "${device.displayName}: MQTT connect failed — ${e.message}"
    sendEvent(name: "connectionStatus", value: "disconnected")
    _scheduleReconnect()
}

def disconnect() {
    _stopMqtt()
}

// Cleanly stops MQTT. When manual=true (user called disconnect()), clears the
// reconnect schedule and marks the connection as intentionally stopped so the
// mqttClientStatus callback does not schedule an automatic reconnect.
private void _stopMqtt(boolean manual = true) {
    if (manual) {
        state.stopped = true
        unschedule("connect")
        state.reconnectDelay = 0
    }
    try { interfaces.mqtt.disconnect() } catch (ignored) { }
    sendEvent(name: "connectionStatus", value: "disconnected")
}

// Platform callback — fires whenever the MQTT connection state changes.
def mqttClientStatus(String status) {
    _dbg("mqttClientStatus: ${status}")
    if (status.startsWith("Status: Connection succeeded")) {
        log.info "${device.displayName}: MQTT connected"
        state.reconnectDelay = 0
        state.stopped = false
        sendEvent(name: "connectionStatus", value: "connected")
        // Subscribe after a short delay — the MQTT client needs a moment to
        // fully settle before subscribe packets are reliably processed by the broker.
        runIn(1, "_onConnect")
    } else {
        log.warn "${device.displayName}: MQTT status: ${status}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        if (!state.stopped) _scheduleReconnect()
        state.stopped = false
    }
}

def _onConnect() {
    String topic = "device/${settings.printerSerial}/report"
    interfaces.mqtt.subscribe(topic, 1)
    log.info "${device.displayName}: subscribed to ${topic}"
    pauseExecution(500)
    refresh()
}

// Back-off schedule: 20 s → 60 s → 180 s → 360 s (cap)
private void _scheduleReconnect() {
    int base  = (state.reconnectDelay ?: 0) as int
    int delay = base == 0 ? 20 : (base <= 20 ? 60 : (base <= 60 ? 180 : 360))
    state.reconnectDelay = delay
    log.info "${device.displayName}: reconnect in ${delay}s"
    runIn(delay, "connect")
}

private int _refreshSeconds() {
    return Math.max(300, (settings.refreshInterval ?: 300) as int)
}

private void _scheduleRefresh() {
    runIn(_refreshSeconds(), "_timedRefresh")
}

def _timedRefresh() {
    int  interval  = _refreshSeconds()
    Long lastMsg   = lastMessageAt[device.idAsLong]
    long silenceMs = now() - (lastMsg ?: 0L)
    if (lastMsg && silenceMs > interval * 2 * 1000L) {
        log.warn "${device.displayName}: no MQTT traffic for ${silenceMs / 1000}s — forcing reconnect"
        _stopMqtt(false)
        pauseExecution(1000)
        connect()
    } else {
        refresh()
    }
    _scheduleRefresh()
}

// ── Incoming messages ──────────────────────────────────────────────────────────

def parse(String raw) {
    def msg = interfaces.mqtt.parseMessage(raw)
    lastMessageAt[device.idAsLong] = now()
    _dbg("message on ${msg.topic}")

    Map json
    try {
        json = new JsonSlurper().parseText(msg.payload)
    } catch (e) {
        log.error "${device.displayName}: JSON parse error — ${e.message}"
        return
    }

    if (!json?.print) {
        _dbg("no 'print' key (top-level keys: ${json?.keySet()})")
        return
    }

    try {
        _processPayload(json)
    } catch (e) {
        log.error "${device.displayName}: payload processing error — ${e.message}"
    }
}

private void _processPayload(Map json) {
    def p = json.print

    // ── State + elapsed tracking ───────────────────────────────
    // printElapsed is settled before printerState changes, because whatever reacts to
    // FINISH (the app's notification) reads it straight afterwards. A finished print
    // keeps its final time until the next print starts.
    boolean stateChanged = false
    if (p.containsKey("gcode_state")) {
        String gs = (p.gcode_state as String).toUpperCase()
        if (gs == "RUNNING") {
            if (!state.printStartTime) {
                state.printStartTime = now()
                _refreshElapsed()
            }
        } else if (gs in ["FINISH", "FAILED"]) {
            if (state.printStartTime) {
                _refreshElapsed()
                state.printStartTime = null
            }
        } else if (gs in ["IDLE", "PREPARE"]) {
            state.printStartTime = null
            sendEvent(name: "printElapsed", value: "—")
        }
        stateChanged = device.currentValue("printerState") != gs
        sendEvent(name: "printerState", value: gs)
    }

    // ── Progress ───────────────────────────────────────────────
    if (p.containsKey("mc_percent"))
        sendEvent(name: "printProgress",  value: (p.mc_percent        as int), unit: "%")
    if (p.containsKey("mc_remaining_time"))
        sendEvent(name: "remainingTime",  value: (p.mc_remaining_time as int), unit: "min")
    if (p.containsKey("layer_num"))
        sendEvent(name: "currentLayer",   value: (p.layer_num         as int))
    if (p.containsKey("total_layer_num"))
        sendEvent(name: "totalLayers",    value: (p.total_layer_num   as int))

    // ── File ───────────────────────────────────────────────────
    if (p.containsKey("subtask_name") || p.containsKey("gcode_file")) {
        String f = ((p.subtask_name ?: p.gcode_file) ?: "") as String
        sendEvent(name: "printFile", value: f.tokenize("/").last() ?: "")
    }

    // ── Temperatures ───────────────────────────────────────────
    if (p.containsKey("nozzle_temper"))
        sendEvent(name: "nozzleTemp",       value: Math.round(p.nozzle_temper        as double), unit: "°C")
    if (p.containsKey("nozzle_target_temper"))
        sendEvent(name: "nozzleTargetTemp", value: Math.round(p.nozzle_target_temper as double), unit: "°C")
    if (p.containsKey("bed_temper"))
        sendEvent(name: "bedTemp",          value: Math.round(p.bed_temper           as double), unit: "°C")
    if (p.containsKey("bed_target_temper"))
        sendEvent(name: "bedTargetTemp",    value: Math.round(p.bed_target_temper    as double), unit: "°C")

    // Chamber temp: newer firmware packs current+target into device.ctc.info.temp
    // (low 16 bits = current °C). Older firmware reports flat chamber_temper in print.
    def devBlock  = json.device ?: p.device
    def ctcPacked = devBlock?.ctc?.info?.temp
    if (ctcPacked != null) {
        sendEvent(name: "chamberTemp", value: (ctcPacked & 0xFFFF), unit: "°C")
    } else if (p.containsKey("chamber_temper")) {
        sendEvent(name: "chamberTemp", value: Math.round(p.chamber_temper as double), unit: "°C")
    }

    // ── Speed ──────────────────────────────────────────────────
    if (p.containsKey("spd_lvl"))
        sendEvent(name: "speedLevel",     value: SPEED_LABELS[(p.spd_lvl as int)] ?: "Standard")
    if (p.containsKey("spd_mag"))
        sendEvent(name: "speedMagnitude", value: (p.spd_mag as int), unit: "%")

    // ── Chamber light ──────────────────────────────────────────
    if (p.containsKey("lights_report")) {
        p.lights_report?.each { light ->
            if (light?.node == "chamber_light")
                sendEvent(name: "chamberLight", value: light.mode == "on" ? "on" : "off")
        }
    }

    // ── AMS / filament ─────────────────────────────────────────
    if (p.containsKey("vt_tray")) _storeVtTray(p.vt_tray)
    if (p.containsKey("ams"))     _processAms(p.ams)
    if (p.containsKey("ams") || p.containsKey("vt_tray")) _updateActiveFilament()

    // ── Misc ───────────────────────────────────────────────────
    if (p.containsKey("mc_print_error_code"))
        sendEvent(name: "printError", value: (p.mc_print_error_code as String))
    if (p.containsKey("wifi_signal"))
        sendEvent(name: "wifiSignal", value: (p.wifi_signal          as String))
    if (p.ipcam?.rtsp_url)
        sendEvent(name: "cameraUrl",  value: (p.ipcam.rtsp_url       as String))

    // Stamped to the second, so sending it with every message would add an event per
    // message (about one a second while printing). Once a minute, or on a state change.
    long nowMs = now()
    Long sentAt = lastUpdateSent[device.idAsLong]
    if (stateChanged || sentAt == null || nowMs - sentAt >= LAST_UPDATE_EVERY_MS) {
        lastUpdateSent[device.idAsLong] = nowMs
        sendEvent(name: "lastUpdate", value: new Date(nowMs).format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")))
    }
}

// P1 and A1 printers send partial updates: an AMS block may hold only tray_now, or
// only the trays that changed. Merge each update into a stored copy instead of
// rebuilding the summary from whatever arrived. state.amsCache is
// [unitId: [trayId: [tray_type, tray_color, remain]]], keyed by strings.
private void _processAms(def amsBlock) {
    if (!(amsBlock instanceof Map)) return

    if (amsBlock.containsKey("tray_now")) {
        state.trayNow = _parseTrayNow(amsBlock.tray_now)
        sendEvent(name: "amsTrayNow", value: state.trayNow)
    }

    def units = amsBlock.ams
    if (!(units instanceof List)) return

    Map old   = state.amsCache ?: [:]
    Map cache = old.collectEntries { uid, trays -> [(uid): trays.collectEntries { tid, t -> [(tid): new LinkedHashMap(t)] }] }
    units.each { unit ->
        if (unit?.id == null) return
        String uid  = unit.id.toString()
        Map    trays = cache[uid] ?: [:]
        (unit.tray ?: []).each { tray ->
            if (tray?.id == null) return
            String tid = tray.id.toString()
            if (tray.keySet() == ["id"] as Set) {
                trays.remove(tid)               // a tray with nothing but its id is an empty slot
                return
            }
            Map t = trays[tid] ?: [:]
            ["tray_type", "tray_color", "remain"].each { k -> if (tray.containsKey(k)) t[k] = tray[k] }
            trays[tid] = t
        }
        cache[uid] = trays
    }

    // ams_exist_bits, when present, says which units are connected: drop any that aren't
    if (amsBlock.ams_exist_bits != null) {
        try {
            int bits = Integer.parseInt(amsBlock.ams_exist_bits.toString(), 16)
            cache = cache.findAll { uid, trays -> uid.isInteger() && (bits & (1 << uid.toInteger())) != 0 }
        } catch (ignored) { }
    }

    if (cache != old) state.amsCache = cache
    sendEvent(name: "amsSummary", value: _amsSummary(cache))
}

private String _amsSummary(Map cache) {
    def parts = []
    cache.keySet().sort { it.toInteger() }.each { uid ->
        Map trays = cache[uid]
        trays.keySet().sort { it.toInteger() }.each { tid ->
            Map    t     = trays[tid]
            String mtype = (t.tray_type ?: "—") as String
            String color = ((t.tray_color ?: "") as String).take(6)
            def    rem   = t.remain
            String pct   = (rem instanceof Number && rem >= 0) ? "${rem as int}%" : "?"
            parts << "A${uid}T${tid}:${mtype}/${color}/${pct}"
        }
    }
    return parts.join(", ")
}

private void _storeVtTray(def vt) {
    if (!(vt instanceof Map)) return
    Map stored = new LinkedHashMap(state.vtTray ?: [:])
    ["tray_type", "tray_color"].each { k -> if (vt.containsKey(k)) stored[k] = vt[k] }
    if (stored != state.vtTray) state.vtTray = stored
}

// The active filament comes from tray_now: 254 is the external spool, 0–253 an AMS
// slot (unit × 4 + slot). Anything else (255, nothing loaded) leaves it as it was.
private void _updateActiveFilament() {
    Integer trayNow = state.trayNow as Integer
    if (trayNow == null) return
    Map tray = null
    if (trayNow == TRAY_EXTERNAL) {
        tray = state.vtTray
    } else if (trayNow >= 0 && trayNow < TRAY_EXTERNAL) {
        tray = state.amsCache?.get(trayNow.intdiv(4).toString())?.get((trayNow % 4).toString())
    }
    if (tray?.tray_type) {
        sendEvent(name: "filamentType",  value: tray.tray_type as String)
        sendEvent(name: "filamentColor", value: _hexColor(tray.tray_color as String))
    }
}

// ── Commands ───────────────────────────────────────────────────────────────────

def refresh() {
    _publish([pushing: [sequence_id: _nextSeq(), command: "pushall", version: 1, push_target: 1]])
}

def setTileStubs(String tile, String amsTile) {
    sendEvent(name: "html",    value: tile)
    sendEvent(name: "htmlAms", value: amsTile)
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private void _publish(Map payload) {
    if (device.currentValue("connectionStatus") != "connected") {
        log.warn "${device.displayName}: cannot publish — not connected"
        return
    }
    String topic = "device/${settings.printerSerial}/request"
    try {
        interfaces.mqtt.publish(topic, JsonOutput.toJson(payload), 1, false)
    } catch (e) {
        log.error "${device.displayName}: publish failed — ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        _scheduleReconnect()
    }
}

// Runs every minute, and on print start and finish. Between prints printElapsed keeps
// the last print's time; IDLE and PREPARE reset it to "—".
def _refreshElapsed() {
    if (!state.printStartTime) return
    long mins = (now() - (state.printStartTime as long)).intdiv(60000L)
    sendEvent(name: "printElapsed", value: String.format("%d:%02d", mins.intdiv(60L), mins % 60L))
}

private void _initAttributes() {
    sendEvent(name: "driverVersion",    value: VERSION)
    sendEvent(name: "connectionStatus", value: "disconnected")
    sendEvent(name: "printerState",     value: "IDLE")
    sendEvent(name: "printProgress",    value: 0,    unit: "%")
    sendEvent(name: "printElapsed",     value: "—")
    sendEvent(name: "remainingTime",    value: 0,    unit: "min")
    sendEvent(name: "currentLayer",     value: 0)
    sendEvent(name: "totalLayers",      value: 0)
    sendEvent(name: "nozzleTemp",       value: 0,    unit: "°C")
    sendEvent(name: "nozzleTargetTemp", value: 0,    unit: "°C")
    sendEvent(name: "bedTemp",          value: 0,    unit: "°C")
    sendEvent(name: "bedTargetTemp",    value: 0,    unit: "°C")
    sendEvent(name: "chamberTemp",      value: 0,    unit: "°C")
    sendEvent(name: "speedLevel",       value: "Standard")
    sendEvent(name: "speedMagnitude",   value: 100,  unit: "%")
    sendEvent(name: "filamentType",     value: "—")
    sendEvent(name: "filamentColor",    value: "#000000")
    sendEvent(name: "amsSummary",       value: "")
    sendEvent(name: "amsTrayNow",       value: 255)
    sendEvent(name: "chamberLight",     value: "off")
    sendEvent(name: "printError",       value: "0")
    sendEvent(name: "wifiSignal",       value: "")
    sendEvent(name: "cameraUrl",        value: "")
    sendEvent(name: "lastUpdate",       value: "")
    sendEvent(name: "printFile",        value: "")
}

private int _parseTrayNow(def val) {
    try { return val.toString().toInteger() } catch (ignored) { return TRAY_NONE }
}

private String _hexColor(String raw) {
    if (!raw || raw.length() < 6) return "#000000"
    return "#${raw.take(6).toUpperCase()}"
}

private String _nextSeq() {
    state.sequenceId = ((state.sequenceId ?: 0) + 1) % 10000
    return state.sequenceId.toString()
}

private void _dbg(String msg) {
    if (settings.logEnable) log.debug "${device.displayName}: ${msg}"
}
