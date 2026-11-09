// Off-hub tests for the Yale Assure Lock 2 driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} is skipped) on top of HubStub,
// a minimal stand-in for the Hubitat sandbox. Hubitat's Z-Wave command classes are
// stubbed with just the fields the driver reads, and the zwave builder records each
// command the driver sends. The clock is controlled, so timeouts can be stepped
// through without waiting.

import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
[
'''package hubitat.device
enum Protocol { ZIGBEE, ZWAVE }
class HubAction { def cmd; HubAction(cmd, Protocol p) { this.cmd = cmd } }
class HubMultiAction { List cmds; HubMultiAction(List c, Protocol p) { cmds = c } }''',
'''package hubitat.zwave
abstract class Command { String format() { getClass().simpleName } }''',
'''package hubitat.zwave.commands.doorlockv1
class DoorLockOperationReport extends hubitat.zwave.Command { Integer doorLockMode }
class DoorLockOperationSet extends hubitat.zwave.Command {
  static final Integer DOOR_LOCK_MODE_DOOR_SECURED = 0xFF
  static final Integer DOOR_LOCK_MODE_DOOR_UNSECURED = 0x00 }''',
'''package hubitat.zwave.commands.usercodev1
class UserCodeReport extends hubitat.zwave.Command {
  static final Integer USER_ID_STATUS_AVAILABLE = 0
  static final Integer USER_ID_STATUS_OCCUPIED = 1
  Integer userIdentifier; Integer userIdStatus; String userCode }
class UsersNumberReport extends hubitat.zwave.Command { Integer supportedUsers }''',
'''package hubitat.zwave.commands.notificationv3
class NotificationReport extends hubitat.zwave.Command {
  Integer notificationType; Integer event; List eventParameter = []; Integer v1AlarmType = 0 }''',
'''package hubitat.zwave.commands.supervisionv1
class SupervisionGet extends hubitat.zwave.Command {
  Integer sessionID = 1; def inner
  def encapsulatedCommand(Map versions) { if (versions == null) throw new IllegalArgumentException("no command class versions"); inner } }''',
'''package hubitat.zwave.commands.securityv1
class SecurityMessageEncapsulation extends hubitat.zwave.Command { def inner; def encapsulatedCommand(Map v) { inner } }''',
// Only the 1.07 driver refers to this; it lets that copy load for comparison runs.
'''package hubitat.zwave.commands.alarmv2
class AlarmReport extends hubitat.zwave.Command { Integer alarmType; Integer alarmLevel }''',
'''package hubitat.zwave.commands.batteryv1
class BatteryReport extends hubitat.zwave.Command { Integer batteryLevel }''',
// A command the driver builds, e.g. zwave.userCodeV1.userCodeGet(userIdentifier: 3)
'''class Sent { String cc; String name; Map args
  String format() { "$cc.$name" }
  String toString() { "$cc.$name$args" } }
class CcBuilder { String cc
  def methodMissing(String n, a) { new Sent(cc: cc, name: n, args: (a && a[0] instanceof Map) ? a[0] : [:]) } }
class ZwaveStub {
  Closure parser = { String d, Map v -> null }
  def parse(String d, Map v) { parser(d, v) }
  def propertyMissing(String cc) { new CcBuilder(cc: cc) } }''',
'''
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map attrs = [:]
  Map data = [deviceId: "17873"]
  Map state = [:]
  Map atomicState = [:]
  List events = []
  List logs = []           // [level, message]
  List sent = []           // Sent commands, in order
  Map updatedSettings = [:]
  Map runIns = [:]
  long clock = 1_700_000_000_000L
  def zwave = new ZwaveStub()
  def zwaveHubNodeId = 1
  def log = [trace: { m -> logs << ["trace", m.toString()] }, debug: { m -> logs << ["debug", m.toString()] },
             info: { m -> logs << ["info", m.toString()] }, warn: { m -> logs << ["warn", m.toString()] }]
  def device = null
  def propertyMissing(String n) { settingsMap[n] }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  long now() { clock }
  // Script properties assigned from outside land in the binding, so move the clock through a method.
  void advance(long ms) { clock += ms }
  void runIn(Number s, String h, Map o = [:]) { runIns[h] = s }
  void runInMillis(Number ms, String h, Map o = [:]) { runIns[h] = ms / 1000 }
  void unschedule(String h = null) { if (h == null) runIns.clear() else runIns.remove(h) }
  def zwaveSecureEncap(c) { c }
  void sendHubCommand(a) { if (a.hasProperty('cmds')) sent.addAll(a.cmds) else sent << a.cmd }
  // Reversible stand-in for the hub's encryption; decrypt returns null for anything it didn't encrypt, as the hub does.
  String encrypt(String s) { s == null ? null : "ENC:" + s.bytes.encodeBase64().toString() }
  String decrypt(String s) { s?.startsWith("ENC:") ? new String(s.substring(4).decodeBase64()) : null }
  def parseJson(String s) { new groovy.json.JsonSlurper().parseText(s) }
  void setup() {
    def self = this
    device = [id: 85, displayName: "Test Lock",
              currentValue: { String a, boolean skipCache = false -> self.attrs[a] },
              getDataValue: { String k -> self.data[k] },
              updateSetting: { String n, v -> self.updatedSettings[n] = v }]
  }
}'''
].each { gcl.parseClass(it) }

