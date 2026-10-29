import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.HttpResponseException

/**
 * A scripted stand-in for the EcoNet (ClearBlade) REST API.
 *
 * Tests set `locations` to the account contents and flip the failure knobs; every
 * response is round-tripped through JSON so the driver sees parsed data, as on the hub.
 */
class FakeEcoNet {
    List locations = []
    String token = "TOKEN-PLACEHOLDER"
    String accountId = "ACCOUNT-PLACEHOLDER-0001"

    // Failure knobs
    Integer authStatus = null          // non-2xx → thrown, as the hub does
    boolean rejectCredentials = false  // 200 with success=false
    Integer pollStatus = null
    Integer pollFailTimes = null       // null = every poll fails while pollStatus is set
    Integer publishStatus = null
    Exception networkError = null
    Exception pollError = null         // thrown by polls only, e.g. a timeout once logged in
    boolean pollUnsuccessful = false   // 200 with success=false

    // Usage Report (dynamicAction): usage_type → day ("yyyy-MM-dd") → [hour: value].
    // A usage_type with no entry answers success=false, as for a unit without that report.
    Map usage = [:]
    Map usageMessages = [energyUsage: "Energy Usage in kWh", waterUsage: "Water Usage in gallons"]
    Integer usageStatus = null
    List<Map> usageRequests = []       // decoded request bodies

    int authCalls = 0
    int pollCalls = 0
    List<Map> published = []           // decoded MQTT payloads

    Closure handler() {
        return { Map params -> respond(params) }
    }

    def respond(Map params) {
        if (networkError) throw networkError
        String uri = params.uri.toString()
        if (uri.endsWith("/user/auth")) {
            authCalls++
            if (authStatus) throw new HttpResponseException(authStatus)
            if (rejectCredentials) return resp(200, [options: [success: false, message: "Invalid credentials"]])
            return resp(200, [user_token: token, options: [success: true, account_id: accountId]])
        }
        if (uri.contains("/getUserDataForApp")) {
            pollCalls++
            if (pollStatus && (pollFailTimes == null || pollFailTimes-- > 0)) throw new HttpResponseException(pollStatus)
            if (pollError) throw pollError
            if (pollUnsuccessful) return resp(200, [success: false])
            return resp(200, [success: true, results: [locations: locations]])
        }
        if (uri.contains("/dynamicAction")) {
            def body = new JsonSlurper().parseText(params.body as String) as Map
            usageRequests << body
            if (usageStatus) throw new HttpResponseException(usageStatus)
            String type = body.usage_type
            if (!usage.containsKey(type)) return resp(200, [success: false])
            String day = (body.start_date as String).substring(0, 10)
            def hours = (usage[type][day] ?: [:]) as Map
            def data = hours.collect { h, v -> [name: h.toString(), value: v] }
            String key = type == "energyUsage" ? "energy_usage" : "water_usage"
            return resp(200, [success: true, results: [(key): [data: data, historyData: [], message: usageMessages[type]]]])
        }
        if (uri.contains("/publish")) {
            if (publishStatus) throw new HttpResponseException(publishStatus)
            def outer = new JsonSlurper().parseText(params.body as String)
            def payload = new JsonSlurper().parseText(outer.body as String)
            published << [topic: outer.topic, payload: payload]
            return [status: 200, data: "OK"]
        }
        throw new IllegalArgumentException("unexpected URI ${uri}")
    }

    static Map resp(int status, Object data) {
        [status: status, data: new JsonSlurper().parseText(JsonOutput.toJson(data))]
    }

    /** Fields of the most recent command, without the envelope (transactionId, ids). */
    Map lastCommand() {
        def p = published ? new LinkedHashMap(published[-1].payload as Map) : null
        p?.remove("transactionId")
        p?.remove("device_name")
        p?.remove("serial_number")
        return p
    }
}
