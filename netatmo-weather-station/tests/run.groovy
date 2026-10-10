// Off-hub tests for Netatmo Weather Station. Run with tests/run.sh.
//
// The app and driver files are parsed (never run, so definition{}/metadata{} are
// skipped) on top of HubStub, a minimal stand-in for the Hubitat sandbox. Netatmo's
// token endpoint and API are faked: each test decides how Netatmo answers, including
// failures with no response at all, so token handling can be checked end to end.
//
// Every identifier here is a placeholder: never put real client IDs, secrets, tokens
// or station MAC addresses in this file.

import org.codehaus.groovy.control.CompilerConfiguration

def gcl = new GroovyClassLoader(this.class.classLoader)
gcl.parseClass('''
// What Hubitat's http* methods throw for an HTTP error: the status is on e.response
class FakeHttpError extends Exception {
  Map response
  FakeHttpError(int status) { super("status code: ${status}"); response = [status: status] }
}
abstract class HubStub extends Script {
  Map settingsMap = [:]
  Map state = [:]
  Map atomicState = [:]
  def app = [id: 1L]
  Map attrs = [:]
  List events = []
  List logs = []
  long clock = 1767225600000L   // 2026-01-01T00:00:00Z
  // Queues of answers: a Map is a 200 response body, an Integer an HTTP error, null no
  // response, and a Closure is called (it can sleep, to hold a request open) for its answer
  List tokenAnswers = []
  List apiAnswers = []
  int tokenCalls = 0
  int apiCalls = 0
  def log = [error: { m -> logs << "error: $m" }, warn: { m -> logs << "warn: $m" },
             info: { m -> logs << "info: $m" }, debug: { m -> }]
  def location = [temperatureScale: "C", timeZone: TimeZone.getTimeZone("UTC")]
  def parent = null
  def device = [deviceNetworkId: "netatmo:STATION:STATION"]
  Map getSettings() { settingsMap }
  def propertyMissing(String n) { settingsMap[n] }
  long now() { clock }
  void advance(long ms) { clock += ms }
  // Share another execution's atomicState and fake Netatmo. Set through reflection:
  // assigning a property on a Script (b.atomicState = …) writes to its binding instead.
  void shareWith(HubStub other) {
    ["atomicState", "tokenAnswers", "apiAnswers"].each { name ->
      def f = HubStub.getDeclaredField(name)
      f.accessible = true
      f.set(this, f.get(other))
    }
  }
  void sendEvent(Map m) { events << m; attrs[m.name] = m.value }
  void runIn(def s, String h) {}
  void unschedule(String h = null) {}
  def getChildDevice(String dni) { null }
  def getChildDevices() { [] }
  private Object next(List answers) {
    if (answers.isEmpty()) throw new IllegalStateException("no fake answer queued")
    def a
    synchronized (answers) { a = answers.remove(0) }
    if (a instanceof Closure) a = a.call()
    if (a == null) throw new java.net.SocketTimeoutException("Read timed out")
    if (a instanceof Integer) throw new FakeHttpError(a as int)
    return a
  }
  void httpPost(Map params, Closure c) {
    if (params.uri.toString().endsWith("/oauth2/token")) { synchronized (this) { tokenCalls++ }; c([status: 200, data: next(tokenAnswers)]) }
    else { apiCalls++; c([status: 200, data: next(apiAnswers)]) }
  }
  void httpGet(Map params, Closure c) { apiCalls++; c([status: 200, data: next(apiAnswers)]) }
}''')
def shell = new GroovyShell(gcl, new Binding(), new CompilerConfiguration(scriptBaseClass: 'HubStub'))
File dir = new File(args[0])
String filter = args.length > 1 ? args[1] : ""

def app = { Map settings = [:] ->
  def a = shell.parse(new File(dir, "NetatmoWeatherStationConnect.groovy"))
  a.settingsMap.putAll([clientId: "CLIENT-ID-PLACEHOLDER", clientSecret: "SECRET-PLACEHOLDER", pollIntervalMinutes: "5"] + settings)
  a
}
// An authorized app whose access token has expired, so the next API call must refresh it
def withTokens = { a, long expiresAt ->
  Map tokens = [netatmoAccessToken: "OLD-ACCESS", netatmoRefreshToken: "OLD-REFRESH", netatmoTokenExpiresAt: expiresAt]
  a.atomicState.putAll(tokens)
  a.state.putAll(tokens + [netatmoAuthenticated: true])
  a
}
def expiredApp = { Map settings = [:] -> withTokens(app(settings), 1767225600000L - 1000L) }
// ... and one whose token is still good
def validApp = { Map settings = [:] -> withTokens(app(settings), 1767225600000L + 3 * 3600000L) }
def newTokens = [access_token: "NEW-ACCESS", refresh_token: "NEW-REFRESH", expires_in: 10800]
def stations = [status: "ok", body: [devices: [[_id: "STATION", type: "NAMain", station_name: "Home", reachable: true,
  dashboard_data: [Temperature: 21.5, Humidity: 40, health_idx: 1, time_utc: 1767225000],
  modules: [[_id: "OUTDOOR", type: "NAModule1", module_name: "Garden", reachable: false]]]]]]