def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
String filter = args.length > 1 ? args[1] : ""

def fresh = { Map settings = [:] ->
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

def cl = gcl.&loadClass
def NR = { Map m -> cl('hubitat.zwave.commands.notificationv3.NotificationReport').newInstance([notificationType: 6] + m) }
def UCR = { int slot, String code -> cl('hubitat.zwave.commands.usercodev1.UserCodeReport').newInstance(userIdentifier: slot, userIdStatus: code ? 1 : 0, userCode: code) }
def DLR = { int mode -> cl('hubitat.zwave.commands.doorlockv1.DoorLockOperationReport').newInstance(doorLockMode: mode) }
def named = { d, String n -> d.events.findAll { it.name == n } }
def values = { d, String n -> named(d, n)*.value }
def codesOf = { d ->
  def v = d.attrs.lockCodes
  if (!v) return [:]
  new groovy.json.JsonSlurper().parseText(v.startsWith("{") ? v : d.decrypt(v))
}
// Store codes the way the 1.07 driver left them: plain JSON.
def withCodes = { d, Map codes -> d.attrs.lockCodes = groovy.json.JsonOutput.toJson(codes); d }
def alex = ["1": [name: "Alex", code: "1111", status: "active"]]
def codeGets = { d -> d.sent.findAll { it.name == 'userCodeGet' }*.args*.userIdentifier }

// ── Unlocks name the right user ──────────────────────────────────────────
// On Z-Wave JS the slot arrives in eventParameter[0]. 1.07 read [2], so every keypad
// unlock on Z-Wave JS said "unlocked by User null".

test("unlock: Z-Wave JS keypad unlock names the user (slot in eventParameter[0])") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: [1]))
  [d.attrs.lastCodeName == "Alex" && named(d, 'lock')[0].descriptionText.contains("Alex"), [d.attrs.lastCodeName, named(d, 'lock')]]
}

test("unlock: old-stack keypad unlock names the user (embedded User Code Report)") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: [0x63, 0x03, 1, 1, 0x31]))
  [d.attrs.lastCodeName == "Alex", d.attrs.lastCodeName]
}

test("unlock: the Rule Machine data map is keyed by the real slot") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: [1]))
  [named(d, 'lock')[0].data == ["1": [name: "Alex"]], named(d, 'lock')[0].data]
}

test("unlock: a slot with no parameters never reports 'User null'") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: []))
  [!d.events.any { "${it.value} ${it.descriptionText}".contains("null") }, d.events]
}

test("unlock: a slot Hubitat doesn't know is reported as an unknown code") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: [3]))
  [d.attrs.lastCodeName == "unknown code 3", d.attrs.lastCodeName]
}

test("unlock: a fingerprint unlock keeps the matched user's name") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(NR(event: 6, eventParameter: [0x63, 0x03, 1, 1, 0x31], v1AlarmType: 0x91))
  [d.attrs.lastCodeName == "Alex" && named(d, 'lock')[0].descriptionText.contains("fingerprint"), named(d, 'lock')[0].descriptionText]
}

