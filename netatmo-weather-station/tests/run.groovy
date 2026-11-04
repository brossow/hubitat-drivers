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
  FakeHttpError(int status, Object data = null, Map headers = [:]) {
    super("status code: ${status}")
    response = [status: status, data: data, headers: headers]
  }
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
  List apiLog = []
  List scheduled = []
  Map request = [:]
  void receive(Map r) { request = r }
  def getFullApiServerUrl() { "https://cloud.example/api/HUB/apps/1" }
  def createAccessToken() { "ENDPOINT-TOKEN" }
  def render(Map m) { m }
  Object parseJson(String text) { new groovy.json.JsonSlurper().parseText(text) }
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
  void runIn(def s, String h) { scheduled << [seconds: s, handler: h] }
  void runEvery5Minutes(String h) {}
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
    if (a instanceof Exception) throw a
    return a
  }
  void httpPost(Map params, Closure c) {
    if (params.uri.toString().endsWith("/oauth2/token")) { synchronized (this) { tokenCalls++ }; c([status: 200, data: next(tokenAnswers)]) }
    else { api("POST", params, c) }
  }
  void httpGet(Map params, Closure c) { api("GET", params, c) }
  void httpPut(Map params, Closure c) { api("PUT", params, c) }
  void httpDelete(Map params, Closure c) { api("DELETE", params, c) }
  private void api(String verb, Map params, Closure c) {
    apiCalls++
    apiLog << [verb: verb, uri: params.uri.toString(), body: params.body]
    c([status: 200, data: next(apiAnswers)])
  }
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

// ── Rate limits: Netatmo's "too many requests" is not a token problem ────

def httpError = { int status, Object data = null, Map headers = [:] ->
  gcl.loadClass("FakeHttpError").newInstance(status, data, headers)
}
def usageReached = { httpError(403, [error: [code: 26, message: "Maximum usage reached"]]) }

test("limit: 403 with code 26 doesn't renew the token or retry") {
  def a = validApp()
  a.apiAnswers << usageReached()
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [!r.success && a.tokenCalls == 0 && a.apiCalls == 1 && a.atomicState.netatmoRefreshToken == "OLD-REFRESH", [r, a.tokenCalls, a.apiCalls]]
}

test("limit: a JSON-text error body is read too") {
  def a = validApp()
  a.apiAnswers << httpError(403, '{"error":{"code":26}}')
  a.apiRequest("GET", "/api/getstationsdata")
  [a.tokenCalls == 0 && a.apiCalls == 1, [a.tokenCalls, a.apiCalls]]
}

test("limit: HTTP 429 honours Retry-After") {
  def a = validApp()
  a.apiAnswers << httpError(429, null, ["Retry-After": [value: "120"]])
  a.apiRequest("GET", "/api/getstationsdata")
  [a.atomicState.netatmoRateLimitedUntil == a.clock + 120000L, a.atomicState]
}

test("limit: a very long Retry-After is capped at an hour") {
  def a = validApp()
  a.apiAnswers << httpError(429, null, ["Retry-After": [value: "86400"]])
  a.apiRequest("GET", "/api/getstationsdata")
  [a.atomicState.netatmoRateLimitedUntil == a.clock + 3600000L, a.atomicState]
}

test("limit: polls wait out the backoff without calling Netatmo, then resume") {
  def a = validApp(selectedDeviceDnis: ["netatmo:STATION:STATION"])
  a.apiAnswers << usageReached()
  a.poll()
  String first = a.state.lastPollStatus
  int callsAfterHit = a.apiCalls
  a.advance(300000L)
  a.poll()
  String waiting = a.state.lastPollStatus
  int callsWhileWaiting = a.apiCalls
  a.advance(15 * 60000L)
  a.apiAnswers << stations
  a.poll()
  [first == "Waiting" && waiting == "Waiting" && callsWhileWaiting == callsAfterHit && a.state.lastPollStatus == "OK" && a.state.netatmoAuthenticated == true,
   [first, waiting, callsAfterHit, callsWhileWaiting, a.state.lastPollStatus]]
}

test("limit: hitting the limit doesn't sign the hub out or log an error") {
  def a = validApp(selectedDeviceDnis: ["netatmo:STATION:STATION"])
  a.apiAnswers << usageReached()
  a.poll()
  [a.state.netatmoAuthenticated == true && !a.logs.any { it.startsWith("error") }, a.logs]
}

test("limit: 403 'operation forbidden' (code 13) isn't treated as a bad token") {
  def a = validApp()
  a.apiAnswers << httpError(403, [error: [code: 13]])
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [!r.success && a.tokenCalls == 0 && a.apiCalls == 1, [a.tokenCalls, a.apiCalls]]
}

