// Off-hub tests for the Bambu Lab Printer driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} is skipped) on top of HubStub,
// a minimal stand-in for the Hubitat sandbox. MQTT messages are fed to parse()
// exactly as the hub would deliver them, with a clock the tests control, and the
// events the driver sends are recorded in order.
//
// Fixtures use the field names of the Bambu local MQTT protocol. Every identifier in
// them is a placeholder: never put a real serial number or access code here.

import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map attrs = [:]
  Map state = [:]
  List events = []
  List published = []
  long clock = 1767225600000L   // 2026-01-01T00:00:00Z
  def log = [warn: { m -> }, info: { m -> }, debug: { m -> }, error: { m -> }]
  def device = null
  def interfaces = null
  Map getSettings() { settingsMap }
  def propertyMissing(String n) { settingsMap[n] }
  long now() { clock }
  // A method, not d.clock += …: assigning a property on a Script writes to its binding
  void advance(long ms) { clock += ms }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void runIn(def s, String h) {}
  void runEvery1Minute(String h) {}
  void unschedule(String h = null) {}
  void pauseExecution(def ms) {}
  void setup(long id) {
    def self = this
    device = [idAsLong: id, displayName: "Test printer",
              currentValue: { String a -> self.attrs[a] }]
    interfaces = [mqtt: [parseMessage: { String raw -> [topic: "device/SERIAL-PLACEHOLDER/report", payload: raw] },
                         publish: { String t, String p, int q, boolean r -> self.published << p }]]
  }
}''')
// Records every write, even one that stores the value already there: on the hub,
// assigning to state is what costs a database write
gcl.parseClass('''
class WriteLog extends LinkedHashMap {
  List writes = []
  WriteLog(Map m) { super(m) }
  Object put(Object k, Object v) { writes << k; super.put(k, v) }
  Object remove(Object k) { writes << k; super.remove(k) }
}''')
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
String filter = args.length > 1 ? args[1] : ""

long nextId = 1
def fresh = { Map settings = [:] ->
  def d = shell.parse(driverFile)
  d.setup(nextId++)   // a new device id per test, so the driver's in-memory maps don't leak between tests
  d.settingsMap.putAll([printerSerial: "SERIAL-PLACEHOLDER"] + settings)
  d.attrs.connectionStatus = "connected"
  d
}
def send = { d, Map print, Map extra = [:] -> d.parse(JsonOutput.toJson([print: print] + extra)) }
def minutes = { d, int m -> d.advance(m * 60000L) }
def named = { d, String n -> d.events.findAll { it.name == n }*.value }

// A full report, as an X1C sends every second while printing
def tray = { id, type, color, remain -> [id: "$id", tray_type: type, tray_color: color + "FF", remain: remain] }
def fullReport = { String gs, int trayNow, Map more = [:] ->
  [gcode_state: gs, mc_percent: 40, nozzle_temper: 220.4, bed_temper: 60.1,
   ams: [tray_now: "$trayNow", ams_exist_bits: "3",
         ams: [[id: "0", tray: [tray(0, "PLA", "FF0000", 80), tray(1, "PETG", "00FF00", 50),
                                tray(2, "PLA", "0000FF", 20), tray(3, "ABS", "FFFFFF", 10)]],
               [id: "1", tray: [tray(0, "TPU", "111111", 90), tray(1, "PLA", "222222", 0),
                                tray(2, "ASA", "333333", 30), [id: "3"]]]]],
   vt_tray: [id: "254", tray_type: "PETG-CF", tray_color: "444444FF"]] + more
}

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

// ── Elapsed time and the finish notification ─────────────────────────────

test("finish: the final elapsed time is sent before printerState FINISH") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); minutes(d, 83)
  send(d, [gcode_state: "FINISH"])
  int iTime  = d.events.findLastIndexOf { it.name == "printElapsed" && it.value == "1:23" }
  int iState = d.events.findIndexOf { it.name == "printerState" && it.value == "FINISH" }
  [iTime >= 0 && iTime < iState, d.events.findAll { it.name in ["printElapsed", "printerState"] }]
}

test("finish: the finished print keeps its time afterwards") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); minutes(d, 83)
  send(d, [gcode_state: "FINISH"]); minutes(d, 30)
  send(d, [gcode_state: "FINISH"]); d._refreshElapsed()
  [d.attrs.printElapsed == "1:23", d.attrs.printElapsed]
}

test("finish: a failed print also keeps its time") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); minutes(d, 5)
  send(d, [gcode_state: "FAILED"])
  [d.attrs.printElapsed == "0:05", d.attrs.printElapsed]
}

test("elapsed: preparing the next print clears the last print's time") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); minutes(d, 10)
  send(d, [gcode_state: "FINISH"])
  send(d, [gcode_state: "PREPARE"])
  [d.attrs.printElapsed == "—", d.attrs.printElapsed]
}

test("elapsed: a pause doesn't restart the clock") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); minutes(d, 20)
  send(d, [gcode_state: "PAUSE"]);   minutes(d, 10)
  send(d, [gcode_state: "RUNNING"]); minutes(d, 5); d._refreshElapsed()
  [d.attrs.printElapsed == "0:35", d.attrs.printElapsed]
}

test("elapsed: not sent with every message, only once a minute") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"])
  59.times { d.advance(1000); send(d, [gcode_state: "RUNNING", mc_percent: 1]) }
  [named(d, "printElapsed").size() == 1, named(d, "printElapsed")]
}

// ── Active filament ──────────────────────────────────────────────────────

test("filament: tray_now 254 is the external spool, not an AMS tray") {
  def d = fresh()
  send(d, fullReport("RUNNING", 254))
  [d.attrs.filamentType == "PETG-CF" && d.attrs.filamentColor == "#444444", [d.attrs.filamentType, d.attrs.filamentColor]]
}

test("filament: an AMS slot is found by unit and slot (unit 1, slot 2 = 6)") {
  def d = fresh()
  send(d, fullReport("RUNNING", 6))
  [d.attrs.filamentType == "ASA" && d.attrs.filamentColor == "#333333", [d.attrs.filamentType, d.attrs.filamentColor]]
}

test("filament: tray_now 255 (nothing loaded) leaves the filament as it was") {
  def d = fresh()
  send(d, fullReport("RUNNING", 1))
  send(d, [ams: [tray_now: "255"]])
  [d.attrs.filamentType == "PETG", d.attrs.filamentType]
}

test("filament: a vt_tray update during an AMS print doesn't take over") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  send(d, [vt_tray: [id: "254", tray_type: "TPU", tray_color: "999999FF"]])
  [d.attrs.filamentType == "PLA" && d.attrs.filamentColor == "#FF0000", [d.attrs.filamentType, d.attrs.filamentColor]]
}

// ── AMS with partial updates (P1 and A1 series) ──────────────────────────

test("ams: a message with only tray_now keeps the summary") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  String before = d.attrs.amsSummary
  send(d, [ams: [tray_now: "2"]])
  [d.attrs.amsSummary == before && d.attrs.amsTrayNow == 2 && d.attrs.filamentType == "PLA" && d.attrs.filamentColor == "#0000FF", d.attrs]
}

test("ams: one changed tray updates only that tray") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  send(d, [ams: [ams: [[id: "0", tray: [[id: "0", remain: 79]]]]]])
  def s = d.attrs.amsSummary
  [s.contains("A0T0:PLA/FF0000/79%") && s.contains("A1T2:ASA/333333/30%") && s.split(", ").size() == 7, s]
}

test("ams: an AMS message without tray_now keeps the active tray") {
  def d = fresh()
  send(d, fullReport("RUNNING", 5))
  send(d, [ams: [ams: [[id: "0", tray: [[id: "1", remain: 49]]]]]])
  [d.attrs.amsTrayNow == 5, d.attrs.amsTrayNow]
}

test("ams: a slot reported with nothing but its id is empty") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  send(d, [ams: [ams: [[id: "0", tray: [[id: "3"]]]]]])
  [!d.attrs.amsSummary.contains("A0T3"), d.attrs.amsSummary]
}

test("ams: a unit missing from ams_exist_bits is dropped") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  send(d, [ams: [ams_exist_bits: "1", ams: [[id: "0", tray: []]]]])
  [!d.attrs.amsSummary.contains("A1T"), d.attrs.amsSummary]
}

test("ams: 0% remaining is reported as 0%, not unknown") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  [d.attrs.amsSummary.contains("A1T1:PLA/222222/0%"), d.attrs.amsSummary]
}

// ── Event and database volume ────────────────────────────────────────────

test("lastUpdate: once a minute at one message a second") {
  def d = fresh()
  120.times { d.advance(1000); send(d, fullReport("RUNNING", 0)) }
  [named(d, "lastUpdate").size() == 2, named(d, "lastUpdate").size()]
}

test("lastUpdate: a state change is stamped straight away") {
  def d = fresh()
  send(d, [gcode_state: "RUNNING"]); d.advance(5000)
  send(d, [gcode_state: "PAUSE"])
  [named(d, "lastUpdate").size() == 2, named(d, "lastUpdate")]
}

test("state: a message doesn't write the time it arrived to state") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  [!d.state.containsKey("lastMessageTime"), d.state.keySet()]
}

test("state: an unchanged AMS report doesn't rewrite state.amsCache") {
  def d = fresh()
  send(d, fullReport("RUNNING", 0))
  def first = d.state.amsCache
  send(d, fullReport("RUNNING", 0))
  [d.state.amsCache.is(first), "rewritten"]
}

// An idle printer repeats its full report too; the repeats should cost nothing
def idleReport = { Map more = [:] ->
  fullReport("IDLE", 0, [mc_percent: 0, nozzle_temper: 24.6, bed_temper: 23.9, wifi_signal: "-52dBm",
                         spd_lvl: 2, spd_mag: 100, mc_print_error_code: "0"] + more)
}

test("events: a repeated report sends nothing new") {
  def d = fresh()
  send(d, idleReport()); int after = d.events.size()
  10.times { d.advance(1000); send(d, idleReport()) }
  [d.events.size() == after, d.events.drop(after)]
}

test("events: a changed value is still sent") {
  def d = fresh()
  send(d, idleReport()); d.advance(1000)
  send(d, idleReport(bed_temper: 31.2))
  [named(d, "bedTemp") == [24L, 31L], named(d, "bedTemp")]
}

test("events: an idle printer after a print still resets the elapsed time") {
  def d = fresh()
  send(d, [gcode_state: "IDLE"])
  send(d, [gcode_state: "RUNNING"]); minutes(d, 12)
  send(d, [gcode_state: "FINISH"])
  send(d, [gcode_state: "IDLE"])
  [d.attrs.printElapsed == "—", named(d, "printElapsed")]
}

test("state: a repeated idle report writes nothing to state") {
  def d = fresh()
  send(d, idleReport())
  def watched = gcl.loadClass("WriteLog").newInstance(d.state)
  d.state = watched
  5.times { d.advance(1000); send(d, idleReport()) }
  [watched.getWrites().isEmpty(), watched.getWrites()]
}

test("wifiSignal: a changing signal is sent at most every 5 minutes") {
  def d = fresh()
  600.times { i -> d.advance(1000); send(d, idleReport(wifi_signal: "-${50 + i % 5}dBm")) }
  [named(d, "wifiSignal").size() == 2, named(d, "wifiSignal")]
}

// ── Full-status requests ─────────────────────────────────────────────────

test("refresh: full status is requested no more than every 300 s") {
  [fresh(refreshInterval: 30)._refreshSeconds() == 300 && fresh([:])._refreshSeconds() == 300, ""]
}

test("refresh: asks for pushall on the request topic") {
  def d = fresh()
  d.refresh()
  [d.published.size() == 1 && d.published[0].contains('"command":"pushall"'), d.published]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