test("unlock: every keypad unlock fires, even when already unlocked") {
  def d = withCodes(fresh(), alex)
  d.attrs.lock = "unlocked"
  d.zwaveEvent(NR(event: 6, eventParameter: [1]))
  [named(d, 'lock')[0].isStateChange == true, named(d, 'lock')[0]]
}

// ── Repeated events ──────────────────────────────────────────────────────
// Z-Wave JS re-sends the door state 2-3 times per open/close, and 1.07 forced every
// event through as a state change, so rules on the contact ran several times.

test("contact: repeated door-state reports are not forced as state changes") {
  def d = fresh()
  3.times { d.zwaveEvent(NR(event: 0x16)) }
  [named(d, 'contact').every { !it.isStateChange } && values(d, 'contact') == ["open"] * 3, named(d, 'contact')]
}

test("contact: open and closed map to the right values") {
  def d = fresh()
  d.zwaveEvent(NR(event: 0x16)); d.zwaveEvent(NR(event: 0x17))
  [values(d, 'contact') == ["open", "closed"], values(d, 'contact')]
}

test("lock: manual/digital/auto lock events are not forced as state changes") {
  def d = fresh()
  [1, 2, 3, 4, 9].each { d.zwaveEvent(NR(event: it)) }
  [named(d, 'lock').every { !it.isStateChange }, named(d, 'lock')]
}

test("battery: reports are not forced as state changes") {
  def d = fresh()
  d.zwaveEvent(cl('hubitat.zwave.commands.batteryv1.BatteryReport').newInstance(batteryLevel: 92))
  [named(d, 'battery').every { !it.isStateChange }, named(d, 'battery')]
}

// Z-Wave JS turns the "Opening state" variable (0/1) into a NotificationReport with
// event 1, which reads as Manual Lock. Simulate that translation in the stub.
test("door open on Z-Wave JS does not report a phantom lock") {
  def d = fresh()
  d.attrs.lock = "unlocked"
  d.zwave.parser = { String desc, Map v -> NR(event: 1) }
  d.parse('{"cc":113,"values":[{"propertyKey":"Opening state","newValue":1}]}')
  [!d.events.any { it.name == 'lock' }, d.events]
}

// ── Lock state ───────────────────────────────────────────────────────────

test("lock: doorLockMode 0xFE (bolt position unknown) is 'unknown', not unlocked") {
  def d = fresh()
  d.zwaveEvent(DLR(0xFE)); d.releaseLockReport()
  [values(d, 'lock') == ["unknown"], values(d, 'lock')]
}

test("lock: doorLockMode 0xFF locked, 0x00 unlocked") {
  def d = fresh()
  d.zwaveEvent(DLR(0xFF)); d.releaseLockReport(); d.zwaveEvent(DLR(0x00)); d.releaseLockReport()
  [values(d, 'lock') == ["locked", "unlocked"], values(d, 'lock')]
}

// Hub test, Z-Wave JS: the lock-state report arrived 0.6 s before the keypad notification, so a
// keypad unlock fired a bare "was unlocked" and then "unlocked by Test via keypad".
test("lock: a keypad unlock right after the lock-state report fires once, with the user") {
  def d = withCodes(fresh(), alex)
  d.attrs.lock = "locked"
  d.zwaveEvent(DLR(0x00))
  boolean held = named(d, 'lock').isEmpty() && d.runIns.releaseLockReport != null
  d.zwaveEvent(NR(event: 6, eventParameter: [1]))
  d.releaseLockReport()
  def locks = named(d, 'lock')
  [held && locks.size() == 1 && locks[0].descriptionText.contains("Alex"), locks*.descriptionText]
}

test("lock: a lock-state report with no notification is still reported") {
  def d = fresh()
  d.attrs.lock = "locked"
  d.zwaveEvent(DLR(0x00)); d.releaseLockReport()
  [values(d, 'lock') == ["unlocked"], values(d, 'lock')]
}

test("lock: a lock-state report that changes nothing is not held") {
  def d = fresh()
  d.attrs.lock = "locked"
  d.zwaveEvent(DLR(0xFF))
  [d.runIns.releaseLockReport == null, d.runIns]
}

