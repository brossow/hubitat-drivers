import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

/**
 * Bambu Bridge — Hubitat app
 *
 * Receives printer status from the bambu_bridge Docker container and updates
 * a Bambu Lab Printer virtual device. Also serves live dashboard tile pages
 * via local HTTP endpoints, embedded in the device's html/htmlAms attributes
 * as iframe stubs.
 *
 * Setup:
 *   1. Paste into Apps Code
 *   2. Click Save, then click "Enable OAuth" — required for endpoints to work
 *   3. Install via Apps > Add User App > Bambu Bridge
 *   4. Select your Bambu Lab Printer virtual device and configure preferences
 *   5. Copy the Bridge POST URL into HUBITAT_URL in your .env file
 *   6. Add ONE Attribute tile to your dashboard using attribute "html" — this
 *      is the combined status + AMS tile. Give it plenty of vertical space.
 *      Optionally add a second tile using "htmlAms" if you prefer AMS separate.
 *   7. In dashboard settings → Templates → Attribute → default state, set
 *      Background Color transparency to 0 so the tile background shows through.
 *
 * Endpoints:
 *   POST /update      — receives status from bridge (HUBITAT_URL points here)
 *   GET  /tile        — serves combined printer status + AMS page
 *   GET  /ams-tile    — serves AMS-only page (for separate-tile layouts)
 *   GET  /ping        — health check
 */

definition(
    name:        "Bambu Bridge",
    namespace:   "brossow",
    author:      "Brent Rossow",
    description: "Receives Bambu printer status from the bambu_bridge script",
    category:    "Integrations",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-bridge-app.groovy",
    oauth:       [displayName: "Bambu Bridge", displayLink: ""]
)

@Field static final Map UPDATE_INTERVALS = [
    "1":   "1 second ⚠️ High event volume — may affect hub performance",
    "5":   "5 seconds ⚠️ High event volume — may affect hub performance",
    "10":  "10 seconds",
    "30":  "30 seconds (default)",
    "60":  "1 minute",
    "300": "5 minutes",
]

@Field static final Map TILE_THEMES = [
    "dark":  "Dark (default — for dark dashboard backgrounds)",
    "light": "Light — for light dashboard backgrounds",
]

@Field static final Map AMS_COLUMNS = [
    "auto": "Auto (default) — fits tile width",
    "1":    "1 column",
    "2":    "2 columns",
    "3":    "3 columns",
    "4":    "4 columns",
    "6":    "6 columns",
    "8":    "8 columns",
    "12":   "12 columns (hardware maximum)",
]

preferences {
    page(name: "mainPage")
}

def mainPage() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (e) {
            log.warn "Could not create access token — did you click 'Enable OAuth' in Apps Code? Error: ${e}"
        }
    }
    _ensureStubs()

    def bridgeSection = state.accessToken
        ? "<p>Copy into <code>HUBITAT_URL</code> in your <code>.env</code> file:</p><p><code style='word-break:break-all'>${getLocalApiServerUrl()}/${app.id}/update?access_token=${state.accessToken}</code></p>"
        : "<p><b>No endpoint available.</b> Open Apps Code, find Bambu Bridge, click 'Enable OAuth', then return here.</p>"

    def tileSection = state.accessToken
        ? "<p>Add an <b>Attribute</b> tile using attribute <b>html</b> for the combined status + AMS tile. " +
          "Experiment with row/column span to get a good aspect ratio — portrait (taller than wide) works well. " +
          "Optionally add a second tile using <b>htmlAms</b> for a standalone AMS tile in wide layouts.</p>"
        : "<p>Enable OAuth first to activate dashboard tiles.</p>"

    dynamicPage(name: "mainPage", title: "Bambu Bridge", install: true, uninstall: true) {
        section("Printer Device") {
            input(name: "printerDevice", type: "capability.sensor", title: "Bambu Lab Printer virtual device", required: true)
        }
        section("Update Frequency") {
            input(name: "updateInterval", type: "enum", title: "How often to update Hubitat device events",
                  options: UPDATE_INTERVALS, defaultValue: "30", required: true)
        }
        section("Tile Appearance") {
            input(name: "tileTheme", type: "enum", title: "Color theme",
                  options: TILE_THEMES, defaultValue: "dark", required: true)
            input(name: "showAms", type: "bool", title: "Show AMS filament section in combined tile",
                  defaultValue: true, required: true)
            input(name: "amsColumns", type: "enum", title: "AMS tile columns",
                  options: AMS_COLUMNS, defaultValue: "auto", required: true)
        }
        section("Logging") {
            input(name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
        }
        section("Bridge POST URL") { paragraph bridgeSection }
        section("Dashboard Tiles")  { paragraph tileSection  }
    }
}

