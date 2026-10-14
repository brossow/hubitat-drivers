// Off-hub tests for the Bambu Lab Printer app. Run with tests/run.sh.
//
// The app file is parsed (never run, so definition{}, preferences{} and mappings{}
// are skipped) on top of AppStub, a minimal stand-in for the Hubitat app sandbox.
// The hub's address is a field the tests change, standing in for the hub moving to
// a new IP. Addresses are documentation placeholders (RFC 5737), not a real network.

import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''
abstract class AppStub extends Script {
  Map settingsMap = [:]
  Map state = [:]
  List subscriptions = []
  List schedules = []
  List runIns = []
  String hubUrl = "http://192.0.2.10/apps/api"
  def log = [warn: { m -> }, info: { m -> }, debug: { m -> }, error: { m -> }]
  def app = [id: 767L]
  def location = [name: "Home"]
  Map getSettings() { settingsMap }
  def propertyMissing(String n) { settingsMap[n] }
  String getLocalApiServerUrl() { hubUrl }
  // A method, not a.hubUrl = …: assigning a property on a Script writes to its binding
  void moveHub(String url) { hubUrl = url }
  void useState(Map m) { state = m }
  void createAccessToken() { state.accessToken = "TOKEN-PLACEHOLDER" }
  void subscribe(def source, String attr, String handler) { subscriptions << [source: source, attr: attr, handler: handler] }
  void unsubscribe() { subscriptions.clear() }
  void schedule(String cron, String handler) { schedules << [cron: cron, handler: handler] }
  void runIn(def s, String handler) { runIns << [seconds: s, handler: handler] }
  void unschedule(String h = null) { schedules.clear(); runIns.clear() }
}''')
// A printer device that records what it's sent and keeps its attributes the way the
// hub does: an event carrying the value already there changes nothing
gcl.parseClass('''
class FakePrinter {
  Map attrs = [printerState: "IDLE", filamentType: "PLA"]
  List pushes = []
  String displayName = "Test printer"
  def currentValue(String a) { attrs[a] }
  void setTileStubs(String tile, String amsTile) {
    pushes << [tile, amsTile]
    attrs.html = tile; attrs.htmlAms = amsTile
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
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'AppStub'))
def appFile = new File(args[0])
String filter = args.length > 1 ? args[1] : ""

// An installed app with OAuth enabled and a printer selected
def fresh = { Map settings = [:] ->
  def a = shell.parse(appFile)
  def printer = gcl.loadClass("FakePrinter").newInstance()
  a.settingsMap.putAll([printerDevice: printer] + settings)
  a.createAccessToken()
  a.installed()
  a
}
def printer = { a -> a.settingsMap.printerDevice }
// Runs whatever runIn() queued, as the hub would once the delay passes
def fireRunIns = { a -> def due = a.runIns.collect(); a.runIns.clear(); due.each { a."${it.handler}"() } }

int failures = 0, ran = 0
def test = { String name, Closure body ->
  if (!name.toLowerCase().contains(filter.toLowerCase())) return
  ran++
  boolean ok; String detail = ""
  try { def r = body(); ok = r[0]; detail = r[1].toString() }
  catch (Throwable e) { ok = false; detail = "threw ${e.class.simpleName}: ${e.message}" }
  if (!ok) failures++
  println "${ok ? 'PASS' : 'FAIL'}  app: $name${!ok && detail ? '  ' + detail : ''}"
}

// ── Tile stubs follow the hub's address ──────────────────────────────────

test("stubs: install points the tiles at the hub") {
  def a = fresh()
  def html = printer(a).attrs.html
  [html.contains("src='http://192.0.2.10/apps/api/767/tile?access_token=TOKEN-PLACEHOLDER'") &&
   printer(a).attrs.htmlAms.contains("/767/ams-tile?"), html]
}

test("stubs: a hub restart re-points the tiles at the new address") {
  def a = fresh()
  def onStart = a.subscriptions.find { it.source.is(a.location) && it.attr == "systemStart" }
  if (!onStart) return [false, "no systemStart subscription: ${a.subscriptions*.attr}"]
  a.moveHub("http://192.0.2.20/apps/api")
  a."${onStart.handler}"([name: "systemStart"])
  fireRunIns(a)
  [printer(a).attrs.html.contains("http://192.0.2.20/apps/api/767/tile?") &&
   printer(a).attrs.htmlAms.contains("http://192.0.2.20/apps/api/767/ams-tile?"), printer(a).attrs.html]
}

// A DHCP lease can move the hub without a restart
test("stubs: the scheduled check re-points the tiles without a restart") {
  def a = fresh()
  if (!a.schedules) return [false, "nothing scheduled"]
  a.moveHub("http://192.0.2.20/apps/api")
  a.schedules.each { a."${it.handler}"() }
  [printer(a).attrs.html.contains("http://192.0.2.20/apps/api/767/tile?"), printer(a).attrs.html]
}

test("stubs: the scheduled check runs at least daily") {
  def a = fresh()
  // Hubitat cron: sec min hour day-of-month month day-of-week; a fixed day field would skip days
  def daily = a.schedules.findAll { s -> def f = s.cron.split(/\s+/); f.size() >= 6 && f[3] in ["*", "?"] && f[5] in ["*", "?"] }
  [daily.size() == 1, a.schedules]
}

test("stubs: the scheduled check with nothing changed writes nothing") {
  def a = fresh()
  if (!a.schedules) return [false, "nothing scheduled"]
  int pushes = printer(a).pushes.size()
  def watched = gcl.loadClass("WriteLog").newInstance(a.state)
  a.useState(watched)
  a.schedules.each { a."${it.handler}"() }
  [printer(a).pushes.size() == pushes && watched.getWrites().isEmpty(),
   "pushes ${printer(a).pushes.size() - pushes}, writes ${watched.getWrites()}"]
}

// e.g. the device was re-created, or its driver code re-saved and the attribute cleared
test("stubs: the scheduled check restores a device that lost its stubs") {
  def a = fresh()
  if (!a.schedules) return [false, "nothing scheduled"]
  printer(a).attrs.remove("html")
  a.schedules.each { a."${it.handler}"() }
  [printer(a).attrs.html?.contains("/767/tile?"), printer(a).attrs.html]
}

test("stubs: Done still re-pushes, and keeps the checks in place") {
  def a = fresh()
  a.moveHub("http://192.0.2.20/apps/api")
  a.updated()
  [printer(a).attrs.html.contains("http://192.0.2.20/") && a.schedules.size() == 1 &&
   a.subscriptions.count { it.attr == "systemStart" } == 1, [a.schedules, a.subscriptions*.attr]]
}

test("stubs: without OAuth nothing is pushed and nothing fails") {
  def a = shell.parse(appFile)
  def p = gcl.loadClass("FakePrinter").newInstance()
  a.settingsMap.printerDevice = p
  a.installed()
  a.schedules.each { a."${it.handler}"() }
  [p.pushes.isEmpty(), p.pushes]
}

test("stubs: no printer selected doesn't fail") {
  def a = shell.parse(appFile)
  a.createAccessToken()
  a.installed()
  a.schedules.each { a."${it.handler}"() }
  [true, ""]
}

println(ran == 0 ? "no app tests match '$filter'" : failures ? "$failures of $ran app tests FAILED" : "all $ran app tests passed")
System.exit(failures ? 1 : ran == 0 ? 3 : 0)   // 3: nothing matched the filter
