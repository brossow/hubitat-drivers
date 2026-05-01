import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

/**
 * Bambu Lab Printer — Hubitat app
 *
 * Companion app for the Bambu Lab Printer driver. Serves live dashboard tile pages
 * via OAuth-protected local HTTP endpoints, and handles notifications and automations
 * based on printer state changes.
 *
 * Setup:
 *   1. Paste into Apps Code
 *   2. Click Save, then click "Enable OAuth" — required for dashboard tiles to work
 *   3. Install via Apps → Add User App → Bambu Lab Printer
 *   4. Select your printer device and configure preferences
 *   5. Add an Attribute tile to your dashboard using attribute "html"
 *      Optionally add a second tile using "htmlAms" for a standalone AMS layout
 *
 * Endpoints (all require access_token query param):
 *   GET /tile      — combined status + AMS tile
 *   GET /ams-tile  — AMS-only tile
 *   GET /ping      — health check
 */

definition(
    name:        "Bambu Lab Printer",
    namespace:   "brossow",
    author:      "Brent Rossow",
    description: "Dashboard tiles, notifications, and automations for Bambu Lab printers",
    category:    "Integrations",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-lab-app.groovy",
    oauth:       [displayName: "Bambu Lab Printer", displayLink: ""]
)

@Field static final Map TILE_THEMES = [
    "dark":  "Dark (default — for dark dashboard backgrounds ~#1C1C1C)",
    "light": "Light — for light dashboard backgrounds ~#F8F8F8",
]

@Field static final Map AMS_COLUMNS = [
    "auto": "Auto (default) — fits tile width",
    "1": "1 column", "2": "2 columns", "3": "3 columns", "4": "4 columns",
    "6": "6 columns", "8": "8 columns", "12": "12 columns",
]

// ── Preferences ────────────────────────────────────────────────────────────────

preferences {
    page(name: "mainPage")
    page(name: "notificationsPage")
    page(name: "automationsPage")
}

def mainPage() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (e) {
            log.warn "Could not create access token — did you click 'Enable OAuth' in Apps Code? Error: ${e}"
        }
    }
    _pushStubs()

    def tileInfo = state.accessToken
        ? "<p>Add an <b>Attribute</b> tile to your dashboard using attribute <b>html</b> for the combined status + AMS tile. " +
          "Experiment with row/column span for a good aspect ratio — portrait (taller than wide) works well.<br><br>" +
          "Optionally add a second tile using attribute <b>htmlAms</b> for a standalone AMS tile in wide layouts.</p>" +
          "<p><b>Tip:</b> in dashboard settings → Templates → Attribute → default state, set Background Color transparency to 0 " +
          "so the tile's own background shows through cleanly.</p>"
        : "<p><b>No endpoint available.</b> Open Apps Code, find Bambu Lab Printer, click 'Enable OAuth', then return here.</p>"

    dynamicPage(name: "mainPage", title: "Bambu Lab Printer", install: true, uninstall: true) {
        section("Printer Device") {
            input name: "printerDevice", type: "capability.sensor",
                  title: "Bambu Lab Printer device", required: true,
                  description: "Select the device created with the Bambu Lab Printer driver"
        }
        section("Tile Appearance") {
            input name: "tileTheme", type: "enum", title: "Color theme",
                  options: TILE_THEMES, defaultValue: "dark", required: true
            input name: "showAms", type: "bool",
                  title: "Show AMS filament section in combined tile",
                  description: "Auto-hides if no AMS data is available regardless of this setting",
                  defaultValue: true, required: true
            input name: "amsColumns", type: "enum", title: "AMS tile columns",
                  options: AMS_COLUMNS, defaultValue: "auto", required: true
        }
        section("") {
            href "notificationsPage", title: "Notifications",
                 description: "Push alerts for print events and progress milestones"
            href "automationsPage",   title: "Event Actions",
                 description: "Trigger switches and dimmers on print start, pause, finish, and error"
        }
        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
        section("Dashboard Tiles") { paragraph tileInfo }
    }
}