def installed() {
    log.info "Bambu Bridge installed (app ${app.id})"
    state.lastPush  = 0
    state.lastState = null
    _ensureStubs()
}

def updated() {
    log.info "Bambu Bridge updated — interval: ${updateInterval}s, theme: ${tileTheme}, showAms: ${showAms}"
    state.lastPush    = 0
    state.lastState   = null
    state.tileStub    = null
    state.amsTileStub = null
    _ensureStubs()
}

def uninstalled() { log.info "Bambu Bridge uninstalled" }

// ── Endpoint routing ──────────────────────────────────────────────────────────

mappings {
    path("/update")   { action: [POST: "handleUpdate"] }
    path("/tile")     { action: [GET:  "handleTile"]   }
    path("/ams-tile") { action: [GET:  "handleAmsTile"] }
    path("/ping")     { action: [GET:  "handlePing"]   }
}

// ── Handlers ──────────────────────────────────────────────────────────────────

def handleUpdate() {
    def body = request.JSON
    if (!body) {
        render status: 400, contentType: "application/json", data: '{"error":"empty body"}'
        return
    }

    state.printerData = body

    if (printerDevice) {
        _ensureStubs()
        def interval     = (updateInterval ?: "30").toInteger()
        def now          = now()
        def stateChanged = body.printerState != state.lastState
        def due          = (now - (state.lastPush ?: 0)) >= (interval * 1000)
        if (logEnable) log.debug "Bambu Bridge: received update — state=${body.printerState}, progress=${body.printProgress}%, stateChanged=${stateChanged}, secSinceLastPush=${((now - (state.lastPush ?: 0)) / 1000).toLong()}"

        if (stateChanged || due) {
            if (logEnable) log.debug "Bambu Bridge: pushing to device (stateChanged=${stateChanged}, due=${due})"
            def update = body + [html: state.tileStub, htmlAms: state.amsTileStub]
            printerDevice.updateStatus(update)
            state.lastPush  = now
            state.lastState = body.printerState
        } else {
            if (logEnable) log.debug "Bambu Bridge: throttled — interval ${interval}s not elapsed"
        }
    } else {
        log.warn "Bambu Bridge: no printer device configured"
    }

    render status: 200, contentType: "application/json", data: '{"status":"ok"}'
}

def handleTile() {
    render contentType: "text/html;charset=UTF-8",
           data: _buildCombinedPage(state.printerData ?: [:]),
           status: 200
}

def handleAmsTile() {
    render contentType: "text/html;charset=UTF-8",
           data: _buildAmsPage(state.printerData ?: [:]),
           status: 200
}

def handlePing() {
    render status: 200, contentType: "application/json",
           data: groovy.json.JsonOutput.toJson([
               status: "ok", app: "Bambu Bridge",
               device: printerDevice?.displayName ?: "none",
           ])
}

// ── Stub generation ───────────────────────────────────────────────────────────

private void _ensureStubs() {
    if (state.tileStub && state.amsTileStub) return
    if (!state.accessToken) return
    def base  = "${getLocalApiServerUrl()}/${app.id}"
    def token = state.accessToken
    state.tileStub    = _iframeStub("${base}/tile?access_token=${token}")
    state.amsTileStub = _iframeStub("${base}/ams-tile?access_token=${token}")
}