test("lock: 'not fully locked' is reported as that, not as a jam") {
  def d = fresh()
  d.zwaveEvent(NR(event: 7))
  [named(d, 'lock')[0].value == "unknown" && named(d, 'lock')[0].descriptionText.contains("did not fully lock"), named(d, 'lock')]
}

// ── Lock codes: confirmation ─────────────────────────────────────────────
// 1.07 wrote the code into lockCodes before the lock answered, so the read-back saw
// no change and never sent codeChanged, and a rejected code still showed as active.

test("setCode: nothing is recorded until the lock confirms") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  [codesOf(d).isEmpty() && !d.events.any { it.name == 'codeChanged' }, codesOf(d)]
}

test("setCode: a confirming read-back records the code and sends codeChanged 'added'") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  d.zwaveEvent(UCR(2, "2222"))
  [codesOf(d)["2"] == [name: "Sam", code: "2222", status: "active"] && values(d, 'codeChanged') == ["added"], [codesOf(d), values(d, 'codeChanged')]]
}

test("setCode: Z-Wave JS 'code added' with no slot confirms the single pending code") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  d.zwaveEvent(NR(event: 0x0E, eventParameter: []))
  [codesOf(d)["2"]?.name == "Sam" && values(d, 'codeChanged') == ["added"] && !d.atomicState.pendingCodes, [codesOf(d), d.atomicState]]
}

test("setCode: a duplicate-code notification reports 'failed' and stores nothing") {
  def d = fresh()
  d.setCode(2, "1111", "Sam")
  d.zwaveEvent(NR(event: 0x0F, eventParameter: []))
  [codesOf(d).isEmpty() && values(d, 'codeChanged') == ["failed"], [codesOf(d), values(d, 'codeChanged')]]
}

test("setCode: an empty slot on read-back reports 'failed'") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  d.zwaveEvent(UCR(2, null))
  [codesOf(d).isEmpty() && values(d, 'codeChanged') == ["failed"], values(d, 'codeChanged')]
}

test("setCode: no answer is read back once, then reported as 'failed'") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  d.advance(21_000); d.checkPendingCodes()
  boolean readBack = codeGets(d) == [2] && !d.events.any { it.name == 'codeChanged' }
  d.advance(21_000); d.checkPendingCodes()
  [readBack && values(d, 'codeChanged') == ["failed"] && !d.atomicState.pendingCodes && codesOf(d).isEmpty(), [codeGets(d), values(d, 'codeChanged')]]
}

test("setCode: the ZW3 truncated read-back (first digit only) is ignored") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  d.zwaveEvent(UCR(2, "2222")); d.zwaveEvent(UCR(2, "2")); d.zwaveEvent(UCR(2, "2222"))
  [codesOf(d)["2"].code == "2222" && values(d, 'codeChanged') == ["added"], [codesOf(d), values(d, 'codeChanged')]]
}

test("setCode: two codes at once are both confirmed (Z-Wave JS, no slot in the notification)") {
  def d = fresh()
  d.setCode(2, "2222", "Sam"); d.setCode(3, "3333", "Guest")
  d.sent.clear()
  d.zwaveEvent(NR(event: 0x0E, eventParameter: []))
  def asked = codeGets(d).sort()
  d.zwaveEvent(UCR(2, "2222")); d.zwaveEvent(UCR(3, "3333"))
  [asked == [2, 3] && codesOf(d).keySet() == ["2", "3"] as Set && values(d, 'codeChanged') == ["added", "added"], [asked, codesOf(d), values(d, 'codeChanged')]]
}

test("setCode: changing the PIN in an occupied slot sends 'changed'") {
  def d = withCodes(fresh(), alex)
  d.setCode(1, "9999", "Alex")
  d.zwaveEvent(UCR(1, "9999"))
  [codesOf(d)["1"].code == "9999" && values(d, 'codeChanged') == ["changed"], [codesOf(d), values(d, 'codeChanged')]]
}

// 1.07 had LockCodes[...] (capital L) on this path, which threw.
test("read-back: a code changed at the lock in an occupied slot is recorded, no exception") {
  def d = withCodes(fresh(), alex)
  d.zwaveEvent(UCR(1, "4444"))
  [codesOf(d)["1"] == [name: "Alex", code: "4444", status: "active"], codesOf(d)]
}