def notificationsPage() {
    dynamicPage(name: "notificationsPage", title: "Notifications", nextPage: "mainPage") {
        section("Notification Device") {
            input name: "notifyDevice", type: "capability.notification",
                  title: "Send notifications to", multiple: true, required: false
        }
        section("Alert Triggers") {
            input name: "notifyOnFinish",   type: "bool", title: "Notify when print finishes",          defaultValue: true
            input name: "notifyOnStart",    type: "bool", title: "Notify when print starts",            defaultValue: false
            input name: "notifyOnPause",    type: "bool", title: "Notify when print pauses",            defaultValue: false
            input name: "notifyOnError",    type: "bool", title: "Notify on printer error",             defaultValue: true
            input name: "notifyOnFilament", type: "bool", title: "Notify on filament type change",      defaultValue: false
        }
    }
}

def automationsPage() {
    dynamicPage(name: "automationsPage", title: "Event Actions", nextPage: "mainPage") {
        section("Print Starts") {
            input name: "switchOnStart",  type: "capability.switch", title: "Turn ON",  multiple: true, required: false
            input name: "switchOffStart", type: "capability.switch", title: "Turn OFF", multiple: true, required: false
        }
        section("Print Pauses") {
            input name: "switchOnPause",  type: "capability.switch", title: "Turn ON",  multiple: true, required: false
            input name: "switchOffPause", type: "capability.switch", title: "Turn OFF", multiple: true, required: false
        }
        section("Print Finishes") {
            input name: "switchOnFinish",  type: "capability.switch",      title: "Turn ON",  multiple: true, required: false
            input name: "switchOffFinish", type: "capability.switch",      title: "Turn OFF", multiple: true, required: false
            input name: "dimmerOnFinish",  type: "capability.switchLevel", title: "Set level on these dimmers", multiple: true, required: false
            input name: "dimmerLevel",     type: "number", title: "Dimmer level (0–100)", range: "0..100", defaultValue: 100, required: false
        }
        section("Printer Error") {
            input name: "switchOnError", type: "capability.switch", title: "Turn ON", multiple: true, required: false
        }
        section("Mode Filter") {
            input name: "restrictModes", type: "mode",
                  title: "Only trigger actions when hub mode is",
                  multiple: true, required: false,
                  description: "Leave blank to trigger in all modes"
        }
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    log.info "Bambu Lab Printer app installed"
    initialize()
}

def updated() {
    log.info "Bambu Lab Printer app updated"
    unsubscribe()
    unschedule()
    state.tileStub    = null
    state.amsTileStub = null
    initialize()
}

def uninstalled() {
    log.info "Bambu Lab Printer app uninstalled"
}

private void initialize() {
    _pushStubs()
    if (!printerDevice) return

    subscribe(printerDevice, "printerState", "onPrinterStateChange")
    subscribe(printerDevice, "filamentType", "onFilamentTypeChange")

    state.lastState        = printerDevice.currentValue("printerState") ?: "IDLE"
    state.lastFilamentType = printerDevice.currentValue("filamentType") ?: "—"
}

// ── Endpoint routing ───────────────────────────────────────────────────────────

mappings {
    path("/tile")     { action: [GET: "handleTile"]    }
    path("/ams-tile") { action: [GET: "handleAmsTile"] }
    path("/ping")     { action: [GET: "handlePing"]    }
}

def handleTile() {
    render contentType: "text/html;charset=UTF-8", data: _buildCombinedPage(), status: 200
}

def handleAmsTile() {
    render contentType: "text/html;charset=UTF-8", data: _buildAmsPage(), status: 200
}

def handlePing() {
    render status: 200, contentType: "application/json",
           data: groovy.json.JsonOutput.toJson([
               status: "ok", app: "Bambu Lab Printer",
               device: printerDevice?.displayName ?: "none",
           ])
}

// ── Stub management ────────────────────────────────────────────────────────────

private void _pushStubs() {
    if (!state.accessToken) return
    String base  = "${getLocalApiServerUrl()}/${app.id}"
    String token = state.accessToken
    state.tileStub    = _iframe("${base}/tile?access_token=${token}")
    state.amsTileStub = _iframe("${base}/ams-tile?access_token=${token}")
    if (printerDevice) {
        try {
            printerDevice.setTileStubs(state.tileStub, state.amsTileStub)
        } catch (e) {
            log.warn "Could not push tile stubs to device: ${e.message}"
        }
    }
}

private String _iframe(String url) {
    return "<div style='height:100%;width:100%;overflow:hidden'><iframe src='${url}' style='height:100%;width:100%;border:none;overflow:hidden'></iframe></div>"
}

// ── Event handlers ─────────────────────────────────────────────────────────────

def onPrinterStateChange(evt) {
    String next = evt.value
    String prev = state.lastState ?: "IDLE"
    if (logEnable) log.debug "printerState: ${prev} → ${next}"

    if (next == "RUNNING" && prev != "RUNNING") {
        if (notifyOnStart) {
            String f = printerDevice.currentValue("printFile") ?: "unknown file"
            _notify("Bambu printer started: ${f}")
        }
        _automate("start")
    }

    if (next == "FINISH" && prev in ["RUNNING", "PAUSE"]) {
        if (notifyOnFinish) {
            String f = printerDevice.currentValue("printFile")    ?: "unknown file"
            String e = printerDevice.currentValue("printElapsed") ?: "—"
            _notify("Bambu printer finished: ${f} (${e})")
        }
        _automate("finish")
    }

    if (next == "PAUSE" && prev == "RUNNING") {
        if (notifyOnPause) _notify("Bambu printer paused")
        _automate("pause")
    }

    if (next == "FAILED") {
        if (notifyOnError) _notify("Bambu printer error — check the printer")
        _automate("error")
    }

    state.lastState = next
}

def onFilamentTypeChange(evt) {
    String next = evt.value
    String prev = state.lastFilamentType ?: "—"
    if (notifyOnFilament && next != prev && prev != "—")
        _notify("Bambu filament changed: ${prev} → ${next}")
    state.lastFilamentType = next
}

// ── Automations ────────────────────────────────────────────────────────────────

private void _automate(String trigger) {
    if (restrictModes && !(location.mode in restrictModes)) {
        if (logEnable) log.debug "Automation skipped — hub mode: ${location.mode}"
        return
    }
    switch (trigger) {
        case "start":
            switchOnStart?.each  { it.on()  }
            switchOffStart?.each { it.off() }
            break
        case "pause":
            switchOnPause?.each  { it.on()  }
            switchOffPause?.each { it.off() }
            break
        case "finish":
            switchOnFinish?.each { it.on()  }
            switchOffFinish?.each{ it.off() }
            dimmerOnFinish?.each { it.setLevel(dimmerLevel ?: 100) }
            break
        case "error":
            switchOnError?.each  { it.on()  }
            break
    }
}

// ── Notification helper ────────────────────────────────────────────────────────

private void _notify(String msg) {
    log.info "Notification: ${msg}"
    notifyDevice?.each { d ->
        try { d.deviceNotification(msg) }
        catch (e) { log.error "Failed to notify ${d.displayName}: ${e.message}" }
    }
}

// ── Combined tile page (status + optional AMS) ─────────────────────────────────

private String _buildCombinedPage() {
    def th = _theme(tileTheme ?: "dark")

    def state_    = (printerDevice?.currentValue("printerState")     ?: "IDLE")     as String
    def progress  = (printerDevice?.currentValue("printProgress")    ?: 0)          as int
    def elapsed   = (printerDevice?.currentValue("printElapsed")     ?: "—")        as String
    def remaining = (printerDevice?.currentValue("remainingTime")    ?: 0)          as int
    def curLayer  = (printerDevice?.currentValue("currentLayer")     ?: 0)          as int
    def totLayer  = (printerDevice?.currentValue("totalLayers")      ?: 0)          as int
    def file      = (printerDevice?.currentValue("printFile")        ?: "")         as String
    def nozzle    = (printerDevice?.currentValue("nozzleTemp")       ?: 0)          as double
    def nozzleTgt = (printerDevice?.currentValue("nozzleTargetTemp") ?: 0)          as double
    def bed       = (printerDevice?.currentValue("bedTemp")          ?: 0)          as double
    def bedTgt    = (printerDevice?.currentValue("bedTargetTemp")    ?: 0)          as double
    def chamber   = (printerDevice?.currentValue("chamberTemp")      ?: 0)          as double
    def speed     = (printerDevice?.currentValue("speedLevel")       ?: "Standard") as String
    def speedMag  = (printerDevice?.currentValue("speedMagnitude")   ?: 100)        as int
    def error     = (printerDevice?.currentValue("printError")       ?: "0")        as String
    def fType     = (printerDevice?.currentValue("filamentType")     ?: "—")        as String
    def fColor    = (printerDevice?.currentValue("filamentColor")    ?: "#000000")  as String
    def amsSummary= (printerDevice?.currentValue("amsSummary")       ?: "")         as String
    def trayNow   = (printerDevice?.currentValue("amsTrayNow")       ?: 255)        as int
    def connStatus= (printerDevice?.currentValue("connectionStatus") ?: "disconnected") as String
    def updated   = (printerDevice?.currentValue("lastUpdate")       ?: "")         as String

    def printing  = state_ in ["RUNNING", "PAUSE", "PREPARE"]
    def hasResult = state_ in ["FINISH", "FAILED"]
    def color     = _stateColor(state_, th)
    def label     = _stateLabel(state_)
    def timeOnly  = updated.contains("T") ? updated.split("T")[1].replace("Z","") : updated

    // Error banner
    def errorDiv = (error != "0")
        ? "<div class='err'>&#9888; Error ${_fmtError(error)} &mdash; check printer display</div>" : ""

    // File name
    def fileDiv = file ? "<div class='file'>${_esc(file)}</div>" : ""

    // Progress bar
    def progressDiv = ""
    if (printing || hasResult || progress > 0) {
        def layers   = totLayer > 0 ? "${curLayer}/${totLayer} layers" : ""
        def hasElap  = elapsed && elapsed != "—"
        def timeStr  = ""
        if (hasElap && remaining > 0)   timeStr = "&#8593;${elapsed} &middot; &#9201;${_fmtMin(remaining)}"
        else if (remaining > 0)          timeStr = "&#9201; ${_fmtMin(remaining)}"
        else if (hasElap && printing)    timeStr = "&#8593; ${elapsed}"
        progressDiv = "<div class='bar-bg'><div class='bar-fill' style='width:${progress}%;background:${color}'></div></div>" +
                      "<div class='bar-info'><span>${progress}%</span><span>${layers}</span><span>${timeStr}</span></div>"
    }

    // Temperatures
    def nStr = "<span style='color:${_tempColor(nozzle, nozzleTgt, th)}'>${nozzle}&deg;</span>" +
               "<span class='tgt'>&nbsp;&rarr;&nbsp;${nozzleTgt > 0 ? "${nozzleTgt as int}&deg;" : "&mdash;"}</span>"
    def bStr = "<span style='color:${_tempColor(bed, bedTgt, th)}'>${bed}&deg;</span>" +
               "<span class='tgt'>&nbsp;&rarr;&nbsp;${bedTgt > 0 ? "${bedTgt as int}&deg;" : "&mdash;"}</span>"
    def cStr = chamber > 0 ? "${chamber}&deg;" : "&mdash;"
    def tempsDiv = "<div class='temps'>" +
        "<div><div class='tv'>${nStr}</div><div class='tl'>Nozzle</div></div>" +
        "<div><div class='tv'>${bStr}</div><div class='tl'>Bed</div></div>" +
        "<div><div class='tv' style='color:${chamber > 0 ? th.dim : th.dimmer}'>${cStr}</div><div class='tl'>Chamber</div></div>" +
        "</div>"

    // Info row: speed + active filament
    def speedPart    = printing ? "&#9889; ${_esc(speed)} (${speedMag}%)" : ""
    def filamentPart = (printing && fType && fType != "—" && fColor?.startsWith("#"))
        ? "<span class='fsw' style='background:${fColor}'></span>${_esc(fType)}" : ""
    def infoRowDiv = (speedPart || filamentPart)
        ? "<div class='info-row'><span>${speedPart}</span><span class='filament'>${filamentPart}</span></div>" : ""

    // AMS section
    def amsHtml = ""
    if (showAms != false && _parseAms(amsSummary)) {
        def body = _amsBodyHtml(amsSummary, trayNow)
        amsHtml = "<hr class='sep'><div class='ams-wrap'><div class='ams-head'>AMS Filament</div>${body}</div>"
    }

    // Footer: connection dot + timestamp
    def connColor = connStatus == "connected" ? th.stateRunning : th.stateFailed
    def footLeft  = "<span style='color:${connColor}'>&#11044;</span>"

    return """<!DOCTYPE html><html lang=en><head><meta charset=UTF-8>
<meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv=refresh content=30><link rel=icon href=data:,>
<style>
:root{--bg:${th.bg};--ct:${th.text};--cd:${th.dim};--cm:${th.dimmer};--cf:${th.faint};--cb:${th.barBg};--ce-bg:${th.errBg};--ce-t:${th.errText};--ao:${th.activeOutline};--cu:${th.unitBorder}}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);font-family:sans-serif;color:var(--ct);height:100vh;padding:clamp(6px,2vw,14px);display:flex;flex-direction:column;gap:clamp(4px,1vw,10px);overflow:hidden}
.state{font-size:clamp(18px,8vw,48px);font-weight:700;text-align:center}
.file{font-size:clamp(12px,3vw,22px);color:var(--cd);text-align:center;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.err{background:var(--ce-bg);color:var(--ce-t);border-radius:4px;padding:clamp(2px,.5vw,5px) clamp(4px,1vw,10px);font-size:clamp(10px,2.5vw,15px);text-align:center}
.bar-bg{background:var(--cb);border-radius:3px;height:clamp(6px,1.5vw,12px);overflow:hidden}
.bar-fill{height:100%;border-radius:3px}
.bar-info{display:flex;justify-content:space-between;font-size:clamp(11px,2.2vw,16px);color:var(--cm);margin-top:clamp(1px,.3vw,4px)}
.temps{display:flex;justify-content:space-around;text-align:center}
.tv{font-size:clamp(13px,4.5vw,28px)}.tl{font-size:clamp(11px,2vw,18px);color:var(--cm)}
.tgt{color:var(--cm);font-size:.85em}
.info-row{display:flex;justify-content:space-between;align-items:center;font-size:clamp(11px,2.5vw,17px);color:var(--cf)}
.filament{display:flex;align-items:center;gap:clamp(3px,.5vw,6px)}
.fsw{display:inline-block;width:clamp(10px,2.5vw,18px);height:clamp(10px,2.5vw,18px);border-radius:50%;flex-shrink:0}
.sep{border:none;border-top:1px solid var(--cu);flex-shrink:0}
.ams-wrap{flex:1;display:flex;flex-direction:column;min-height:0;gap:clamp(3px,.8vw,8px);overflow-y:auto}
.ams-head{font-size:clamp(13px,3.5vw,22px);font-weight:700;color:var(--cd);flex-shrink:0}
.empty{color:var(--cm);font-size:clamp(11px,3vw,16px);margin:auto}
${_amsCss()}
.foot{font-size:clamp(10px,1.8vw,14px);color:var(--cf);margin-top:auto;flex-shrink:0;display:flex;justify-content:space-between;align-items:center}
</style></head><body>
<div class='state' style='color:${color}'>${label}</div>
${fileDiv}${errorDiv}${progressDiv}${tempsDiv}${infoRowDiv}${amsHtml}
<div class='foot'>${footLeft}<span id='ts'>${timeOnly} UTC</span></div>
<script>(function(){try{var d=new Date('${updated}');if(isNaN(d.getTime()))return;var n=new Date(),same=d.toDateString()===n.toDateString(),fmt={month:'short',day:'numeric'};document.getElementById('ts').textContent=same?d.toLocaleTimeString():d.toLocaleDateString(undefined,fmt)+' '+d.toLocaleTimeString();}catch(e){}})();</script>
</body></html>"""
}

// ── AMS-only tile page ─────────────────────────────────────────────────────────

private String _buildAmsPage() {
    def th       = _theme(tileTheme ?: "dark")
    def summary  = (printerDevice?.currentValue("amsSummary")  ?: "") as String
    def trayNow  = (printerDevice?.currentValue("amsTrayNow")  ?: 255) as int
    def updated  = (printerDevice?.currentValue("lastUpdate")  ?: "") as String
    def connStatus = (printerDevice?.currentValue("connectionStatus") ?: "disconnected") as String
    def timeOnly = updated.contains("T") ? updated.split("T")[1].replace("Z","") : updated
    def content  = _amsBodyHtml(summary, trayNow)
    def connColor = connStatus == "connected" ? _theme(tileTheme ?: "dark").stateRunning : _theme(tileTheme ?: "dark").stateFailed

    return """<!DOCTYPE html><html lang=en><head><meta charset=UTF-8>
<meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv=refresh content=30><link rel=icon href=data:,>
<style>
:root{--bg:${th.bg};--ct:${th.text};--cd:${th.dim};--cm:${th.dimmer};--cf:${th.faint};--ao:${th.activeOutline};--cu:${th.unitBorder}}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);font-family:sans-serif;color:var(--ct);height:100vh;padding:clamp(6px,2vw,14px);display:flex;flex-direction:column;overflow:hidden}
.heading{font-size:clamp(13px,3.5vw,22px);font-weight:700;color:var(--cd);margin-bottom:clamp(4px,1vw,10px);flex-shrink:0}
.ams-scroll{flex:1;overflow-y:auto;min-height:0}
.empty{color:var(--cm);font-size:clamp(11px,3vw,16px);margin:auto}
${_amsCss()}
.foot{font-size:clamp(10px,1.8vw,14px);color:var(--cf);padding-top:clamp(4px,1vw,8px);display:flex;justify-content:space-between;align-items:center}
</style></head><body>
<div class='heading'>AMS Filament</div>
<div class='ams-scroll'>${content}</div>
<div class='foot'><span style='color:${connColor}'>&#11044;</span><span id='ts'>${timeOnly} UTC</span></div>
<script>(function(){try{var d=new Date('${updated}');if(isNaN(d.getTime()))return;var n=new Date(),same=d.toDateString()===n.toDateString(),fmt={month:'short',day:'numeric'};document.getElementById('ts').textContent=same?d.toLocaleTimeString():d.toLocaleDateString(undefined,fmt)+' '+d.toLocaleTimeString();}catch(e){}})();</script>
</body></html>"""
}

// ── Rendering helpers ──────────────────────────────────────────────────────────

private String _amsBodyHtml(String summary, int trayNow) {
    def trays = _parseAms(summary)
    if (!trays) return "<div class='empty'>No AMS detected</div>"

    def units   = [:].withDefault { [] }
    trays.each { t -> units[t.unit] << t }

    def colSetting = amsColumns ?: "auto"
    def colStyle   = (colSetting == "auto")
        ? ""
        : " style='grid-template-columns:repeat(${Math.min(colSetting.toInteger(), units.size())},1fr)'"

    def sb = new StringBuilder()
    sb << "<div class='ams-body'${colStyle}>"
    units.keySet().sort().each { uid ->
        sb << "<div class='unit'><div class='ul'>Unit ${uid + 1}</div><div class='row'>"
        units[uid].sort { it.tray }.each { t ->
            int  globalIdx = uid * 4 + t.tray
            def  isActive  = globalIdx == trayNow
            def  isEmpty   = !t.type || t.type in ["", "—"]
            def  hex       = t.color?.length() == 6 ? t.color : null
            def  pct       = _parseRemain(t.remain)
            sb << "<div class='tray'>"
            if (isEmpty || !hex) {
                sb << "<div class='sw empty'></div><div class='tt'>&mdash;</div>"
            } else {
                sb << "<div class='sw${isActive ? " active" : ""}' style='background:#${hex}'></div>"
                sb << "<div class='tt'>${_esc(t.type)}</div>"
                if (pct != null) sb << "<div class='tp'>${pct}%</div>"
            }
            sb << "</div>"
        }
        sb << "</div></div>"
    }
    sb << "</div>"
    return sb.toString()
}

private Map _theme(String name) {
    // Colors verified for WCAG AA contrast (4.5:1 normal text, 3:1 large text).
    // Dark calibrated to background #1C1C1C; Light calibrated to background #F8F8F8.
    if (name == "light") return [
        bg: "#F8F8F8", text: "#212121", dim: "#424242", dimmer: "#5A5A5A", faint: "#666666",
        barBg: "#E0E0E0", errBg: "#FFCDD2", errText: "#C62828",
        tempIdle: "#666666", tempHeating: "#BA4A00", tempReady: "#2E7D32",
        stateRunning: "#2E7D32", statePause: "#BF360C", stateFinish: "#1565C0",
        stateFailed: "#C62828", statePrepare: "#6A1B9A", stateIdle: "#5A5A5A",
        activeOutline: "#2E7D32", unitBorder: "#CCCCCC",
    ]
    return [   // dark
        bg: "#1C1C1C", text: "#E0E0E0", dim: "#AFAFAF", dimmer: "#9E9E9E", faint: "#9A9A9A",
        barBg: "#333333", errBg: "#7F1D1D", errText: "#FFCDD2",
        tempIdle: "#AFAFAF", tempHeating: "#FFB74D", tempReady: "#81C784",
        stateRunning: "#66BB6A", statePause: "#FF9800", stateFinish: "#42A5F5",
        stateFailed: "#FF7070", statePrepare: "#CE93D8", stateIdle: "#9E9E9E",
        activeOutline: "#66BB6A", unitBorder: "#4A4A4A",
    ]
}

private String _tempColor(def current, def target, Map th) {
    double cur = (current ?: 0) as double
    double tgt = (target  ?: 0) as double
    if (tgt <= 0) return th.tempIdle
    return Math.abs(cur - tgt) <= 3.0 ? th.tempReady : th.tempHeating
}

private String _stateColor(String st, Map th) {
    switch (st) {
        case "RUNNING": return th.stateRunning
        case "PAUSE":   return th.statePause
        case "FINISH":  return th.stateFinish
        case "FAILED":  return th.stateFailed
        case "PREPARE": return th.statePrepare
        default:        return th.stateIdle
    }
}

private String _stateLabel(String st) {
    switch (st) {
        case "RUNNING": return "&#9654; Printing"
        case "PAUSE":   return "&#9646;&#9646; Paused"
        case "FINISH":  return "&#10003; Finished"
        case "FAILED":  return "&#10007; Failed"
        case "PREPARE": return "&#9881; Preparing"
        default:        return "Idle"
    }
}

private String _fmtMin(int minutes) {
    if (minutes <= 0) return ""
    int h = (minutes / 60) as int
    int m = minutes % 60
    return m > 0 ? "${h}h ${m}m" : "${h}h"
}

private String _fmtError(String code) {
    if (!code || code == "0") return ""
    try {
        long n = code.toLong()
        if (n > 0) return "0x${String.format('%08X', n)}"
    } catch (ignored) { }
    return code
}

private String _esc(String s) {
    return s?.replaceAll("&", "&amp;")?.replaceAll("<", "&lt;")?.replaceAll(">", "&gt;") ?: ""
}

private List _parseAms(String summary) {
    if (!summary) return []
    def trays = []
    summary.split(", ").each { entry ->
        int colon = entry.indexOf(":")
        if (colon < 0) return
        def loc  = entry.substring(0, colon)
        def info = entry.substring(colon + 1).split("/")
        def m    = (loc =~ /A(\d+)T(\d+)/)
        if (m.find() && info.size() >= 2) {
            trays << [unit: m.group(1).toInteger(), tray: m.group(2).toInteger(),
                      type: info[0]?.trim() ?: "", color: info[1]?.trim() ?: "",
                      remain: info.size() > 2 ? info[2]?.trim() : "?"]
        }
    }
    return trays
}

private Integer _parseRemain(String remain) {
    if (!remain || remain == "?") return null
    try {
        int n = remain.replace("%", "").trim().toInteger()
        return n > 0 ? n : null
    } catch (ignored) { return null }
}

private String _amsCss() {
    return """\
.ams-body{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));grid-auto-rows:auto;align-content:start;gap:clamp(6px,1.5vw,14px);min-height:0}
.unit{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:clamp(4px,1vmin,8px);padding:clamp(6px,1.5vmin,10px);border:1px solid var(--cu);border-radius:clamp(4px,1vmin,8px);overflow:hidden}
.ul{font-size:clamp(10px,2vmin,16px);color:var(--cm);text-align:center}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(38px,1fr));gap:clamp(3px,1vw,6px);width:100%}
.tray{text-align:center;min-width:0}
.sw{width:min(40px,100%);aspect-ratio:1;border-radius:clamp(3px,1vmin,6px);margin:0 auto}
.sw.empty{border:1px dashed var(--cm)}
.sw.active{outline:clamp(2px,.5vmin,3px) solid var(--ao);outline-offset:clamp(1px,.3vmin,3px)}
.tt{font-size:clamp(10px,2.5vmin,16px);color:var(--cd);margin-top:clamp(2px,.5vmin,5px)}
.tp{font-size:clamp(9px,2vmin,14px);color:var(--cm)}"""
}