private String _iframeStub(String url) {
    return "<div style='height:100%;width:100%;overflow:hidden'><iframe src='${url}' style='height:100%;width:100%;border:none;overflow:hidden'></iframe></div>"
}

// ── Combined tile page (status + optional AMS) ────────────────────────────────

private String _buildCombinedPage(Map s) {
    // TODO: v1 assumes single nozzle. Multi-nozzle support (X2D, H2D, etc.)
    // requires knowing the MQTT payload format for additional nozzles.
    def th             = _theme(tileTheme ?: "dark")
    def showAmsSection = (showAms != false)

    // Status data
    def state_    = (s.printerState    ?: "IDLE") as String
    def color     = _stateColor(state_, th)
    def label     = _stateLabel(state_)
    def progress  = (s.printProgress   ?: 0) as int
    def file      = (s.printFile       ?: "") as String
    def remaining = (s.remainingTime   ?: 0) as int
    def curLayer  = (s.currentLayer    ?: 0) as int
    def totLayer  = (s.totalLayers     ?: 0) as int
    def nozzle    = (s.nozzleTemp      ?: 0) as double
    def nozzleTgt = (s.nozzleTargetTemp?: 0) as double
    def bed       = (s.bedTemp         ?: 0) as double
    def bedTgt    = (s.bedTargetTemp   ?: 0) as double
    def chamber   = (s.chamberTemp     ?: 0) as double
    def speed     = (s.speedLevel      ?: "Standard") as String
    def speedMag  = (s.speedMagnitude  ?: 100) as int
    def error     = (s.printError      ?: "0") as String
    def updated   = (s.lastUpdate      ?: "") as String

    def printing  = state_ in ["RUNNING", "PAUSE", "PREPARE"]
    def hasResult = state_ in ["FINISH", "FAILED"]
    def timeOnly  = updated.contains("T") ? updated.split("T")[1].replace("Z","") : updated

    def errorDiv = (error != "0")
        ? "<div class='err'>&#9888; Error ${_fmtError(error)} &mdash; check printer display</div>" : ""
    def fileDiv = file ? "<div class='file'>${_esc(file)}</div>" : ""

    def progressDiv = ""
    if (printing || hasResult || progress > 0) {
        def layers  = totLayer > 0 ? "${curLayer}/${totLayer} layers" : ""
        def timeStr = remaining > 0 ? "&#9201; ${_fmtMin(remaining)}" : ""
        progressDiv = "<div class='bar-bg'><div class='bar-fill' style='width:${progress}%;background:${color}'></div></div>" +
                      "<div class='bar-info'><span>${progress}%</span><span>${layers}</span><span>${timeStr}</span></div>"
    }

    def nozzleColor  = _tempColor(nozzle, nozzleTgt, th)
    def bedColor     = _tempColor(bed, bedTgt, th)
    def chamberColor = chamber > 0 ? th.dim : th.dimmer
    def nTgt = nozzleTgt > 0 ? "${nozzleTgt as int}&deg;" : "&mdash;"
    def bTgt = bedTgt    > 0 ? "${bedTgt    as int}&deg;" : "&mdash;"
    def cStr = chamber   > 0 ? "${chamber}&deg;"          : "&mdash;"

    def nStr = "<span style='color:${nozzleColor}'>${nozzle}&deg;</span><span class='tgt'>&nbsp;&rarr;&nbsp;${nTgt}</span>"
    def bStr = "<span style='color:${bedColor}'>${bed}&deg;</span><span class='tgt'>&nbsp;&rarr;&nbsp;${bTgt}</span>"

    def tempsDiv = "<div class='temps'>" +
        "<div><div class='tv'>${nStr}</div><div class='tl'>Nozzle</div></div>" +
        "<div><div class='tv'>${bStr}</div><div class='tl'>Bed</div></div>" +
        "<div><div class='tv' style='color:${chamberColor}'>${cStr}</div><div class='tl'>Chamber</div></div>" +
        "</div>"

    def speedDiv = printing ? "<div class='speed'>&#9889; ${speed} (${speedMag}%)</div>" : ""

    // AMS section — hidden if no data regardless of showAms setting
    def amsHtml = ""
    if (showAmsSection) {
        def amsSummary = (s.amsSummary ?: "") as String
        if (_parseAms(amsSummary)) {
            def amsBody = _amsBodyHtml(amsSummary, (s.amsTrayNow ?: 255) as int)
            amsHtml = "<hr class='sep'><div class='ams-wrap'><div class='ams-head'>AMS Filament</div>${amsBody}</div>"
        }
    }

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
.tv{font-size:clamp(13px,4.5vw,28px)}
.tl{font-size:clamp(11px,2vw,18px);color:var(--cm)}
.tgt{color:var(--cm);font-size:.85em}
.speed{font-size:clamp(11px,2.5vw,17px);color:var(--cf);text-align:center}
.sep{border:none;border-top:1px solid var(--cu);flex-shrink:0}
.ams-wrap{flex:1;display:flex;flex-direction:column;min-height:0;gap:clamp(3px,.8vw,8px)}
.ams-head{font-size:clamp(13px,3.5vw,22px);font-weight:700;color:var(--cd);flex-shrink:0}
.empty{color:var(--cm);font-size:clamp(11px,3vw,16px);margin:auto}
${_amsCss()}
.foot{font-size:clamp(10px,1.8vw,14px);color:var(--cf);text-align:right;margin-top:auto;flex-shrink:0}
</style></head><body>
<div class='state' style='color:${color}'>${label}</div>
${fileDiv}${errorDiv}${progressDiv}${tempsDiv}${speedDiv}${amsHtml}
<div class='foot'>&#128337; <span id='ts'>${timeOnly} UTC</span></div>
<script>(function(){try{var d=new Date('${updated}');if(isNaN(d.getTime()))return;var n=new Date(),same=d.toDateString()===n.toDateString(),fmt={month:'short',day:'numeric'};document.getElementById('ts').textContent=same?d.toLocaleTimeString():d.toLocaleDateString(undefined,fmt)+' '+d.toLocaleTimeString();}catch(e){}})();</script>
</body></html>"""
}

// ── AMS-only tile page ────────────────────────────────────────────────────────

private String _buildAmsPage(Map s) {
    def th      = _theme(tileTheme ?: "dark")
    def updated = (s.lastUpdate ?: "") as String
    def timeOnly = updated.contains("T") ? updated.split("T")[1].replace("Z","") : updated
    def content = _amsBodyHtml((s.amsSummary ?: "") as String, (s.amsTrayNow ?: 255) as int)

    return """<!DOCTYPE html><html lang=en><head><meta charset=UTF-8>
<meta name=viewport content="width=device-width,initial-scale=1"><meta http-equiv=refresh content=30><link rel=icon href=data:,>
<style>
:root{--bg:${th.bg};--ct:${th.text};--cd:${th.dim};--cm:${th.dimmer};--cf:${th.faint};--ao:${th.activeOutline};--cu:${th.unitBorder}}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);font-family:sans-serif;color:var(--ct);height:100vh;padding:clamp(6px,2vw,14px);display:flex;flex-direction:column;overflow:hidden}
.heading{font-size:clamp(13px,3.5vw,22px);font-weight:700;color:var(--cd);margin-bottom:clamp(4px,1vw,10px)}
.empty{color:var(--cm);font-size:clamp(11px,3vw,16px);margin:auto}
${_amsCss()}
.foot{font-size:clamp(10px,1.8vw,14px);color:var(--cf);text-align:right;padding-top:clamp(4px,1vw,8px)}
</style></head><body>
<div class='heading'>AMS Filament</div>
${content}
<div class='foot'>&#128337; <span id='ts'>${timeOnly} UTC</span></div>
<script>(function(){try{var d=new Date('${updated}');if(isNaN(d.getTime()))return;var n=new Date(),same=d.toDateString()===n.toDateString(),fmt={month:'short',day:'numeric'};document.getElementById('ts').textContent=same?d.toLocaleTimeString():d.toLocaleDateString(undefined,fmt)+' '+d.toLocaleTimeString();}catch(e){}})();</script>
</body></html>"""
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private String _amsBodyHtml(String amsSummary, int trayNow) {
    def trays = _parseAms(amsSummary)
    if (!trays) return "<div class='empty'>No AMS detected</div>"

    def content = new StringBuilder()
    def units   = [:].withDefault { [] }
    trays.each { t -> units[t.unit] << t }

    def colSetting = amsColumns ?: "auto"
    def gridCols = (colSetting == "auto")
        ? "repeat(auto-fill,minmax(160px,1fr))"
        : "repeat(${Math.min(colSetting.toInteger(), units.size())},1fr)"
    content << "<div class='ams-body' style='grid-template-columns:${gridCols}'>"
    units.keySet().sort().each { uid ->
        content << "<div class='unit'><div class='ul'>Unit ${uid + 1}</div><div class='row'>"
        units[uid].sort { it.tray }.each { t ->
            def globalIdx = uid * 4 + t.tray
            def isActive  = globalIdx == trayNow
            def isEmpty   = !t.type || t.type in ["", "—"]
            def hex       = t.color?.length() == 6 ? t.color : null
            def pct       = _parseRemain(t.remain)
            content << "<div class='tray'>"
            if (isEmpty || !hex) {
                content << "<div class='sw empty'></div><div class='tt'>&mdash;</div>"
            } else {
                def ac = isActive ? " active" : ""
                content << "<div class='sw${ac}' style='background:#${hex}'></div>"
                content << "<div class='tt'>${t.type}</div>"
                if (pct != null) content << "<div class='tp'>${pct}%</div>"
            }
            content << "</div>"
        }
        content << "</div></div>"
    }
    content << "</div>"
    return content.toString()
}

private Map _theme(String name) {
    // Colors verified for WCAG AA contrast (4.5:1 normal text, 3:1 large text).
    // Dark theme calibrated against background #1C1C1C.
    // Light theme calibrated against background #F8F8F8.
    if (name == "light") return [
        bg:            "#F8F8F8",
        text:          "#212121",   // 15.5:1 on #F8F8F8 ✓
        dim:           "#424242",   //  8.9:1 on #F8F8F8 ✓
        dimmer:        "#5A5A5A",   //  6.2:1 on #F8F8F8 ✓
        faint:         "#666666",   //  4.8:1 on #F8F8F8 ✓
        barBg:         "#E0E0E0",
        errBg:         "#FFCDD2",
        errText:       "#C62828",   //  8.4:1 on #FFCDD2 ✓
        tempIdle:      "#666666",   //  4.8:1 on #F8F8F8 ✓
        tempHeating:   "#BA4A00",   //  4.9:1 on #F8F8F8 ✓
        tempReady:     "#2E7D32",   //  6.3:1 on #F8F8F8 ✓
        stateRunning:  "#2E7D32",
        statePause:    "#BF360C",   //  7.3:1 on #F8F8F8 ✓
        stateFinish:   "#1565C0",
        stateFailed:   "#C62828",
        statePrepare:  "#6A1B9A",
        stateIdle:     "#5A5A5A",
        activeOutline: "#2E7D32",
        unitBorder:    "#CCCCCC",
    ]
    return [   // dark — calibrated to background #1C1C1C
        bg:            "#1C1C1C",
        text:          "#E0E0E0",   // 13.0:1 on #1C1C1C ✓
        dim:           "#AFAFAF",   //  8.2:1 on #1C1C1C ✓
        dimmer:        "#9E9E9E",   //  6.8:1 on #1C1C1C ✓
        faint:         "#9A9A9A",   //  6.5:1 on #1C1C1C ✓
        barBg:         "#333333",
        errBg:         "#7F1D1D",
        errText:       "#FFCDD2",   // 10.5:1 on #7f1d1d ✓
        tempIdle:      "#AFAFAF",   //  8.2:1 on #1C1C1C ✓
        tempHeating:   "#FFB74D",   //  7.5:1 on #1C1C1C ✓
        tempReady:     "#81C784",   //  6.6:1 on #1C1C1C ✓
        stateRunning:  "#66BB6A",   //  5.6:1 on #1C1C1C ✓
        statePause:    "#FF9800",   //  5.5:1 on #1C1C1C ✓
        stateFinish:   "#42A5F5",   //  5.0:1 on #1C1C1C ✓
        stateFailed:   "#FF7070",   //  4.9:1 on #1C1C1C ✓
        statePrepare:  "#CE93D8",   //  6.7:1 on #1C1C1C ✓
        stateIdle:     "#9E9E9E",   //  6.8:1 on #1C1C1C ✓
        activeOutline: "#66BB6A",
        unitBorder:    "#4A4A4A",
    ]
}

// Returns a color for a temperature reading based on whether there is an active
// target and how close the current reading is to it.
private String _tempColor(def current, def target, Map th) {
    def cur = (current ?: 0) as double
    def tgt = (target  ?: 0) as double
    if (tgt <= 0) return th.tempIdle
    if (Math.abs(cur - tgt) <= 3.0) return th.tempReady
    return th.tempHeating
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
    if (minutes < 60) return "${minutes}m"
    def h = (minutes / 60) as int
    def m = minutes % 60
    return m > 0 ? "${h}h ${m}m" : "${h}h"
}

private String _esc(String s) {
    return s?.replaceAll("&", "&amp;")?.replaceAll("<", "&lt;")?.replaceAll(">", "&gt;") ?: ""
}

private List _parseAms(String summary) {
    if (!summary) return []
    def trays = []
    summary.split(", ").each { entry ->
        def colon = entry.indexOf(":")
        if (colon < 0) return
        def loc  = entry.substring(0, colon)
        def info = entry.substring(colon + 1).split("/")
        def m    = (loc =~ /A(\d+)T(\d+)/)
        if (m.find() && info.size() >= 2) {
            trays << [
                unit:   m.group(1).toInteger(),
                tray:   m.group(2).toInteger(),
                type:   info[0]?.trim() ?: "",
                color:  info[1]?.trim() ?: "",
                remain: info.size() > 2 ? info[2]?.trim() : "?",
            ]
        }
    }
    return trays
}

private Integer _parseRemain(String remain) {
    if (!remain || remain == "?") return null
    try {
        def n = remain.replace("%","").trim().toInteger()
        return n > 0 ? n : null
    } catch (e) { return null }
}

private String _fmtError(String code) {
    if (!code || code == "0") return ""
    try {
        def n = code.toLong()
        if (n > 0) return "0x${String.format('%08X', n)}"
    } catch (e) {}
    return code
}

private String _amsCss() {
    return """\
.ams-body{flex:1;display:grid;grid-auto-rows:1fr;gap:clamp(6px,1.5vw,14px);overflow:hidden;min-height:0}
.unit{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:clamp(4px,1vmin,8px);padding:clamp(6px,2vmin,14px);border:1px solid var(--cu);border-radius:clamp(4px,1vmin,8px)}
.ul{font-size:clamp(10px,2vmin,16px);color:var(--cm);text-align:center}
.row{display:grid;grid-template-columns:repeat(4,1fr);gap:clamp(4px,2vmin,14px)}
.tray{text-align:center}
.sw{width:min(clamp(24px,10vmin,72px),100%);aspect-ratio:1;border-radius:clamp(3px,1vmin,6px);margin:0 auto}
.sw.empty{border:1px dashed var(--cm)}
.sw.active{outline:clamp(2px,.5vmin,3px) solid var(--ao);outline-offset:clamp(1px,.3vmin,3px)}
.tt{font-size:clamp(10px,2.5vmin,16px);color:var(--cd);margin-top:clamp(2px,.5vmin,5px)}
.tp{font-size:clamp(9px,2vmin,14px);color:var(--cm)}"""
}
