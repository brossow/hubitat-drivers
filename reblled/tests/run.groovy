// Off-hub tests for the ReBLLED LED Controller driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} and preferences{} are skipped) on
// top of HubStub, a minimal stand-in for the Hubitat sandbox. Pushes are fed to parse()
// as the hub delivers web socket messages; HTTP requests are queued, and the tests
// answer them with FakeResponse. The clock is the stub's, so tests move through time.
//
// The status objects follow the controller's /ws push (src/blled/api.h in ReBLLED).
// Addresses are documentation-only (192.0.2.x) and credentials are made up.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''
class FakeResponse {
  int status
  String data
  boolean hasError() { status < 200 || status >= 300 }
  String getErrorMessage() { hasError() ? "status code: ${status}" : null }
  def getJson() { new groovy.json.JsonSlurper().parseText(data) }
}
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map state = [:]
  Map attrs = [:]
  List events = []
  List logs = []
  List pending = []      // [handler, params, data] waiting for an answer
  List socket = []       // "connect <url>" / "close", in order
  Map scheduled = [:]    // handler name -> seconds (or "every minute")
  long clock = 1767225600000L   // 2026-01-01T00:00:00Z
  def log = [error: { m -> logs << "error: $m" }, warn: { m -> logs << "warn: $m" },
             info: { m -> logs << "info: $m" }, debug: { m -> }]
  def location = [timeZone: TimeZone.getTimeZone("America/Chicago")]
  def device = null
  def interfaces = null
  Map getSettings() { settingsMap }
  def propertyMissing(String n) { settingsMap[n] }
  long now() { clock }
  // A method, not d.clock += …: assigning a property on a Script writes to its binding
  void advance(long ms) { clock += ms }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void asynchttpPost(String h, Map p, Map d = null) { pending << [h, p, d] }
  void asynchttpGet(String h, Map p, Map d = null) { pending << [h, p, d] }
  void runIn(Number s, String h, Map o = null) { scheduled[h] = s }
  void runEvery1Minute(String h) { scheduled[h] = "every minute" }
  void unschedule(String h = null) { if (h == null) scheduled.clear() else scheduled.remove(h) }
  void setup(long id) {
    def self = this
    device = [idAsLong: id, displayName: "LED strip",
              currentValue: { String a -> self.attrs[a] },
              updateSetting: { String n, Map v -> self.settingsMap[n] = v.value }]
    interfaces = [webSocket: [connect: { String url -> self.socket << "connect " + url },
                              close: { -> self.socket << "close" }]]
  }
}''')
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
def manifest = new JsonSlurper().parse(new File(args[1]))
String filter = args.length > 2 ? args[2] : ""

long nextId = 1
def fresh = { Map settings = [:] ->
  def d = shell.parse(driverFile)
  d.setup(nextId++)   // a new device id per test, so the driver's in-memory maps don't leak between tests
  d.settingsMap.putAll([address: "192.0.2.20", onMode: "follow", txtEnable: true] + settings)
  d
}
def respond = { int status, def body = null -> gcl.loadClass("FakeResponse").newInstance(status: status, data: body == null ? "" : JsonOutput.toJson(body)) }
// Answers the oldest queued request
def answer = { d, int status, def body = null ->
  def (h, p, data) = d.pending.remove(0)
  d."$h"(respond(status, body), data)
}
def named = { d, String n -> d.events.findAll { it.name == n }*.value }
def secs = { d, int s -> d.advance(s * 1000L) }

// A status object as the controller pushes it under "lights". This one is the X1C idle
// with its chamber light off, captured from a real controller (values made neutral).
def status = { Map more = [:], Map printer = [:] ->
  [reason: "light_off", show: "off", rssi: -46, outputs: [0, 0, 0, 0, 0], brightness: 100, mode: "follow",
   firmware: "1.0.0", tool: "none",
   printer: [connected: true, accessRejected: false, certRejected: false, gcodeState: "IDLE", stage: -1,
             door: false, light: false, detectedModel: "X1C", model: "X1C", hasLidar: true] + printer] + more
}
long uptime = 1000
def push = { d, Map lights ->
  d.parse(JsonOutput.toJson([wifi_rssi: lights.rssi ?: -46, ip: "192.0.2.20", uptime: uptime++, doorOpen: false,
                             printerConnection: true, clients: 1, stg_cur: -1, lights: lights]))
}
def printing = { Map more = [:] -> status([reason: "printing", show: "colour", colour: [kind: "neutral"], rhythm: "steady", state: "printing"] + more,
                                         [gcodeState: "RUNNING", stage: 0, light: true]) }

int failures = 0, ran = 0
def test = { String name, Closure body ->
  if (!name.toLowerCase().contains(filter.toLowerCase())) return
  ran++
  boolean ok; String detail = ""
  try { def r = body(); ok = r[0]; detail = r[1].toString() }
  catch (Throwable e) { ok = false; detail = "threw ${e.class.simpleName}: ${e.message}" }
  if (!ok) failures++
  println "${ok ? 'PASS' : 'FAIL'}  $name${!ok && detail ? '  ' + detail : ''}"
}

// ── Status into attributes ────────────────────────────────────────────────

test("status: a push fills in every attribute") {
  def d = fresh(); push(d, status())
  Map want = [healthStatus: "online", mode: "follow", switch: "on", level: 100, strip: "off", stripColor: "off",
              reason: "light_off", status: "Off: following the printer's light, which is off",
              printerConnection: "connected", printerStatus: "idle", attention: "none", door: "closed",
              printerLight: "off", printerModel: "X1C", firmware: "1.0.0", rssi: -46]
  Map got = want.collectEntries { k, v -> [k, d.attrs[k]] }
  [got == want, got]
}

test("status: a repeated push sends nothing") {
  def d = fresh(); push(d, status()); int n = d.events.size()
  10.times { secs(d, 1); push(d, status([rssi: -47])) }
  [d.events.size() == n, d.events.drop(n)]
}

test("status: countdowns that tick every second send nothing") {
  def d = fresh(); push(d, status([reason: "idle", show: "colour", colour: [kind: "neutral"], rhythm: "steady", state: "idle", idleOffIn: 300]))
  int n = d.events.size()
  5.times { int i -> secs(d, 1); push(d, status([reason: "idle", show: "colour", colour: [kind: "neutral"], rhythm: "steady", state: "idle", idleOffIn: 299 - i])) }
  [d.events.size() == n, d.events.drop(n)]
}

test("status: rssi is sent when it moves 5 dBm, not for 1–2 dBm wobbles") {
  def d = fresh(); push(d, status([rssi: -46]))
  secs(d, 61); push(d, status([rssi: -48]))
  secs(d, 61); push(d, status([rssi: -52]))
  [named(d, "rssi") == [-46, -52], named(d, "rssi")]
}

test("status: a real change is sent at once, even inside the minute") {
  def d = fresh(); push(d, status()); secs(d, 1)
  push(d, status([:], [door: true]))
  [d.attrs.door == "open", named(d, "door")]
}

test("status: after a reboot (memory lost) unchanged values send nothing") {
  def d = fresh(); push(d, status())
  def d2 = fresh(); d2.attrs.putAll(d.attrs)   // same device values, empty in-memory maps
  push(d2, status())
  [d2.events.isEmpty(), d2.events]
}

test("status: no event ever has an empty value") {
  def d = fresh()
  push(d, status([reason: "", firmware: "", show: "colour", colour: [kind: "custom", hex: ""]], [model: "", gcodeState: ""]))
  def empty = d.events.findAll { it.value == null || it.value.toString() == "" }
  [empty.isEmpty(), empty]
}

test("status: a lit custom color reads as hex plus rhythm") {
  def d = fresh(); push(d, status([reason: "error", show: "colour", colour: [kind: "custom", hex: "#ff0000"], rhythm: "blink", state: "error", detail: "runout"]))
  [d.attrs.stripColor == "#FF0000, blink" && d.attrs.strip == "on", [d.attrs.stripColor, d.attrs.strip]]
}

test("status: whites, cycle and Wi-Fi have names") {
  def d = fresh()
  def names = [[show: "colour", colour: [kind: "warm"], rhythm: "steady"], [show: "cycle"], [show: "wifi", colour: [kind: "custom", hex: "#ffff00"]]].collect {
    secs(d, 1); push(d, status(it)); d.attrs.stripColor
  }
  [names == ["warm white", "color cycle", "Wi-Fi signal"], names]
}

test("status: every reason the firmware sends has its own sentence") {
  // REASON_NAMES in ReBLLED's decide.h
  def reasons = ["mode_off", "mode_on", "tool_test", "tool_cycle", "tool_wifi", "unreachable", "access_rejected", "cert_rejected",
                 "connecting", "error", "attention", "finished", "preparing", "preparing_no_lidar", "printing", "light_off", "idle_off", "idle"]
  def d = fresh()
  def raw = reasons.findAll { r -> secs(d, 1); push(d, status([reason: r])); d.attrs.status == r }
  [raw.isEmpty(), raw]
}

test("status: printer connection problems are named") {
  def d = fresh()
  def got = [[connected: false, accessRejected: true], [connected: false, certRejected: true], [connected: false]].collect {
    secs(d, 1); push(d, status([reason: "unreachable"], it)); d.attrs.printerConnection
  }
  secs(d, 1); push(d, status([reason: "connecting"], [connected: false])); got << d.attrs.printerConnection
  [got == ["access code rejected", "not verified", "unreachable", "connecting"] && d.attrs.printerStatus == "offline", [got, d.attrs.printerStatus]]
}

// ── Finished prints and attention ─────────────────────────────────────────

test("finish: a print seen running then finishing sends lastPrintFinished") {
  def d = fresh(); push(d, printing()); secs(d, 3600)
  push(d, status([reason: "finished", show: "colour", colour: [kind: "custom", hex: "#00ff00"], rhythm: "slow", state: "finished"], [gcodeState: "FINISH"]))
  def f = d.events.findAll { it.name == "lastPrintFinished" }
  [f.size() == 1 && f[0].value == "2025-12-31 19:00" && d.attrs.printerStatus == "finished", f]
}

test("finish: connecting to a printer already showing FINISH is not a new finish") {
  def d = fresh(); push(d, status([:], [gcodeState: "FINISH"]))
  [named(d, "lastPrintFinished").isEmpty(), named(d, "lastPrintFinished")]
}

test("finish: the printer coming back online on FINISH is not a new finish") {
  def d = fresh(); push(d, printing()); secs(d, 1)
  push(d, status([reason: "unreachable"], [connected: false])); secs(d, 1)
  push(d, status([:], [gcodeState: "FINISH"]))
  [named(d, "lastPrintFinished").isEmpty(), named(d, "printerStatus")]
}

test("finish: a failed print is not a finish") {
  def d = fresh(); push(d, printing()); secs(d, 1)
  push(d, status([:], [gcodeState: "FAILED"]))
  [named(d, "lastPrintFinished").isEmpty() && d.attrs.printerStatus == "failed", d.attrs.printerStatus]
}

test("attention: taken from the controller's decision while following") {
  def d = fresh(); push(d, status([reason: "attention", state: "attention", detail: "clog", show: "colour", colour: [kind: "custom", hex: "#0000ff"], rhythm: "pulse"], [gcodeState: "PAUSE", stage: 35]))
  [d.attrs.attention == "nozzle clog" && d.attrs.status == "Needs attention: nozzle clog", [d.attrs.attention, d.attrs.status]]
}

test("attention: an HMS error the stage doesn't show comes through") {
  def d = fresh(); push(d, status([reason: "error", state: "error", detail: "hmsSerious"], [gcodeState: "RUNNING", stage: 0]))
  [d.attrs.attention == "serious HMS error", d.attrs.attention]
}

test("attention: still reported with the strip switched off (mode off)") {
  def d = fresh(); push(d, status([reason: "mode_off", mode: "off"], [gcodeState: "PAUSE", stage: 6]))
  [d.attrs.attention == "filament runout" && d.attrs.switch == "off", [d.attrs.attention, d.attrs.switch]]
}

test("attention: a plain pause in mode off reads as paused, and clears") {
  def d = fresh(); push(d, status([reason: "mode_off", mode: "off"], [gcodeState: "PAUSE", stage: -1])); def first = d.attrs.attention
  secs(d, 1); push(d, status([reason: "mode_off", mode: "off"], [gcodeState: "RUNNING", stage: 0]))
  [first == "paused" && d.attrs.attention == "none", named(d, "attention")]
}

// ── Commands ──────────────────────────────────────────────────────────────

test("commands: on() sends mode follow as JSON, with no login when none is set") {
  def d = fresh(); d.on()
  def p = d.pending[0][1]
  boolean ok = p.uri == "http://192.0.2.20/api/settings" && p.requestContentType == "application/json" &&
               new JsonSlurper().parseText(p.body) == [mode: "follow"] && p.headers["X-BLLED"] && !p.headers.Authorization
  [ok, p]
}

test("commands: on() with 'always on' chosen sends mode on") {
  def d = fresh(onMode: "on"); d.on()
  [new JsonSlurper().parseText(d.pending[0][1].body) == [mode: "on"], d.pending[0][1].body]
}

test("commands: the login goes as HTTP basic auth") {
  def d = fresh(webUser: "admin", webPass: "example-pass"); d.off()
  String auth = d.pending[0][1].headers.Authorization
  [auth == "Basic " + "admin:example-pass".bytes.encodeBase64().toString(), auth]
}

test("commands: a user name without a password sends no login") {
  def d = fresh(webUser: "admin"); d.off()
  [!d.pending[0][1].headers.Authorization, d.pending[0][1].headers]
}

test("commands: setLevel while on sends only the brightness") {
  def d = fresh(); push(d, status()); d.setLevel(40)
  [new JsonSlurper().parseText(d.pending[0][1].body) == [brightness: 40], d.pending[0][1].body]
}

test("commands: setLevel while off also switches on") {
  def d = fresh(); push(d, status([mode: "off", reason: "mode_off"])); d.setLevel(40)
  [new JsonSlurper().parseText(d.pending[0][1].body) == [brightness: 40, mode: "follow"], d.pending[0][1].body]
}

test("commands: setLevel(0) switches off and keeps the brightness") {
  def d = fresh(); push(d, status()); d.setLevel(0)
  [new JsonSlurper().parseText(d.pending[0][1].body) == [mode: "off"], d.pending[0][1].body]
}

test("commands: setLevel clamps to 0–100") {
  def d = fresh(); push(d, status()); d.setLevel(150)
  [new JsonSlurper().parseText(d.pending[0][1].body) == [brightness: 100], d.pending[0][1].body]
}

test("commands: setMode refuses an unknown mode without sending") {
  def d = fresh(); d.setMode("disco")
  [d.pending.isEmpty() && d.logs.any { it.startsWith("warn:") }, d.logs]
}

test("commands: the reply updates switch, mode and level at once") {
  def d = fresh(); push(d, status()); d.off()
  answer(d, 200, [mode: "off", brightness: 100, look: "standard"])
  [d.attrs.switch == "off" && d.attrs.mode == "off", [d.attrs.switch, d.attrs.mode]]
}

test("commands: 401 says to enter the login and changes nothing") {
  def d = fresh(); push(d, status()); int n = d.events.size(); d.off()
  answer(d, 401)
  [d.events.size() == n && d.logs.any { it.contains("enter it in Preferences") }, d.logs]
}

test("commands: a network failure is logged, nothing changes") {
  def d = fresh(); push(d, status()); int n = d.events.size(); d.setLevel(20)
  answer(d, 408)
  [d.events.size() == n && d.logs.any { it.startsWith("warn:") && it.contains("couldn't send") }, d.logs]
}

test("commands: nothing is sent without an address") {
  def d = fresh(address: null); d.on()
  [d.pending.isEmpty(), d.pending]
}

// ── The web socket ────────────────────────────────────────────────────────

test("socket: initialize connects to /ws and starts the watchdog") {
  def d = fresh(); d.initialize()
  [d.socket.last() == "connect ws://192.0.2.20/ws" && d.scheduled.watchdog, [d.socket, d.scheduled]]
}

test("socket: a pasted URL is cut down to the host") {
  def d = fresh(address: " http://192.0.2.20/setup "); d.initialize()
  [d.socket.last() == "connect ws://192.0.2.20/ws", d.socket]
}

test("socket: no address means offline and no connection") {
  def d = fresh(address: ""); d.initialize()
  [d.attrs.healthStatus == "offline" && !d.socket.any { it.startsWith("connect") }, [d.attrs, d.socket]]
}

test("socket: a failure retries after 5 s, doubling to 5 minutes") {
  def d = fresh(); d.initialize()
  def delays = (1..9).collect { d.webSocketStatus("failure: Connection refused"); def s = d.scheduled.reconnect; secs(d, s as int); d.reconnect(); s }
  [delays == [5, 10, 20, 40, 80, 160, 300, 300, 300], delays]
}

test("socket: the retry reconnects and says offline once") {
  def d = fresh(); d.initialize(); secs(d, 3); push(d, status()); secs(d, 2)
  d.webSocketStatus("status: closing"); secs(d, 5); d.reconnect()
  [d.attrs.healthStatus == "offline" && d.socket.findAll { it.startsWith("connect") }.size() == 2, [named(d, "healthStatus"), d.socket]]
}

test("socket: the 'closing' of the socket initialize() replaced is ignored") {
  def d = fresh(); d.initialize()
  d.webSocketStatus("status: closing")   // the old socket, closed by connectSocket()
  [!d.scheduled.reconnect, d.scheduled]
}

test("socket: a late 'closing' from a replaced socket doesn't drop the live one") {
  def d = fresh(); d.initialize(); secs(d, 3)
  d.webSocketStatus("status: closing")   // later than expected
  secs(d, 1); push(d, status()); secs(d, 4)
  d.reconnect()
  [d.attrs.healthStatus == "online" && d.socket.findAll { it.startsWith("connect") }.size() == 1, [named(d, "healthStatus"), d.socket]]
}

test("socket: the first push after reconnecting says online again") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 1)
  d.webSocketStatus("failure: reset"); secs(d, 5); d.reconnect()
  push(d, status())
  [named(d, "healthStatus") == ["online", "offline", "online"], named(d, "healthStatus")]
}

test("socket: a good push resets the back-off") {
  def d = fresh(); d.initialize()
  3.times { d.webSocketStatus("failure: x"); secs(d, 60); d.reconnect() }
  push(d, status()); d.webSocketStatus("failure: x")
  [d.scheduled.reconnect == 5, d.scheduled.reconnect]
}

test("watchdog: gives a socket that just opened time to push") {
  def d = fresh(); d.initialize(); secs(d, 1); d.watchdog()
  [d.pending.isEmpty() && d.attrs.healthStatus != "offline", [d.pending, d.attrs.healthStatus]]
}

test("watchdog: quiet while pushes arrive") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 20); d.watchdog()
  [d.pending.isEmpty() && d.socket.findAll { it.startsWith("connect") }.size() == 1, [d.pending, d.socket]]
}

test("watchdog: a silent socket is replaced, and polled meanwhile") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 61); d.watchdog()
  boolean polled = d.pending.any { it[1].uri == "http://192.0.2.20/api/status" }
  [polled && d.socket.findAll { it.startsWith("connect") }.size() == 2, [d.pending, d.socket]]
}

test("watchdog: a poll that comes back keeps the device online") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 61); d.watchdog()
  answer(d, 200, status([:], [door: true]))
  [d.attrs.healthStatus == "online" && d.attrs.door == "open", named(d, "healthStatus")]
}

test("watchdog: without a login, 401 stops the polling and says offline") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 61); d.watchdog()
  answer(d, 401)
  secs(d, 60); d.watchdog()
  [d.attrs.healthStatus == "offline" && d.pending.isEmpty() && d.state.needsLogin, [d.attrs.healthStatus, d.pending, d.state]]
}

test("watchdog: saving preferences tries polling again") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 61); d.watchdog(); answer(d, 401)
  d.settingsMap.webUser = "admin"; d.settingsMap.webPass = "example-pass"; d.updated()
  secs(d, 61); d.watchdog()
  [d.pending.size() == 1 && d.pending[0][1].headers.Authorization, d.pending]
}

test("watchdog: saving preferences without a login also tries again once") {
  def d = fresh(); d.initialize(); push(d, status()); secs(d, 61); d.watchdog(); answer(d, 401)
  d.updated(); secs(d, 61); d.watchdog()
  [d.pending.size() == 1, d.pending]
}

test("watchdog: honours the back-off instead of reconnecting every minute") {
  def d = fresh(); d.initialize()
  d.state.retrySecs = 300; secs(d, 61); d.watchdog()
  [d.socket.findAll { it.startsWith("connect") }.size() == 1, d.socket]
}

test("watchdog: leaves a reported failure to the scheduled retry") {
  def d = fresh(); d.initialize(); secs(d, 1)
  d.webSocketStatus("failure: connect timed out"); secs(d, 60); d.watchdog()
  [d.socket.findAll { it.startsWith("connect") }.size() == 1 && d.scheduled.reconnect, d.socket]
}

test("refresh: reconnects a dead socket and polls") {
  def d = fresh(); d.initialize(); secs(d, 40); d.refresh()
  [d.socket.findAll { it.startsWith("connect") }.size() == 2 && d.pending.size() == 1, [d.socket, d.pending]]
}

test("refresh: leaves a live socket alone") {
  def d = fresh(); d.initialize(); push(d, status()); d.refresh()
  [d.socket.findAll { it.startsWith("connect") }.size() == 1, d.socket]
}

test("logs: debug logging switches itself off after 30 minutes") {
  def d = fresh(logEnable: true); d.updated()
  d.logsOff()
  [d.settingsMap.logEnable == "false" && d.scheduled.isEmpty() == false, d.settingsMap.logEnable]
}

// ── Packaging ─────────────────────────────────────────────────────────────

test("version: driver and manifest agree") {
  String src = driverFile.text
  String v = (src =~ /VERSION = "([^"]+)"/)[0][1]
  [manifest.version == v, [v, manifest.version]]
}

if (ran == 0) { println "no tests match '$filter'"; System.exit(3) }
println "\n${ran - failures} of ${ran} passed"
System.exit(failures ? 1 : 0)
