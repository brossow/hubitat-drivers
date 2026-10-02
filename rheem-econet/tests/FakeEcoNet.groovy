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
            return resp(200, [success: true, results: [locations: locations]])
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
