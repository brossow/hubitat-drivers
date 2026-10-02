package groovyx.net.http

// Stand-in for the class Hubitat's httpPost throws on a non-2xx response, so the
// drivers' `instanceof groovyx.net.http.HttpResponseException` compiles off the hub.
class HttpResponseException extends Exception {
    final int statusCode

    HttpResponseException(int statusCode) {
        super("status code: ${statusCode}".toString())
        this.statusCode = statusCode
    }

    int getStatusCode() { statusCode }
}