test("limit: 403 'access token expired' (code 3) still renews and retries") {
  def a = validApp()
  a.apiAnswers << httpError(403, [error: [code: 3]]) << stations
  a.tokenAnswers << newTokens
  Map r = a.apiRequest("GET", "/api/getstationsdata")
  [r.success && a.tokenCalls == 1, [r, a.tokenCalls]]
}

// ── Fewer calls: a rule refreshing several devices makes one ─────────────

test("cache: refreshing several children together reads Netatmo once") {
  def a = validApp()
  a.apiAnswers << stations
  ["netatmo:STATION:STATION", "netatmo:STATION:OUTDOOR", "netatmo:STATION:STATION"].each { a.refreshChild(it) }
  [a.apiCalls == 1, a.apiCalls]
}

test("cache: a refresh a minute later reads Netatmo again") {
  def a = validApp()
  a.apiAnswers << stations << stations
  a.refreshChild("netatmo:STATION:STATION")
  a.advance(61000L)
  a.refreshChild("netatmo:STATION:STATION")
  [a.apiCalls == 2, a.apiCalls]
}

test("cache: saving settings drops it, so new units apply on the next refresh") {
  def a = validApp()
  a.apiAnswers << stations << stations
  a.refreshChild("netatmo:STATION:STATION")
  a.initialize()
  a.refreshChild("netatmo:STATION:STATION")
  [a.apiCalls == 2, a.apiCalls]
}

test("cache: scheduled polls always read fresh data") {
  def a = validApp(selectedDeviceDnis: ["netatmo:STATION:STATION"])
  a.apiAnswers << stations << stations
  a.refreshChild("netatmo:STATION:STATION")
  a.poll()
  [a.apiCalls == 2, a.apiCalls]
}

// ── Webhooks: the endpoint ───────────────────────────────────────────────

def SECRET = "SECRET-PLACEHOLDER"
def sign = { String body, String secret = SECRET ->
  def mac = javax.crypto.Mac.getInstance("HmacSHA256")
  mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
  mac.doFinal(body.getBytes("UTF-8")).encodeHex().toString()
}
def hookApp = { Map settings = [:] ->
  def a = validApp([useWebhooks: true, selectedDeviceDnis: ["netatmo:STATION:STATION"]] + settings)
  a.atomicState.webhookRegistered = true
  a
}
def deliver = { a, Map payload, Map headers = null ->
  String body = groovy.json.JsonOutput.toJson(payload)
  a.receive([body: body, headers: headers != null ? headers : ["X-Netatmo-secret": sign(body)]])
  a.webhookEvent()
}
def deviceEvent = [push_type: "device_event", user_id: "USER-1", device_id: "01:23:45:67:89:ab", home_id: "HOME-1", home_name: "Private Home",
  extra_params: [sequence_id: 7, modules: [[id: "02:23:45:67:89:ab", type: "NAModule1", name: "Garden", reachable: true, temperature: 21.5]]]]

test("hook: the signature matches an independently computed HMAC-SHA256") {
  def a = hookApp()
  // printf '%s' '{"push_type":"device_event","home_name":"Café"}' | openssl dgst -sha256 -hmac SECRET-PLACEHOLDER
  String known = "1345a522dfbc5cb547d482bbb7d30434d865642c7a387d85a19952276e11271b"
  String body = '{"push_type":"device_event","home_name":"Café"}'
  [a.webhookSignatureValid(body, known) && !a.webhookSignatureValid(body + " ", known), "signature"]
}

test("hook: a bad signature gets 401 and does nothing") {
  def a = hookApp()
  def r = deliver(a, deviceEvent, ["X-Netatmo-secret": sign("something else")])
  [r.status == 401 && !a.scheduled && !a.atomicState.webhookLastEventAt && a.atomicState.webhookRejectedCount == 1, [r, a.atomicState]]
}

test("hook: a signature made with another secret gets 401") {
  def a = hookApp()
  String body = groovy.json.JsonOutput.toJson(deviceEvent)
  def r = deliver(a, deviceEvent, ["X-Netatmo-secret": sign(body, "ANOTHER-SECRET")])
  [r.status == 401, r]
}

test("hook: a missing signature gets 401") {
  def a = hookApp()
  def r = deliver(a, deviceEvent, [:])
  [r.status == 401 && !a.scheduled, r]
}

test("hook: header names and uppercase hex are matched case-insensitively") {
  def a = hookApp()
  String body = groovy.json.JsonOutput.toJson(deviceEvent)
  def r = deliver(a, deviceEvent, ["x-netatmo-secret": [sign(body).toUpperCase()]])
  [r.status == 200 && a.scheduled.size() == 1, [r, a.scheduled]]
}