test("read-back: a code found in an unknown slot is added as 'User N'") {
  def d = fresh()
  d.zwaveEvent(UCR(5, "5555"))
  [codesOf(d)["5"]?.name == "User 5" && values(d, 'codeChanged') == ["added"], codesOf(d)]
}

// ── Lock codes: deleting ─────────────────────────────────────────────────

test("deleteCode: Z-Wave JS 'code deleted' with no slot confirms the single pending delete") {
  def d = withCodes(fresh(), alex)
  d.deleteCode(1)
  d.zwaveEvent(NR(event: 0x0D, eventParameter: []))
  [codesOf(d).isEmpty() && values(d, 'codeChanged') == ["deleted"] && codeGets(d).isEmpty(), [codesOf(d), values(d, 'codeChanged')]]
}

test("deleteCode: two deletes at once are read back, then both confirmed") {
  def d = withCodes(fresh(), alex + ["2": [name: "Sam", code: "2222", status: "active"]])
  d.deleteCode(1); d.deleteCode(2)
  d.zwaveEvent(NR(event: 0x0D, eventParameter: []))
  def asked = codeGets(d).sort()
  d.zwaveEvent(UCR(1, null)); d.zwaveEvent(UCR(2, null))
  [asked == [1, 2] && codesOf(d).isEmpty() && values(d, 'codeChanged') == ["deleted", "deleted"], [asked, codesOf(d), values(d, 'codeChanged')]]
}

test("deleteCode: a slot still occupied after a delete reports 'failed' and keeps the code") {
  def d = withCodes(fresh(), alex)
  d.deleteCode(1)
  d.zwaveEvent(UCR(1, "1111"))
  [codesOf(d)["1"] && values(d, 'codeChanged') == ["failed"], [codesOf(d), values(d, 'codeChanged')]]
}

// Hub test, Z-Wave JS: "code added" and "code deleted" each arrived twice, and each fired codeChanged twice.
test("setCode: a repeated 'code added' notification sends one 'added'") {
  def d = fresh()
  d.setCode(10, "4321", "Test")
  d.zwaveEvent(NR(event: 0x0E, eventParameter: [])); d.zwaveEvent(NR(event: 0x0E, eventParameter: []))
  [values(d, 'codeChanged') == ["added"], values(d, 'codeChanged')]
}

test("deleteCode: a repeated 'code deleted' notification sends one 'deleted'") {
  def d = withCodes(fresh(), alex)
  d.deleteCode(1)
  d.zwaveEvent(NR(event: 0x0D, eventParameter: [])); d.zwaveEvent(NR(event: 0x0D, eventParameter: []))
  [values(d, 'codeChanged') == ["deleted"] && codesOf(d).isEmpty(), values(d, 'codeChanged')]
}

test("codeChanged only uses the capability's values") {
  def d = withCodes(fresh(), alex)
  d.setCode(2, "2222", "Sam"); d.zwaveEvent(UCR(2, "2222"))
  d.zwaveEvent(UCR(1, "4444"))
  d.deleteCode(2); d.zwaveEvent(NR(event: 0x0D))
  d.setCode(3, "1111"); d.zwaveEvent(NR(event: 0x0F))
  def allowed = ["added", "changed", "deleted", "failed"]
  [values(d, 'codeChanged').every { it in allowed } && values(d, 'codeChanged').size() == 4, values(d, 'codeChanged')]
}

// ── Privacy ──────────────────────────────────────────────────────────────

test("privacy: codes are stored encrypted by default") {
  def d = fresh()
  d.setCode(2, "2222", "Sam"); d.zwaveEvent(UCR(2, "2222"))
  [d.attrs.lockCodes.startsWith("ENC:") && !d.attrs.lockCodes.contains("2222"), d.attrs.lockCodes]
}

test("privacy: codes are stored as plain JSON when encryption is turned off") {
  def d = fresh(optEncrypt: false)
  d.setCode(2, "2222", "Sam"); d.zwaveEvent(UCR(2, "2222"))
  [d.attrs.lockCodes.startsWith("{"), d.attrs.lockCodes]
}

test("privacy: plain codes left by 1.07 still load, and initialize re-saves them encrypted") {
  def d = withCodes(fresh(), alex)
  d.initialize()
  [d.attrs.lockCodes.startsWith("ENC:") && codesOf(d) == alex, d.attrs.lockCodes]
}