def driver = { String name ->
  shell.parse(new File(dir, "NetatmoWeather${name}.groovy"))
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

// ── Token refresh: temporary failures must not sign the hub out ──────────

test("refresh: no response (timeout) keeps the hub authorized and the tokens") {
  def a = expiredApp()
  a.tokenAnswers << null
  boolean ok = a.refreshAccessToken()
  [!ok && a.state.netatmoAuthenticated == true && a.atomicState.netatmoRefreshToken == "OLD-REFRESH", a.state]
}

test("refresh: a Netatmo outage (HTTP 503) keeps the hub authorized") {
  def a = expiredApp()
  a.tokenAnswers << 503
  a.refreshAccessToken()
  [a.state.netatmoAuthenticated == true, a.state.netatmoAuthenticated]
}

test("refresh: a reply without tokens keeps the hub authorized") {
  def a = expiredApp()
  a.tokenAnswers << [error: "something odd"]
  a.refreshAccessToken()
  [a.state.netatmoAuthenticated == true, a.state.netatmoAuthenticated]
}

test("refresh: a rejected refresh token (HTTP 400, invalid_grant) signs the hub out") {
  def a = expiredApp()
  a.tokenAnswers << 400
  a.refreshAccessToken()
  [a.state.netatmoAuthenticated == false && a.logs.any { it.contains("Reauthorize") }, a.logs]
}

test("refresh: a successful refresh stores the new tokens") {
  def a = expiredApp()
  a.tokenAnswers << newTokens
  boolean ok = a.refreshAccessToken()
  [ok && a.atomicState.netatmoAccessToken == "NEW-ACCESS" && a.atomicState.netatmoRefreshToken == "NEW-REFRESH", a.atomicState]
}

test("poll: after a failed refresh, the next poll tries again and recovers") {
  def a = expiredApp(selectedDeviceDnis: ["netatmo:STATION:STATION"])
  a.tokenAnswers << null                  // first poll: Netatmo unreachable
  a.poll()
  String first = a.state.lastPollStatus
  a.advance(300000L)
  a.tokenAnswers << newTokens             // second poll: Netatmo back
  a.apiAnswers << stations
  a.poll()
  [first == "Error" && a.state.lastPollStatus == "OK" && a.tokenCalls == 2, [first, a.state.lastPollStatus, a.state.lastPollMessage]]
}

// ── API calls with a token Netatmo no longer accepts ─────────────────────

test("api: HTTP 403 (Netatmo's expired/invalid token) refreshes and retries once") {
  def a = validApp()
  a.apiAnswers << 403 << stations
  a.tokenAnswers << newTokens
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [r.success && a.tokenCalls == 1 && a.apiCalls == 2, r]
}

test("api: HTTP 401 also refreshes and retries once") {
  def a = validApp()
  a.apiAnswers << 401 << stations
  a.tokenAnswers << newTokens
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [r.success && a.tokenCalls == 1, r]
}

test("api: a second 403 after refreshing is not retried again") {
  def a = validApp()
  a.apiAnswers << 403 << 403
  a.tokenAnswers << newTokens
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [!r.success && a.apiCalls == 2 && a.tokenCalls == 1, [r, a.apiCalls, a.tokenCalls]]
}

test("api: other errors (HTTP 500) don't trigger a token refresh") {
  def a = validApp()
  a.apiAnswers << 500
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [!r.success && a.tokenCalls == 0, r]
}

// ── Overlapping executions sharing one set of tokens ─────────────────────
// Two instances of the same parsed app class stand in for two executions: they share
// the class's static token store and atomicState, but each has its own state copy.

def twoExecutions = { long expiresAt ->
  def a = withTokens(app(), expiresAt)
  def b = a.getClass().newInstance()
  b.settingsMap.putAll(a.settingsMap)
  b.shareWith(a)
  b.state.putAll(a.state)   // b's copy was loaded before a renewed: old, expired tokens
  [a, b]
}

test("overlap: a second execution uses the token the first one renewed") {
  def (a, b) = twoExecutions(1767225600000L - 1000L)
  a.tokenAnswers << newTokens
  boolean first = a.ensureValidToken()
  boolean second = b.ensureValidToken()
  [first && second && a.tokenCalls + b.tokenCalls == 1, [a.tokenCalls, b.tokenCalls]]
}

test("overlap: two renewals at the same moment spend the refresh token once") {
  def (a, b) = twoExecutions(1767225600000L - 1000L)
  // Netatmo takes a moment to answer, and would reject the spent refresh token
  a.tokenAnswers << { Thread.sleep(300); newTokens } << 400
  List results = Collections.synchronizedList([])
  def t1 = Thread.start { results << a.ensureValidToken() }
  def t2 = Thread.start { results << b.ensureValidToken() }
  [t1, t2]*.join()
  [results == [true, true] && a.tokenCalls + b.tokenCalls == 1 && a.state.netatmoAuthenticated && b.state.netatmoAuthenticated,
   [results, a.tokenCalls + b.tokenCalls]]
}

test("overlap: a 403 after another execution renewed retries without renewing again") {
  def (a, b) = twoExecutions(1767225600000L + 3 * 3600000L)
  // Both sent requests with OLD-ACCESS and Netatmo refused both (revoked token).
  // a renews first; b, holding the same refused token, must use a's instead.
  a.tokenAnswers << newTokens
  boolean aRenewed = a.renewToken("OLD-ACCESS")
  boolean bRenewed = b.renewToken("OLD-ACCESS")
  [aRenewed && bRenewed && a.tokenCalls + b.tokenCalls == 1 && b.netatmoAccessToken() == "NEW-ACCESS",
   [a.tokenCalls, b.tokenCalls, b.netatmoAccessToken()]]
}

test("overlap: renewed tokens reach atomicState and the execution's own state copy") {
  def a = expiredApp()
  a.tokenAnswers << newTokens
  a.ensureValidToken()
  [a.atomicState.netatmoRefreshToken == "NEW-REFRESH" && a.state.netatmoRefreshToken == "NEW-REFRESH", [a.atomicState, a.state]]
}

test("overlap: a stale state copy can't bring back spent tokens") {
  def (a, b) = twoExecutions(1767225600000L - 1000L)
  a.tokenAnswers << newTokens
  a.ensureValidToken()
  // b's state still holds the old tokens; it must read the shared store, not its copy
  [b.netatmoRefreshToken() == "NEW-REFRESH" && b.isTokenValid(), b.netatmoRefreshToken()]
}

test("lock: a lock held for over 2 minutes by a cut-off execution is taken over") {
  def a = expiredApp()
  def cls = a.getClass()
  cls.tokenLock.acquire()                          // an execution took the lock...
  cls.tokenLockTakenAt = a.clock - 180000L         // ...3 minutes ago, and never released it
  a.tokenAnswers << newTokens
  boolean ok = a.ensureValidToken()
  [ok && cls.tokenLock.availablePermits() == 1, [ok, cls.tokenLock.availablePermits()]]
}

test("lock: clearing authorization forgets the shared tokens") {
  def a = validApp()
  a.clearAuthState()
  [!a.isTokenValid() && !a.atomicState.containsKey("netatmoRefreshToken") && a.netatmoRefreshToken() == null, a.atomicState]
}

// ── healthStatus: online/offline from Netatmo's reachable flag ───────────

test("health: health_idx (a Home Coach field) no longer feeds healthStatus") {
  Map n = app().normalizeStationData(stations)
  Map base = n["netatmo:STATION:STATION"]
  [!base.dashboard.containsKey("healthStatus") && base.dashboard.healthIndex == 1, base.dashboard]
}

["BaseStation", "OutdoorModule", "IndoorModule", "RainGauge", "WindGauge"].each { name ->
  test("health: ${name} reports online when reachable and offline when not") {
    def d = driver(name)
    d.updatedFromParent([reachable: true, dashboard: [:], metadata: [:], units: [:]])
    def first = d.attrs.healthStatus
    d.updatedFromParent([reachable: false, dashboard: [:], metadata: [:], units: [:]])
    [first == "online" && d.attrs.healthStatus == "offline", [first, d.attrs.healthStatus]]
  }
}

test("health: no reachable value means no healthStatus event") {
  def d = driver("OutdoorModule")
  d.updatedFromParent([dashboard: [:], metadata: [:], units: [:]])
  [!d.attrs.containsKey("healthStatus"), d.attrs]
}

test("health: an unreachable module from the API ends up offline") {
  def a = app()
  Map n = a.normalizeStationData(stations)
  def d = driver("OutdoorModule")
  d.updatedFromParent(n["netatmo:STATION:OUTDOOR"])
  [d.attrs.healthStatus == "offline", d.attrs.healthStatus]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
