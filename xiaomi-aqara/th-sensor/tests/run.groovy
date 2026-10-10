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
  List scheduled = []
  def log = [warn: { m -> warns << m.toString() }, info: { m -> }, debug: { m -> }]
  def location = [temperatureScale: "C"]
  def device = null
  // Preferences resolve like undeclared properties on the hub: missing ones are null
  def propertyMissing(String n) { settingsMap[n] }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void unschedule(String h = null) { if (h == null) scheduled.clear() else scheduled.remove(h) }
  void schedule(String c, String h) { scheduled << h }
  long now() { System.currentTimeMillis() }
  BigDecimal celsiusToFahrenheit(BigDecimal c) { c * 9 / 5 + 32 }
  void setup() {
    def self = this
    // updateSetting is recorded but not applied to settingsMap, as on the hub a
    // setting written during a run can't be relied on to read back in that run
    device = [currentValue: { String a -> self.attrs[a] },
              updateSetting: { String n, v -> self.updatedSettings[n] = v },
              deleteCurrentState: { String a -> self.attrs.remove(a) }]
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

// ── Health status (replaced the PresenceSensor capability in 2.0.0) ──────

def hoursAgo = { int h -> new Date(System.currentTimeMillis() - h * 3600000L).format('yyyy-MM-dd HH:mm:ss') }

test("health: a checkin within 3 hours is online") {
  def d = fresh([:])
  d.attrs.lastCheckin = hoursAgo(1)
  d.configureHealthCheck()
  [d.attrs.healthStatus == "online" && d.scheduled.contains('checkHealth'), d.attrs]
}

test("health: no checkin for over 3 hours is offline and counted") {
  def d = fresh([:])
  d.attrs.lastCheckin = hoursAgo(4)
  d.checkHealth()
  d.checkHealth()
  [d.attrs.healthStatus == "offline" && d.attrs.offlineCounter == 2 && d.warns.size() == 2, d.attrs]
}

test("health: coming back online counts a restore and resets offlineCounter") {
  def d = fresh([:])
  d.attrs.lastCheckin = hoursAgo(4)
  d.checkHealth()
  d.attrs.lastCheckin = null
  d.sendCheckinEvent()
  [d.attrs.healthStatus == "online" && d.attrs.restoredCounter == 1 && d.attrs.offlineCounter == 0, d.attrs]
}

test("health: turned off, nothing reports offline, even from recovery mode") {
  def d = fresh(healthCheckEnable: false)
  d.attrs.lastCheckin = hoursAgo(4)
  d.attrs.healthStatus = "online"
  d.configureHealthCheck()
  d.checkHealth()
  [!d.attrs.containsKey('healthStatus') && !d.scheduled.contains('checkHealth'), d.attrs]
}

test("health: no presence attribute or capability remains") {
  def d = fresh([:])
  d.attrs.lastCheckin = hoursAgo(1)
  d.configureHealthCheck(); d.checkHealth(); d.sendCheckinEvent()
  def src = driverFile.text
  [!d.events.any { it.name == 'presence' } && !src.contains('capability "PresenceSensor"'), d.events*.name.unique()]
}

// ── Upgrading from 1.x ───────────────────────────────────────────────────

test("upgrade: presence turned off in 1.x stays off") {
  def d = fresh(presenceEnable: false)
  d.migrateFromPresence()
  d.attrs.lastCheckin = hoursAgo(4)
  d.configureHealthCheck()
  [d.updatedSettings.healthCheckEnable == [value: "false", type: "bool"] && !d.healthCheckOn() && !d.attrs.containsKey('healthStatus'), d.updatedSettings]
}

test("upgrade: presence warnings turned off in 1.x stay off") {
  def d = fresh(presenceWarningEnable: false)
  d.migrateFromPresence()
  d.attrs.lastCheckin = hoursAgo(4)
  d.checkHealth()
  [d.updatedSettings.healthWarningEnable == [value: "false", type: "bool"] && d.warns.isEmpty(), d.warns]
}

test("upgrade: a saved 2.0 setting wins over the old one") {
  def d = fresh(presenceEnable: false, healthCheckEnable: true)
  d.migrateFromPresence()
  [d.healthCheckOn() && !d.updatedSettings.containsKey('healthCheckEnable'), d.updatedSettings]
}

test("upgrade: old presence attributes are removed, the counter carried over") {
  def d = fresh([:])
  d.attrs.presence = "present"; d.attrs.notPresentCounter = 3
  d.migrateFromPresence()
  [!d.attrs.containsKey('presence') && !d.attrs.containsKey('notPresentCounter') && d.attrs.offlineCounter == 3, d.attrs]
}

test("upgrade: the 1.x checkPresence schedule is removed") {
  def d = fresh([:])
  d.scheduled << 'checkPresence'
  d.configureHealthCheck()
  [!d.scheduled.contains('checkPresence'), d.scheduled]
}

// ── Housekeeping ─────────────────────────────────────────────────────────

test("logsOff turns debug logging off") {
  def d = fresh(debugLogging: true)
  d.logsOff()
  [d.updatedSettings.debugLogging == [value: "false", type: "bool"], d.updatedSettings]
}

test("version: header, getDriverVersion and packageManifest.json agree") {
  def src = driverFile.text
  def header = (src =~ /Version: v([\d.]+)/)[0][1]
  def code = (src =~ /String version = "v([\d.]+)"/)[0][1]
  def manifest = new groovy.json.JsonSlurper().parse(new File(driverFile.parentFile, "packageManifest.json")).version
  [header == code && code == manifest, "header $header, code $code, manifest $manifest"]
}

test("recovery waits 90 minutes between events by default") {
  [fresh([:]).maxEventMinutes() == 90, ""]
}

// 2.0.0 broke the warnings in these paths (ifhealthWarningsOn()), so the first
// recoveryEvent after a sensor came back threw, and the catch turned Recovery
// Mode off in Preferences, blaming a "Platform bug".
def withoutRadio = { d -> d.metaClass.sendHubCommand = { cmd -> }; d.metaClass.zigbee = [readAttribute: { Object[] a -> [] }]; d }

test("recovery: a sensor coming back ends recovery and leaves it enabled") {
  def d = withoutRadio(fresh(recoveryMode: "Normal"))
  d.attrs.lastCheckin = hoursAgo(0)
  d.scheduled << 'recoveryEvent'
  d.recoveryEvent()
  [!d.scheduled.contains('recoveryEvent') && !d.updatedSettings.containsKey('recoveryMode') && d.warns.any { it.contains("DEACTIVATED") }, d.warns]
}

test("recovery: forced recovery mode starts and stops without disabling it") {
  def d = withoutRadio(fresh(recoveryMode: "Normal"))
  d.metaClass.runIn = { Object[] a -> }
  d.attrs.lastCheckin = hoursAgo(2)
  d.forceRecoveryMode(30)
  d.disableForcedRecoveryMode()
  [!d.updatedSettings.containsKey('recoveryMode') && d.warns.count { it.contains("Forced recovery mode") } == 2, d.warns]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