test("privacy: a pending PIN is not kept in plain text in state") {
  def d = fresh()
  d.setCode(2, "2222", "Sam")
  [!(d.state.toString() + d.atomicState.toString()).contains("2222"), d.atomicState]
}

test("privacy: no PIN in the driver's logs, even with every log level on") {
  def d = fresh(txtEnable: true, debugEnable: true, traceEnable: false)
  d.setCode(2, "2222", "Sam"); d.zwaveEvent(UCR(2, "2222"))
  d.zwaveEvent(NR(event: 6, eventParameter: [2]))
  [!d.logs.any { it[1].contains("2222") }, d.logs.findAll { it[1].contains("2222") }]
}

test("privacy: setCode's trace line masks the PIN") {
  def d = fresh(traceEnable: true)
  d.setCode(2, "2222", "Sam")
  [d.logs.any { it[0] == "trace" && it[1].startsWith("setCode") && !it[1].contains("2222") }, d.logs.findAll { it[1].startsWith("setCode") }]
}

// ── Commands and housekeeping ────────────────────────────────────────────

test("setCodeLength does not throw") {
  def d = fresh()
  d.setCodeLength(6)
  [d.logs.any { it[0] == "warn" }, d.logs]
}

test("initialize with no deviceId data does not throw") {
  def d = fresh()
  d.data.remove("deviceId")
  d.initialize()
  [d.state.DriverVersion == d.getDriverVersion(), d.state]
}

test("initialize detects the ZW3 module and clears 1.07's state.slotId") {
  def d = fresh()
  d.state.slotId = 4
  d.initialize()
  [d.state.module == "ZW3" && !d.state.containsKey("slotId"), d.state]
}

test("initialize drops the lock's own slots (251-255) from lockCodes") {
  def d = withCodes(fresh(), alex + ["251": [name: "User 251", code: "000000", status: "active"]])
  d.initialize()
  [codesOf(d).keySet() == ["1"] as Set, codesOf(d).keySet()]
}

test("maxCodes follows the lock's slot count, capped at 250") {
  def d = fresh()
  def UNR = { int n -> cl('hubitat.zwave.commands.usercodev1.UsersNumberReport').newInstance(supportedUsers: n) }
  d.zwaveEvent(UNR(255)); d.zwaveEvent(UNR(30))
  [values(d, 'maxCodes') == [250, 30], values(d, 'maxCodes')]
}

test("refresh asks for lock state and battery") {
  def d = fresh()
  d.refresh()
  [d.sent*.name.containsAll(['doorLockOperationGet', 'batteryGet']), d.sent]
}

// On a real Assure 2 (ZW3, Z-Wave JS) a door-state get came back as event 0 with no
// parameters, which 1.08-dev logged as "Unknown event 0" at info.
test("an idle (event 0) notification changes nothing and isn't logged at info") {
  def d = fresh(txtEnable: true)
  d.zwaveEvent(NR(event: 0, eventParameter: []))
  [d.events.isEmpty() && !d.logs.any { it[0] == "info" }, [d.events, d.logs]]
}

test("supervised reports are unwrapped and handled") {
  def d = fresh()
  def sg = cl('hubitat.zwave.commands.supervisionv1.SupervisionGet').newInstance(inner: DLR(0xFF))
  d.zwaveEvent(sg); d.releaseLockReport()
  [values(d, 'lock') == ["locked"] && d.sent.any { it.toString().contains('supervisionReport') }, [d.events, d.sent]]
}

test("updated schedules logsOff when debug is on, and logsOff turns both off") {
  def d = fresh(debugEnable: true)
  d.updated()
  boolean scheduled = d.runIns.logsOff == 1800
  d.logsOff()
  [scheduled && d.updatedSettings.debugEnable == [value: "false", type: "bool"] && d.updatedSettings.traceEnable == [value: "false", type: "bool"], [d.runIns, d.updatedSettings]]
}

test("version: header and getDriverVersion agree") {
  def src = driverFile.text
  def header = (src =~ /\*\s+Version ([\d.]+)/)[0][1]
  [header == fresh().getDriverVersion(), "header $header, code ${fresh().getDriverVersion()}"]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