test("hook: a signed device event answers 200 and schedules one read") {
  def a = hookApp()
  def r = deliver(a, deviceEvent)
  [r.status == 200 && a.scheduled == [[seconds: 10, handler: "webhookRefresh"]] && a.apiCalls == 0, [r, a.scheduled, a.apiCalls]]
}

test("hook: repeats and bursts share the one pending read") {
  def a = hookApp()
  3.times { deliver(a, deviceEvent); a.advance(1000L) }
  [a.scheduled.size() == 1, a.scheduled]
}

test("hook: the next read keeps its distance from the previous one") {
  def a = hookApp()
  a.apiAnswers << stations
  deliver(a, deviceEvent)
  a.advance(10000L)
  a.webhookRefresh()
  a.scheduled.clear()
  a.advance(60000L)
  deliver(a, deviceEvent)
  [a.scheduled == [[seconds: 240, handler: "webhookRefresh"]] && a.apiCalls == 1, a.scheduled]
}

test("hook: the wake-up read updates through the normal poll path") {
  def a = hookApp()
  a.apiAnswers << stations
  deliver(a, deviceEvent)
  a.webhookRefresh()
  [a.apiCalls == 1 && a.state.lastPollStatus == "OK" && !a.atomicState.webhookRefreshDueAt, [a.apiCalls, a.state.lastPollStatus]]
}

test("hook: nonsense from a signed sender still answers 200") {
  def a = hookApp()
  String body = "not json"
  a.receive([body: body, headers: ["X-Netatmo-secret": sign(body)]])
  def r = a.webhookEvent()
  [r.status == 200 && !a.scheduled, r]
}

test("hook: switched off, events answer 200 and do nothing") {
  def a = hookApp(useWebhooks: false)
  def r = deliver(a, deviceEvent)
  [r.status == 200 && !a.scheduled && !a.atomicState.webhookLastEventAt, r]
}

test("hook: webhook_deletion marks it unregistered, so polling takes over") {
  def a = hookApp()
  deliver(a, [push_type: "webhook_deletion", user_id: "USER-1"])
  [a.atomicState.webhookRegistered == false && !a.webhooksCoverPolling(), a.atomicState]
}

test("hook: the saved sample hides names, IDs and MAC addresses but keeps readings") {
  def a = hookApp()
  deliver(a, deviceEvent)
  String json = a.atomicState.webhookLastPayload.json
  boolean hidden = !["USER-1", "HOME-1", "Private Home", "01:23:45", "02:23:45", "Garden"].any { json.contains(it) }
  [hidden && json.contains("temperature") && json.contains("21.5") && json.contains("NAModule1"), json]
}

test("hook: the app's secret and the raw event never reach the logs") {
  def a = hookApp()
  deliver(a, deviceEvent)
  deliver(a, deviceEvent, ["X-Netatmo-secret": "bad"])
  [!a.logs.any { it.contains(SECRET) || it.contains("Private Home") || it.contains("ENDPOINT-TOKEN") }, a.logs]
}

// ── Webhooks: polling stands down only while they're arriving ────────────

test("cover: scheduled polls stand down right after an event and a read") {
  def a = hookApp()
  a.apiAnswers << stations
  deliver(a, deviceEvent)
  a.webhookRefresh()
  int calls = a.apiCalls
  a.advance(300000L)
  a.poll()
  [a.apiCalls == calls && a.state.lastPollStatus == "Webhooks active", [a.apiCalls, a.state.lastPollStatus]]
}

test("cover: polling resumes when webhooks go quiet for 30 minutes") {
  def a = hookApp()
  a.apiAnswers << stations << stations
  deliver(a, deviceEvent)
  a.webhookRefresh()
  a.advance(31 * 60000L)
  a.poll()
  [a.apiCalls == 2 && a.state.lastPollStatus == "OK", [a.apiCalls, a.state.lastPollStatus]]
}

test("cover: polling continues as normal before the first event") {
  def a = hookApp()
  a.apiAnswers << stations
  a.poll()
  [a.apiCalls == 1 && a.state.lastPollStatus == "OK", a.state.lastPollStatus]
}

test("cover: Run poll now always reads, even while webhooks are active") {
  def a = hookApp()
  a.apiAnswers << stations << stations
  deliver(a, deviceEvent)
  a.webhookRefresh()
  a.appButtonHandler("runPollNow")
  [a.apiCalls == 2, a.apiCalls]
}

test("cover: with webhooks off, scheduled polling is unchanged") {
  def a = validApp(selectedDeviceDnis: ["netatmo:STATION:STATION"])
  a.apiAnswers << stations
  a.poll()
  [a.apiCalls == 1, a.apiCalls]
}

