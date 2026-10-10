// Off-hub tests for the BirdWeather PUC driver. Run with tests/run.sh.
//
// The driver file is parsed (never run, so metadata{} and preferences{} are skipped)
// on top of HubStub, a minimal stand-in for the Hubitat sandbox. asynchttpGet only
// queues the request; deliver() answers the queue in order from FakeStation, a fake
// BirdWeather API that pages and caps results the way the real one does. The clock
// is the stub's, so tests can move through time.
//
// Every identifier here is a placeholder: never put a real station ID or token in
// this file.

import org.codehaus.groovy.control.CompilerConfiguration
import groovy.json.JsonSlurper

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''
class FakeResponse {
  int status
  def json
  boolean hasError() { status < 200 || status >= 300 }
  String getErrorMessage() { "status code: ${status}" }
}
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map state = [:]
  Map attrs = [:]
  List events = []
  List logs = []
  List pending = []      // [handler, params, data] waiting for an answer
  List requests = []     // every request made, in order
  Map scheduled = [:]    // handler name -> when (seconds or cron)
  Map sun = null
  long clock = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-06-15T17:00:00Z").time  // noon in Chicago
  def log = [error: { m -> logs << "error: $m" }, warn: { m -> logs << "warn: $m" },
             info: { m -> logs << "info: $m" }, debug: { m -> }]
  def location = [timeZone: TimeZone.getTimeZone("America/Chicago")]
  // Preferences resolve like undeclared properties on the hub: missing ones are null
  def propertyMissing(String n) { settingsMap[n] }
  long now() { clock }
  void advance(long ms) { clock += ms }
  void setSunTimes(Map m) { sun = m }
  Map getSunriseAndSunset() { sun ?: [sunrise: new Date(clock - 6 * 3600000L), sunset: new Date(clock + 6 * 3600000L)] }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void asynchttpGet(String handler, Map params, Map data = null) {
    pending << [handler, params, data]
    requests << params
  }
  void runIn(Number s, def h, Map o = null) { scheduled[h?.toString()] = s }
  void schedule(String cron, def h) { scheduled[h?.toString()] = cron }
  void unschedule(def h = null) { if (h == null) scheduled.clear() else scheduled.remove(h.toString()) }
}
// A fake BirdWeather station. Detections are kept newest first with rising IDs, and
// each one adds its species to the all-time list, as the real API does.
class FakeStation {
  List detections = []
  List lifetime = []            // all-time species, most-detected first
  Map failures = [:]            // endpoint -> queue of HTTP statuses to answer with
  long nextId = 1000
  def stub

  Map detect(String name, double conf = 0.9, String cert = "almost_certain") {
    Map d = [id: nextId++, timestamp: new Date(stub.clock).format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", stub.location.timeZone),
             confidence: conf, certainty: cert,
             species: [commonName: name, scientificName: "Avis ${name.toLowerCase()}", thumbnailUrl: "https://example.com/${name}.jpg"],
             soundscape: [url: "https://example.com/${nextId}.flac"]]
    detections.add(0, d)
    if (!(name in lifetime)) lifetime << name
    d
  }

