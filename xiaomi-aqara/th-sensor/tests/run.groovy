// Off-hub tests for the Xiaomi/Aqara T&H driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} is skipped) on top of HubStub,
// a minimal stand-in for the Hubitat sandbox that records events, warnings and
// setting updates. Each test builds a fresh driver instance with the preferences
// it needs, calls driver methods directly, and checks what came out.

import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''package hubitat.helper
class HexUtils { static String integerToHexString(int v, int b) { String.format("%0${b*2}X", v) } }''')
gcl.parseClass('''package hubitat.device
enum Protocol { ZIGBEE, ZWAVE }
class HubAction { HubAction(String c, Protocol p) {} }
class HubMultiAction { void add(HubAction a) {} }''')
gcl.parseClass('''
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map attrs = [:]
  Map state = [:]
  List events = []
  List warns = []
  Map updatedSettings = [:]
  def log = [warn: { m -> warns << m.toString() }, info: { m -> }, debug: { m -> }]
  def location = [temperatureScale: "C"]
  def device = null
  // Preferences resolve like undeclared properties on the hub: missing ones are null
  def propertyMissing(String n) { settingsMap[n] }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void unschedule(String s = null) {}
  void schedule(String c, String h) {}
  BigDecimal celsiusToFahrenheit(BigDecimal c) { c * 9 / 5 + 32 }
  void setup() {
    def self = this
    device = [currentValue: { String a -> self.attrs[a] },
              updateSetting: { String n, v -> self.updatedSettings[n] = v }]
  }
}''')
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
String filter = args.length > 1 ? args[1] : ""

def fresh = { Map settings ->
  def d = shell.parse(driverFile)
  d.setup(); d.settingsMap.putAll(settings); d
}
int failures = 0, ran = 0
def test = { String name, Closure body ->
  if (!name.toLowerCase().contains(filter.toLowerCase())) return
  ran++
  String detail = ""
  boolean ok
  try { def r = body(); ok = r instanceof List ? r[0] : r; detail = r instanceof List ? r[1].toString() : "" }
  catch (Throwable e) { ok = false; detail = "threw ${e.class.simpleName}: ${e.message}" }
  if (!ok) failures++
  println "${ok ? 'PASS' : 'FAIL'}  $name${!ok && detail ? '  ' + detail : ''}"
}
def eventsNamed = { d, String n -> d.events.findAll { it.name == n }*.value }

// ── Pressure ─────────────────────────────────────────────────────────────
// The threshold is 1 hPa in every displayed unit. It used to be a fixed 0.1 in
// the displayed unit: about 100 hPa in atm (pressure froze) and 3.4 hPa in inHg.

test("pressure: atm updates after the first reading") {
  def d = fresh(pressureUnitConversion: "atm")
  [1013, 1003].each { d.sendPressureEvent([attrId: "0000", valueParsed: it]) }
  [eventsNamed(d, 'pressure').size() == 2, eventsNamed(d, 'pressure')]
}

test("pressure: kPa skips 0.5 hPa and reports 1 hPa, as before") {
  def d = fresh(pressureUnitConversion: "kPa")
  [1013, 1013.5, 1014].each { d.sendPressureEvent([attrId: "0000", valueParsed: it]) }
  [eventsNamed(d, 'pressure') == [101.30, 101.40], eventsNamed(d, 'pressure')]
}

// Displayed values are rounded, so a 1 hPa change can show up slightly smaller
// than 1 hPa. Try a 1 hPa step from every start between 950 and 1050 hPa.
["kPa", "mbar", "inHg", "mmHg", "atm"].each { unit ->
  test("pressure: every 1 hPa step is reported in $unit") {
    def missed = (950..1050).findAll { start ->
      def d = fresh(pressureUnitConversion: unit)
      [start, start + 1].each { d.sendPressureEvent([attrId: "0000", valueParsed: it]) }
      eventsNamed(d, 'pressure').size() != 2
    }
    [missed.isEmpty(), "missed from ${missed.take(5)}"]
  }
}

test("pressure: readings outside 500-1100 hPa are dropped with a warning") {
  def d = fresh([:])
  d.sendPressureEvent([attrId: "0000", valueParsed: 1200])
  [eventsNamed(d, 'pressure').isEmpty() && d.warns.any { it.contains("Pressure") }, d.warns]
}

// ── Temperature and humidity thresholds ──────────────────────────────────
// The preferences say "at least this many", so a change equal to the
// threshold must be reported.

test("temperature: a change equal to the threshold is reported") {
  def d = fresh(tempVariance: 0.2, tempUnitDisplayed: "1")
  [2000, 2020].each { d.sendTemperatureEvent(it) }
  [eventsNamed(d, 'temperature') == [20.0, 20.2], eventsNamed(d, 'temperature')]
}

test("temperature: a change below the threshold is skipped") {
  def d = fresh(tempVariance: 0.2, tempUnitDisplayed: "1")
  [2000, 2010].each { d.sendTemperatureEvent(it) }
  [eventsNamed(d, 'temperature') == [20.0], eventsNamed(d, 'temperature')]
}

test("humidity: a change equal to the threshold is reported") {
  def d = fresh(humidityVariance: 0.5)
  [4000, 4050].each { d.sendHumidityEvent(it) }
  [eventsNamed(d, 'humidity') == [40.0, 40.5], eventsNamed(d, 'humidity')]
}

// ── Humidity range ───────────────────────────────────────────────────────

test("humidity: readings above 100% are dropped with a warning") {
  def d = fresh([:])
  d.sendHumidityEvent(10500)
  [eventsNamed(d, 'humidity').isEmpty() && d.warns.any { it.contains("humidity") }, d.warns]
}

test("humidity: readings below 0% are dropped with a warning") {
  def d = fresh([:])
  d.sendHumidityEvent(-100)
  [eventsNamed(d, 'humidity').isEmpty() && d.warns.any { it.contains("humidity") }, d.warns]
}

test("humidity: an offset cannot push a reading past 100%") {
  def d = fresh(humidityOffset: 5)
  d.sendHumidityEvent(9800)
  [d.attrs.humidity == 100, d.attrs.humidity]
}

test("humidity: an offset cannot push a reading below 0%") {
  def d = fresh(humidityOffset: -5)
  d.sendHumidityEvent(200)
  [d.attrs.humidity == 0, d.attrs.humidity]
}

// ── Presence ─────────────────────────────────────────────────────────────
// The device is a PresenceSensor, so "not present" looks like a departure.

test("presence: turning it off reports present, not a departure") {
  def d = fresh(presenceEnable: false)
  d.configurePresence()
  [d.attrs.presence == "present", d.attrs.presence]
}

// ── Housekeeping ─────────────────────────────────────────────────────────

test("logsOff turns debug logging off") {
  def d = fresh(debugLogging: true)
  d.logsOff()
  [d.updatedSettings.debugLogging == [value: "false", type: "bool"], d.updatedSettings]
}

test("recovery waits 90 minutes between events by default") {
  [fresh([:]).maxEventMinutes() == 90, ""]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