// ── Webhooks: registering and removing ───────────────────────────────────

def HOOK_URL = "https://cloud.example/api/HUB/apps/1/webhook?access_token=ENDPOINT-TOKEN"
def hooks = { List urls -> [status: "ok", body: urls.collect { [url: it] }] }

test("register: turning it on adds this hub's address") {
  def a = validApp(useWebhooks: true)
  a.apiAnswers << hooks([]) << hooks([HOOK_URL])
  a.syncWebhookRegistration()
  def post = a.apiLog.find { it.verb == "POST" }
  [a.atomicState.webhookRegistered == true && post.uri == "https://api.netatmo.com/webhooks/v1/" && post.body == [url: HOOK_URL], a.apiLog]
}

test("register: already on Netatmo's list, it isn't added twice") {
  def a = validApp(useWebhooks: true)
  a.apiAnswers << hooks([HOOK_URL])
  a.syncWebhookRegistration()
  [a.atomicState.webhookRegistered == true && !a.apiLog.any { it.verb == "POST" }, a.apiLog]
}

test("register: another hub's webhook is left in place") {
  def a = validApp(useWebhooks: true)
  a.apiAnswers << hooks(["https://other.example/hook"]) << hooks([])
  a.syncWebhookRegistration()
  [a.atomicState.webhookRegistered == true && !a.apiLog.any { it.verb in ["PUT", "DELETE"] }, a.apiLog]
}

test("register: a refusal is reported in plain language and polling carries on") {
  [[403, "isn't allowed"], [409, "limit"], [400, "address"]].every { status, words ->
    def a = validApp(useWebhooks: true)
    a.apiAnswers << hooks([]) << httpError(status as int, [error: [code: 13]])
    a.syncWebhookRegistration()
    a.atomicState.webhookRegistered == false && a.webhookStatusText().contains(words as String) && !a.webhooksCoverPolling()
  } ? [true, ""] : [false, "wrong message or state"]
}

test("register: the address with its token never reaches the logs") {
  def a = validApp(useWebhooks: true)
  a.apiAnswers << hooks([]) << 400
  a.syncWebhookRegistration()
  [!a.logs.any { it.contains("ENDPOINT-TOKEN") }, a.logs]
}

test("register: not authorized yet, it doesn't call Netatmo") {
  def a = validApp(useWebhooks: true)
  a.state.netatmoAuthenticated = false
  a.syncWebhookRegistration()
  [a.apiCalls == 0, a.apiCalls]
}

test("unregister: turning it off removes the webhook") {
  def a = validApp(useWebhooks: false)
  a.atomicState.webhookRegistered = true
  a.apiAnswers << hooks([HOOK_URL]) << hooks([])
  a.syncWebhookRegistration()
  [a.atomicState.webhookRegistered == false && a.apiLog*.verb == ["GET", "DELETE"], a.apiLog]
}

test("unregister: another hub's webhook survives, never a delete-all") {
  def a = validApp(useWebhooks: false)
  a.atomicState.webhookRegistered = true
  a.apiAnswers << hooks([HOOK_URL, "https://other.example/hook"]) << hooks(["https://other.example/hook"])
  a.syncWebhookRegistration()
  def put = a.apiLog.find { it.verb == "PUT" }
  [a.atomicState.webhookRegistered == false && put.body == [[url: "https://other.example/hook"]] && !a.apiLog.any { it.verb == "DELETE" }, a.apiLog]
}

test("unregister: uninstalling removes the webhook") {
  def a = validApp(useWebhooks: true)
  a.atomicState.webhookRegistered = true
  a.apiAnswers << hooks([HOOK_URL]) << hooks([])
  a.uninstalled()
  [a.apiLog*.verb == ["GET", "DELETE"], a.apiLog]
}

test("unregister: a failure while uninstalling doesn't stop the uninstall") {
  def a = validApp(useWebhooks: true)
  a.atomicState.webhookRegistered = true
  a.apiAnswers << 500
  a.uninstalled()
  [true, ""]
}

test("save: Done schedules the registration; left off, it never touches webhooks") {
  def on = validApp(useWebhooks: true)
  on.initialize()
  def off = validApp()
  off.initialize()
  [on.scheduled.any { it.handler == "syncWebhookRegistration" } && !off.scheduled.any { it.handler == "syncWebhookRegistration" }, [on.scheduled, off.scheduled]]
}

println(ran == 0 ? "no tests match '$filter'" : failures ? "$failures of $ran FAILED" : "all $ran passed")
System.exit(failures || ran == 0 ? 1 : 0)