  Object answer(Map p) {
    String path = p.uri.toString().split("/stations/")[1]
    String endpoint = path.contains("/") ? path.substring(path.indexOf("/") + 1) : ""
    Map q = p.query ?: [:]
    List queue = failures[endpoint]
    if (queue) return new FakeResponse(status: queue.remove(0) as int)
    int limit = Math.min((q.limit ?: 100) as int, 100)    // the API caps limit at 100
    switch (endpoint) {
      case "detections":
        List list = q.cursor ? detections.findAll { it.id < (q.cursor as long) } : detections
        return new FakeResponse(status: 200, json: [success: true, detections: list.take(limit)])
      case "stats":
        return new FakeResponse(status: 200, json: [success: true, detections: detections.size(),
                                                    species: detections*.species*.commonName.unique().size()])
      case "species":
        List names = q.period == "all" ? lifetime : detections*.species*.commonName.unique()
        int page = (q.page ?: 1) as int
        List slice = names.drop((page - 1) * limit).take(limit)
        return new FakeResponse(status: 200, json: [success: true,
          species: slice.collect { [commonName: it, scientificName: "Avis", detections: [total: 1]] }])
    }
    return new FakeResponse(status: 404)
  }
}''')

def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
def driverFile = new File(args[0])
def manifestFile = new File(args[1])
String filter = args.length > 2 ? args[2] : ""

def fresh = { Map settings = [:] ->
  def d = shell.parse(driverFile)
  d.settingsMap.putAll([stationId: "12345", pollInterval: "5 minutes", historyDepth: "5",
                        minConfidencePct: 0, announceCertaintyFilter: "all",
                        nightModeEnable: false, enableBirdDetectedEvent: true] + settings)
  d
}
def stationFor = { d ->
  def st = gcl.loadClass("FakeStation").newInstance()
  st.stub = d
  st
}
// Answer every queued request, including any the handlers queue in turn
def deliver = { d, st ->
  int n = 0
  while (d.pending && n++ < 500) {
    def (h, p, data) = d.pending.remove(0)
    d.invokeMethod(h, [st.answer(p), data] as Object[])
  }
}
def pollNow = { d, st -> d.poll(); deliver(d, st) }
// A driver that has already polled once and loaded the all-time list
def running = { Map settings = [:], List seed = ["Robin", "Sparrow", "Cardinal"] ->
  def d = fresh(settings)
  def st = stationFor(d)
  seed.each { st.detect(it) }
  d.refresh(); deliver(d, st)
  d.events.clear()
  d.advance(300000L)
  [d, st]
}
def eventsNamed = { d, String n -> d.events.findAll { it.name == n }*.value }
def detectionRequests = { d -> d.requests.findAll { it.uri.toString().endsWith("/detections") } }

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

// ── Catching up on every detection since the last poll ───────────────────
// A busy station logs well over 10 detections in 5 minutes. 1.5.0 asked for
// max(depth, 10) and silently dropped anything older.

test("catch-up: a species that only appears in the older detections still fires newSpeciesDetected") {
  def (d, st) = running()
  st.detect("Wren")
  24.times { st.detect("Robin") }
  pollNow(d, st)
  ["Wren" in eventsNamed(d, "newSpeciesDetected"), eventsNamed(d, "newSpeciesDetected")]
}

test("catch-up: a first-ever species in the older detections fires newLifetimeSpeciesDetected") {
  def (d, st) = running()
  st.detect("Snowy Owl")
  24.times { st.detect("Robin") }
  pollNow(d, st)
  [eventsNamed(d, "newLifetimeSpeciesDetected") == ["Snowy Owl"], eventsNamed(d, "newLifetimeSpeciesDetected")]
}

test("catch-up: ...and the hourly list refresh doesn't swallow it first") {
  def (d, st) = running()
  st.detect("Snowy Owl")
  24.times { st.detect("Robin") }
  d.advance(3600000L)            // the all-time list is due again on this poll
  pollNow(d, st)
  [eventsNamed(d, "newLifetimeSpeciesDetected") == ["Snowy Owl"], eventsNamed(d, "newLifetimeSpeciesDetected")]
}

test("catch-up: after a long gap, birdDetected fires for the 10 newest only") {
  def (d, st) = running()
  25.times { st.detect("Robin") }
  pollNow(d, st)
  [eventsNamed(d, "birdDetected").size() == 10, eventsNamed(d, "birdDetected").size()]
}

test("catch-up: a quiet poll makes one small request") {
  def (d, st) = running()
  st.detect("Robin")
  d.requests.clear()
  pollNow(d, st)
  List r = detectionRequests(d)
  [r.size() == 1 && (r[0].query.limit as int) <= 25, r*.query]
}

test("catch-up: the walk back through history stops after 5 pages") {
  def (d, st) = running()
  700.times { st.detect("Robin") }
  d.requests.clear()
  pollNow(d, st)
  [detectionRequests(d).size() <= 6 && d.state.lastDetectionId == st.detections[0].id.toString()
     && d.logs.any { it.startsWith("warn") },
   [detectionRequests(d).size(), d.state.lastDetectionId, d.logs]]
}

test("catch-up: nothing new means no events") {
  def (d, st) = running()
  pollNow(d, st)
  [eventsNamed(d, "birdDetected").isEmpty() && eventsNamed(d, "newSpeciesDetected").isEmpty(), d.events*.name]
}

// ── Minimum confidence ───────────────────────────────────────────────────

test("min confidence: a poll where nothing passes reports OK, not an error") {
  def (d, st) = running(minConfidencePct: 95)
  st.detect("Robin", 0.6)
  pollNow(d, st)
  [d.attrs.lastPollStatus == "OK", d.attrs.lastPollStatus]
}

test("min confidence: raising it doesn't replay detections already seen") {
  def d = fresh()
  def st = stationFor(d)
  12.times { st.detect("Robin", 0.95) }
  st.detect("Sparrow", 0.6)      // the newest is below the new threshold
  d.refresh(); deliver(d, st)
  d.events.clear()
  d.settingsMap.minConfidencePct = 80
  d.advance(300000L)
  pollNow(d, st)
  [eventsNamed(d, "birdDetected").isEmpty(), eventsNamed(d, "birdDetected")]
}

test("min confidence: low-confidence detections don't fire events") {
  def (d, st) = running(minConfidencePct: 80)
  st.detect("Wren", 0.5)
  st.detect("Robin", 0.9)
  pollNow(d, st)
  [eventsNamed(d, "birdDetected") == ["Robin"] && !("Wren" in eventsNamed(d, "newSpeciesDetected")), d.events]
}

// ── Station ID and private-station token ─────────────────────────────────

test("token: a private station's token goes in the URL path, not an Authorization header") {
  def d = fresh(apiToken: " TOKEN-PLACEHOLDER ")
  d.poll()
  List uris = d.requests*.uri*.toString()
  [uris.every { it.contains("/stations/TOKEN-PLACEHOLDER/") } && d.requests.every { !it.headers?.Authorization },
   [uris, d.requests*.headers]]
}

test("station ID: surrounding spaces are trimmed from the URL") {
  def d = fresh(stationId: " 12345 ")
  d.poll()
  [d.requests*.uri*.toString().every { it ==~ /https:\/\/app\.birdweather\.com\/api\/v1\/stations\/12345\/[a-z]+/ }, d.requests*.uri]
}

test("station ID: a pasted station URL is reduced to its number") {
  def d = fresh(stationId: "https://app.birdweather.com/stations/12345")
  d.poll()
  [d.requests*.uri*.toString().every { it ==~ /https:\/\/app\.birdweather\.com\/api\/v1\/stations\/12345\/[a-z]+/ }, d.requests*.uri]
}

// ── Errors, retries and healthStatus ─────────────────────────────────────

test("retry: a server error schedules a retry") {
  def (d, st) = running()
  st.failures.detections = [503]
  pollNow(d, st)
  [d.scheduled.containsKey("retryPoll"), d.scheduled]
}

test("retry: saving preferences while a retry is pending doesn't turn retries off") {
  def (d, st) = running()
  st.failures.detections = [503]
  pollNow(d, st)
  d.updated()                    // unschedule() drops the pending retry
  d.pending.clear()
  st.failures.detections = [503]
  pollNow(d, st)
  [d.scheduled.containsKey("retryPoll"), d.scheduled]
}

test("health: online after a good poll") {
  def (d, st) = running()
  [d.attrs.healthStatus == "online", d.attrs.healthStatus]
}

test("health: one failure stays online, a failed retry goes offline, a good poll comes back") {
  def (d, st) = running()
  st.failures.detections = [503, 503]
  pollNow(d, st)
  String afterOne = d.attrs.healthStatus
  d.retryPoll(); deliver(d, st)
  String afterTwo = d.attrs.healthStatus
  pollNow(d, st)
  [afterOne == "online" && afterTwo == "offline" && d.attrs.healthStatus == "online",
   [afterOne, afterTwo, d.attrs.healthStatus]]
}

// ── The all-time species list ────────────────────────────────────────────

test("lifetime: more than 100 species are all loaded") {
  def d = fresh()
  def st = stationFor(d)
  250.times { st.lifetime << "Species ${it}".toString() }
  st.detect("Robin")
  d.refresh(); deliver(d, st)
  [d.state.lifetimeSpeciesSeen.size() == 251, d.state.lifetimeSpeciesSeen.size()]
}

test("lifetime: the list is read after detections, never alongside them") {
  def d = fresh()
  def st = stationFor(d)
  st.detect("Robin")
  d.refresh()
  List firstWave = d.pending.collect { it[1] }
  deliver(d, st)
  boolean allTimeInFirstWave = firstWave.any { it.uri.toString().endsWith("/species") && it.query.period == "all" }
  [!allTimeInFirstWave && d.state.lifetimeBootstrapped == true, firstWave*.query]
}

test("lifetime: a slow page-by-page detections walk still ends with the list read") {
  def (d, st) = running()
  300.times { st.detect("Robin") }
  d.advance(3600000L)
  pollNow(d, st)
  [d.state.lastLifetimeFetchMs == d.clock, d.state.lastLifetimeFetchMs]
}

test("lifetime: a failed detections poll still reads the list") {
  def (d, st) = running()
  st.failures.detections = [503]
  d.advance(3600000L)
  pollNow(d, st)
  [d.state.lastLifetimeFetchMs == d.clock, d.state.lastLifetimeFetchMs]
}

test("lifetime: installing doesn't alert on the newest bird, even one the list doesn't have yet") {
  def d = fresh()
  def st = stationFor(d)
  st.detect("Robin")
  st.lifetime.remove("Robin")    // BirdWeather's all-time list hasn't caught up
  d.refresh(); deliver(d, st)
  [eventsNamed(d, "newLifetimeSpeciesDetected").isEmpty(), eventsNamed(d, "newLifetimeSpeciesDetected")]
}

test("lifetime: a failed page keeps the existing list and still reads detections") {
  def (d, st) = running()
  int before = d.state.lifetimeSpeciesSeen.size()
  st.failures.species = [503, 503]   // the day top-species and the all-time page
  st.detect("Robin")
  d.refresh(); deliver(d, st)
  [d.state.lifetimeSpeciesSeen.size() == before && eventsNamed(d, "birdDetected") == ["Robin"],
   [d.state.lifetimeSpeciesSeen, d.events*.name]]
}

test("lifetime: a genuinely new species fires once, not on its next sighting too") {
  def (d, st) = running()
  st.detect("Heron")
  pollNow(d, st)
  d.advance(3600000L)
  st.detect("Heron")
  pollNow(d, st)
  [eventsNamed(d, "newLifetimeSpeciesDetected") == ["Heron"], eventsNamed(d, "newLifetimeSpeciesDetected")]
}

// ── Today, night mode, first run ─────────────────────────────────────────

test("first run: only the newest detection is processed") {
  def d = fresh()
  def st = stationFor(d)
  ["Robin", "Sparrow", "Wren"].each { st.detect(it) }
  d.refresh(); deliver(d, st)
  [eventsNamed(d, "birdDetected") == ["Wren"], eventsNamed(d, "birdDetected")]
}

test("today: the species list resets at local midnight") {
  def (d, st) = running()
  d.advance(12 * 3600000L)       // past midnight in Chicago
  st.detect("Robin")
  pollNow(d, st)
  ["Robin" in eventsNamed(d, "newSpeciesDetected") && d.state.todaySpeciesSeen == ["Robin"], d.state.todaySpeciesSeen]
}

test("night mode: polls are skipped after sunset") {
  def d = fresh(nightModeEnable: true)
  d.setSunTimes([sunrise: new Date(d.clock + 3600000L), sunset: new Date(d.clock + 7200000L)])
  d.poll()
  [d.requests.isEmpty(), d.requests*.uri]
}

test("night mode: a hub without sunrise/sunset times still polls") {
  def d = fresh(nightModeEnable: true)
  d.setSunTimes([sunrise: null, sunset: null])
  d.poll()
  [!d.requests.isEmpty(), d.logs]
}

// ── Release bookkeeping ──────────────────────────────────────────────────

test("version: the driver and packageManifest.json agree") {
  def manifest = new JsonSlurper().parse(manifestFile)
  String v = fresh().getDriverVersion()
  [v == manifest.version, [driver: v, manifest: manifest.version]]
}

println "\n${ran - failures}/${ran} passed"
if (failures) System.exit(1)
