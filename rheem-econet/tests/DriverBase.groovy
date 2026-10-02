import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Script base class that stands in for the Hubitat driver sandbox.
 *
 * A driver file is compiled with this as its base class, so the hub API it calls —
 * state, settings, log, device, location, sendEvent, httpPost, runIn and friends —
 * resolves to the recording fakes below. Only what these drivers use is modelled.
 *
 * Fidelity notes:
 *   - Hubitat persists `state` as JSON between executions. exec() round-trips it the
 *     same way, so type assumptions (Long vs Integer, List vs String) get exercised.
 *   - An unknown property resolves to a setting, as on the hub (login() reads `email`).
 *   - httpPost calls the closure for 2xx and otherwise throws HttpResponseException,
 *     as the hub does; FakeEcoNet decides which.
 */
abstract class DriverBase extends Script {
    Map state = [:]
    Map settings = [:]
    HubLog log = new HubLog()
    FakeDevice device = new FakeDevice(this)
    Map location = [temperatureScale: "F"]
    long clock = 1_800_000_000_000L

    List<Map> events = []
    List<Map> scheduled = []
    List<Map> requests = []
    Closure httpHandler = { Map params -> throw new IllegalStateException("no HTTP handler for ${params.uri}") }

    Map meta = [capabilities: [], attributes: [], commands: [], inputs: [], definition: [:]]

    // ---- property resolution ---------------------------------------------------
    def getProperty(String name) {
        try {
            return super.getProperty(name)
        } catch (MissingPropertyException e) {
            if (settings.containsKey(name)) return settings[name]
            throw e
        }
    }

    // Script would send every assignment to the binding; real fields must win.
    void setProperty(String name, Object value) {
        if (this.metaClass.hasProperty(this, name)) this.metaClass.setProperty(this, name, value)
        else super.setProperty(name, value)
    }

    // ---- metadata recording ----------------------------------------------------
    void metadata(Closure c) { c.call() }
    void definition(Map attrs, Closure c) { meta.definition = attrs; c.call() }
    void capability(String name) { meta.capabilities << name }
    void attribute(String name, String type, List values = null) {
        meta.attributes << [name: name, type: type, values: values]
    }
    void command(String name, List args = null) { meta.commands << [name: name, args: args] }
    void preferences(Closure c) { c.call() }
    void input(Map attrs) { meta.inputs << attrs }

    // ---- hub API -----------------------------------------------------------------
    long now() { clock }

    void sendEvent(Map evt) {
        events << evt
        device.current[evt.name] = evt.value
    }

    void runIn(Number seconds, String method, Map opts = null) {
        scheduled << [kind: "runIn", seconds: seconds as long, method: method]
    }
    void schedule(String cron, String method) { scheduled << [kind: "cron", cron: cron, method: method] }
    void runEvery1Minute(String m)    { scheduled << [kind: "every", minutes: 1,  method: m] }
    void runEvery5Minutes(String m)   { scheduled << [kind: "every", minutes: 5,  method: m] }
    void runEvery15Minutes(String m)  { scheduled << [kind: "every", minutes: 15, method: m] }
    void runEvery30Minutes(String m)  { scheduled << [kind: "every", minutes: 30, method: m] }
    void runEvery1Hour(String m)      { scheduled << [kind: "every", minutes: 60, method: m] }
    void unschedule(String method = null) {
        if (method == null) scheduled.clear()
        else scheduled.removeAll { it.method == method }
    }

    void httpPost(Map params, Closure c) {
        requests << params
        def resp = httpHandler.call(params)   // may throw HttpResponseException
        c.call(resp)
    }

    // ---- test helpers ------------------------------------------------------------
    /** Run a driver method as one hub execution: state is persisted as JSON around it. */
    def exec(String method, Object... args) {
        roundTripState()
        def result = this.invokeMethod(method, args)
        roundTripState()
        return result
    }

    void roundTripState() {
        def copy = new JsonSlurper().parseText(JsonOutput.toJson(state))
        state.clear()
        state.putAll(copy as Map)
    }

    /** Fire (and remove) the pending runIn jobs for a method, as the scheduler would. */
    int fire(String method) {
        def due = scheduled.findAll { it.kind == "runIn" && it.method == method }
        scheduled.removeAll(due)
        due.each { exec(method) }
        return due.size()
    }

    def current(String name) { device.current[name] }
    List<Map> eventsNamed(String name) { events.findAll { it.name == name } }
    List<Map> runInsFor(String method) { scheduled.findAll { it.kind == "runIn" && it.method == method } }
    void advance(long millis) { clock += millis }
}

class HubLog {
    List<Map> lines = []
    void debug(def m) { lines << [level: "debug", msg: m.toString()] }
    void info(def m)  { lines << [level: "info",  msg: m.toString()] }
    void warn(def m)  { lines << [level: "warn",  msg: m.toString()] }
    void error(def m) { lines << [level: "error", msg: m.toString()] }
    void trace(def m) { lines << [level: "trace", msg: m.toString()] }

    List<String> at(String level) { lines.findAll { it.level == level }*.msg }
    List<String> all() { lines*.msg }
    void clear() { lines.clear() }
}

class FakeDevice {
    final DriverBase driver
    Map current = [:]
    Map data = [:]
    List<String> deletedStates = []

    FakeDevice(DriverBase driver) { this.driver = driver }

    def currentValue(String name) { current[name] }

    void updateSetting(String name, Map spec) {
        def v = spec.value
        if (spec.type == "bool") v = (v.toString() == "true")
        driver.settings[name] = v
    }
    void updateSetting(String name, def value) { driver.settings[name] = value }
    void removeSetting(String name) { driver.settings.remove(name) }
    void deleteCurrentState(String name) {
        deletedStates << name
        current.remove(name)
    }
    void updateDataValue(String name, String value) { data[name] = value }
    String getDataValue(String name) { data[name] }
    String getDisplayName() { "Test device" }
}
