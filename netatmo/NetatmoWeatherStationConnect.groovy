/*
 * Netatmo Weather Station Connect - Hubitat App
 * Version: 0.1.0
 *
 * Copyright 2026 Brent Rossow
 * SPDX-License-Identifier: Apache-2.0
 *
 * Connects Netatmo Weather Station devices to Hubitat.
 *
 * The parent app owns OAuth, Netatmo API calls, discovery, normalization,
 * child creation, manual refresh, and scheduled polling. Child drivers only
 * consume normalized data from this app.
 */

definition(
    name: "Netatmo Weather Station Connect",
    namespace: "brossow",
    author: "Brent Rossow",
    description: "Connect Netatmo Weather Stations to Hubitat",
    category: "Weather",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/netamo-icon-1.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/netamo-icon-1%402x.png",
    oauth: true,
    singleInstance: true,
    menu: "Integrations"
)

preferences {
    page(name: "mainPage", title: "Netatmo Weather Station Connect", install: true, uninstall: true)
}

mappings {
    path("/oauth/callback") { action: [GET: "oauthCallback"] }
}

private String appVersion() { return "0.1.0" }
private String netatmoApiBaseUrl() { return "https://api.netatmo.com" }
private String netatmoAuthorizePath() { return "/oauth2/authorize" }
private String netatmoTokenPath() { return "/oauth2/token" }
private Integer tokenRefreshBufferSeconds() { return 300 }

def installed() {
    log.info "Netatmo Weather Station Connect installed"
    initialize()
}

def updated() {
    log.info "Netatmo Weather Station Connect settings updated"
    initialize()
}

def uninstalled() {
    unschedule()
    log.info "Netatmo Weather Station Connect uninstalled"
}

def initialize() {
    unschedule()
    state.appVersion = appVersion()
    migrateNetatmoTokenState()
    ensureEndpointAccessToken()
    schedulePolling()
}

def mainPage() {
    migrateNetatmoTokenState()
    Boolean endpointOauthReady = ensureEndpointAccessToken()
    String authorizationUrl = endpointOauthReady && credentialsConfigured() ? buildAuthorizeUrl() : ""

    return dynamicPage(name: "mainPage", title: "Netatmo Weather Station Connect", install: true, uninstall: true) {
        section("Netatmo API Credentials") {
            paragraph "Create an application at https://dev.netatmo.com/ and enter its client credentials here."
            input name: "clientId",
                type: "text",
                title: "Client ID",
                required: true,
                submitOnChange: true
            input name: "clientSecret",
                type: "password",
                title: "Client Secret",
                required: true,
                submitOnChange: true
        }

        section("Authorization") {
            paragraph authenticationStatusText()
            if (!endpointOauthReady) {
                paragraph "Hubitat app OAuth is not enabled yet. Save this app code, open it in Apps Code, click OAuth, enable OAuth, then return here."
            } else if (credentialsConfigured()) {
                paragraph "Netatmo callback URL:\n${callbackUrl()}"
                paragraph authorizationLinkHtml(authorizationUrl, state.netatmoAuthenticated ? "Reauthorize Netatmo" : "Authorize Netatmo")
                input name: "clearAuthorization",
                    type: "button",
                    title: "Clear stored Netatmo tokens"
            } else {
                paragraph "Enter and save the Netatmo client credentials before authorizing."
            }
        }

        section("Diagnostics") {
            if (state.netatmoAuthenticated) {
                input name: "testStationsData",
                    type: "button",
                    title: "Test getstationsdata"
                input name: "inspectAvailableFields",
                    type: "button",
                    title: "Inspect available fields"
            } else {
                paragraph "Authorize Netatmo before running the API diagnostic."
            }
            paragraph diagnosticStatusText()
            if (state.lastFieldDiagnostic) {
                paragraph fieldDiagnosticDisplayHtml(state.lastFieldDiagnostic instanceof Map ? (Map)state.lastFieldDiagnostic : [:])
            }
        }

        section("Discovery") {
            if (!state.netatmoAuthenticated) {
                paragraph "Authorize Netatmo before discovering stations and modules."
            } else {
                input name: "refreshDiscovery",
                    type: "button",
                    title: "Refresh station discovery"
                paragraph discoveryStatusText()
                Map discovery = cachedDiscovery()
                if (discovery) {
                    input name: "selectedDeviceDnis",
                        type: "enum",
                        title: "Select Netatmo devices",
                        options: discoverySelectionOptions(discovery),
                        multiple: true,
                        required: false
                    paragraph discoveryDisplayHtml(discovery)
                } else {
                    paragraph "No discovered devices are cached yet. Run station discovery to populate this list."
                }
            }
        }

        section("Child Devices") {
            paragraph "Supported child devices: Base Station, Outdoor Module, Indoor Module, Rain Gauge, and Wind Gauge."
            if (!state.netatmoAuthenticated) {
                paragraph "Authorize Netatmo before creating or updating child devices."
            } else {
                input name: "syncLabels",
                    type: "bool",
                    title: "Sync child labels from Netatmo names",
                    defaultValue: false,
                    required: false
                input name: "syncSupportedDevices",
                    type: "button",
                    title: "Create/update selected supported devices"
                paragraph supportedDeviceSyncStatusText()
            }
        }

        section("Units") {
            input name: "temperatureUnitPreference",
                type: "enum",
                title: "Temperature",
                options: [
                    "location": "Hubitat location default",
                    "C": "Celsius",
                    "F": "Fahrenheit"
                ],
                defaultValue: "location",
                required: true
            input name: "pressureUnitPreference",
                type: "enum",
                title: "Pressure",
                options: [
                    "hpa": "hPa / mbar",
                    "inHg": "inHg"
                ],
                defaultValue: "hpa",
                required: true
            input name: "rainUnitPreference",
                type: "enum",
                title: "Rain",
                options: [
                    "mm": "mm",
                    "in": "inches"
                ],
                defaultValue: "mm",
                required: true
            input name: "windUnitPreference",
                type: "enum",
                title: "Wind speed",
                options: [
                    "kmh": "km/h",
                    "mph": "mph",
                    "ms": "m/s"
                ],
                defaultValue: defaultWindUnitPreference(),
                required: true
            paragraph "Unit preferences are applied by the parent app before child devices are updated."
        }

        section("Polling") {
            input name: "pollIntervalMinutes",
                type: "enum",
                title: "Poll Interval",
                options: pollIntervalOptions(),
                defaultValue: "5",
                required: true
            paragraph pollStatusText()
        }

        section("Logging") {
            input name: "debugLogging",
                type: "bool",
                title: "Enable debug logging",
                defaultValue: false,
                required: false
        }
    }
}

def appButtonHandler(String buttonName) {
    debugLog "Button pressed: ${buttonName}"

    if (buttonName == "testStationsData") {
        runStationsDataDiagnostic()
        return
    }

    if (buttonName == "inspectAvailableFields") {
        runFieldDiagnostic()
        return
    }

    if (buttonName == "refreshDiscovery") {
        runStationDiscovery()
        return
    }

    if (buttonName == "syncSupportedDevices") {
        syncSelectedSupportedDevices()
        return
    }

    if (buttonName == "clearAuthorization") {
        clearAuthState()
        state.lastDiagnosticStatus = "Authorization cleared."
        state.lastDiscoveryStatus = "Authorization cleared. Discovery cache was not refreshed."
        log.info "Netatmo authorization state cleared"
        return
    }

    log.warn "Unhandled button action: ${buttonName}"
}

String authenticationStatusText() {
    if (state.netatmoAuthenticated && isTokenValid()) {
        return "Authenticated. Access token appears valid until ${formatTimestamp(state.netatmoTokenExpiresAt)}."
    }

    if (state.netatmoAuthenticated && state.netatmoRefreshToken) {
        return "Authenticated, but the access token is expired or near expiry. It will be refreshed before the next API call."
    }

    return "Not authenticated."
}

String diagnosticStatusText() {
    return state.lastDiagnosticStatus ?: "No diagnostic has been run yet."
}

String discoveryStatusText() {
    if (state.lastDiscoveryStatus) {
        String timestamp = state.lastDiscoveryAt ? " Last refreshed ${formatTimestamp(state.lastDiscoveryAt)}." : ""
        return "${state.lastDiscoveryStatus}${timestamp}"
    }

    return "No discovery has been run yet."
}

String supportedDeviceSyncStatusText() {
    if (state.lastSupportedDeviceSyncStatus) {
        String timestamp = state.lastSupportedDeviceSyncAt ? " Last run ${formatTimestamp(state.lastSupportedDeviceSyncAt)}." : ""
        return "${state.lastSupportedDeviceSyncStatus}${timestamp}"
    }

    if (state.lastBaseStationSyncStatus) {
        String timestamp = state.lastBaseStationSyncAt ? " Last run ${formatTimestamp(state.lastBaseStationSyncAt)}." : ""
        return "${state.lastBaseStationSyncStatus}${timestamp}"
    }

    return "No supported child device sync has been run yet."
}

String pollStatusText() {
    String interval = settings.pollIntervalMinutes ?: "5"
    String configured = interval == "Disabled" ? "Polling is disabled." : "Polling every ${interval} minute(s)."
    if (state.lastPollStatus) {
        String timestamp = state.lastPollAt ? " Last poll ${formatTimestamp(state.lastPollAt)}." : ""
        String message = state.lastPollMessage ? " ${state.lastPollMessage}" : ""
        return "${configured} Last status: ${state.lastPollStatus}.${message}${timestamp}"
    }

    return "${configured} No scheduled poll has run yet."
}

Map pollIntervalOptions() {
    return [
        "Disabled": "Disabled",
        "1": "1 minute",
        "5": "5 minutes",
        "10": "10 minutes",
        "15": "15 minutes",
        "30": "30 minutes"
    ]
}

Boolean credentialsConfigured() {
    return !!(settings.clientId?.trim() && settings.clientSecret?.trim())
}

String buildAuthorizeUrl() {
    if (!credentialsConfigured()) {
        return ""
    }

    state.netatmoOAuthState = UUID.randomUUID().toString()

    Map query = [
        response_type: "code",
        client_id: settings.clientId?.trim(),
        redirect_uri: callbackUrl(),
        scope: "read_station",
        state: state.netatmoOAuthState
    ]

    String url = "${netatmoApiBaseUrl()}${netatmoAuthorizePath()}?${toQueryString(query)}"
    debugLog "Generated Netatmo authorization URL"
    return url
}

String authorizationLinkHtml(String authorizationUrl, String label) {
    return "<a href=\"${escapeHtml(authorizationUrl)}\" target=\"_blank\" rel=\"noopener noreferrer\">${escapeHtml(label)}</a>"
}

def oauthCallback() {
    debugLog "OAuth callback received with params: ${safeCallbackParams(params)}"

    if (params.error) {
        log.error "Netatmo authorization failed: ${params.error} ${params.error_description ?: ''}"
        state.netatmoAuthenticated = false
        return renderCallbackPage("Netatmo authorization failed", "Netatmo returned: ${params.error}")
    }

    if (!params.code) {
        log.error "Netatmo authorization callback did not include an authorization code"
        state.netatmoAuthenticated = false
        return renderCallbackPage("Netatmo authorization failed", "No authorization code was returned.")
    }

    if (!params.state || params.state != state.netatmoOAuthState) {
        log.error "Netatmo authorization state mismatch"
        state.netatmoAuthenticated = false
        return renderCallbackPage("Netatmo authorization failed", "The authorization state did not match. Please try again.")
    }

    if (exchangeCodeForTokens(params.code as String)) {
        state.netatmoAuthenticated = true
        state.lastDiagnosticStatus = "Authentication completed. Run the getstationsdata diagnostic from the app page."
        log.info "Netatmo authorization completed successfully"
        return renderCallbackPage("Netatmo authorization succeeded", "Authorization is complete.")
    }

    state.netatmoAuthenticated = false
    return renderCallbackPage("Netatmo authorization failed", "Token exchange failed. Check Hubitat logs for details.")
}

Boolean isTokenValid() {
    Long expiresAt = safeLong(state.netatmoTokenExpiresAt)
    if (!netatmoAccessToken() || !expiresAt) {
        return false
    }

    Long refreshAt = expiresAt - (tokenRefreshBufferSeconds() * 1000L)
    return now() < refreshAt
}

Boolean ensureValidToken() {
    if (isTokenValid()) {
        return true
    }

    if (!state.netatmoRefreshToken) {
        log.error "Netatmo token refresh skipped: no refresh token is stored"
        state.netatmoAuthenticated = false
        return false
    }

    return refreshAccessToken()
}

Boolean refreshAccessToken() {
    if (!credentialsConfigured()) {
        log.error "Netatmo token refresh skipped: client credentials are not configured"
        state.netatmoAuthenticated = false
        return false
    }

    Map body = [
        grant_type: "refresh_token",
        refresh_token: state.netatmoRefreshToken,
        client_id: settings.clientId?.trim(),
        client_secret: settings.clientSecret?.trim()
    ]

    try {
        Boolean tokensStored = false
        httpPost(tokenRequestParams(body)) { resp ->
            Map tokenData = responseDataAsMap(resp?.data)
            tokensStored = storeTokenData(tokenData)
        }

        if (tokensStored) {
            state.netatmoAuthenticated = true
            debugLog "Netatmo access token refreshed; expires at ${formatTimestamp(state.netatmoTokenExpiresAt)}"
            return true
        }

        log.error "Netatmo token refresh failed: token response was missing required fields"
    } catch (Exception e) {
        log.error "Netatmo token refresh failed: ${exceptionSummary(e)}"
        debugLog "Netatmo token refresh exception details: ${e}"
    }

    state.netatmoAuthenticated = false
    return false
}

Map apiRequest(String method, String path, Map query = [:], Map body = null) {
    return apiRequestInternal(method, path, query, body, true)
}

private Map apiRequestInternal(String method, String path, Map query = [:], Map body = null, Boolean allowRetry) {
    String verb = (method ?: "GET").toUpperCase()

    if (!ensureValidToken()) {
        log.error "Netatmo API request skipped: authentication is not valid"
        return [success: false, status: null, data: null, error: "Authentication is not valid"]
    }

    Map requestParams = [
        uri: buildApiUrl(path),
        query: query ?: [:],
        headers: ["Authorization": "Bearer ${netatmoAccessToken()}"],
        contentType: "application/json"
    ]

    if (body != null) {
        requestParams.body = body
        requestParams.requestContentType = "application/json"
    }

    try {
        Map result = [success: false, status: null, data: null, error: null]

        if (verb == "GET") {
            httpGet(requestParams) { resp ->
                result = [success: successStatus(resp?.status), status: resp?.status, data: resp?.data, error: null]
            }
        } else if (verb == "POST") {
            httpPost(requestParams) { resp ->
                result = [success: successStatus(resp?.status), status: resp?.status, data: resp?.data, error: null]
            }
        } else {
            log.error "Unsupported Netatmo API method: ${verb}"
            return [success: false, status: null, data: null, error: "Unsupported method: ${verb}"]
        }

        if (result.success) {
            debugLog "Netatmo API ${verb} ${path} succeeded with status ${result.status}"
        } else {
            log.warn "Netatmo API ${verb} ${path} returned status ${result.status}"
        }
        return result
    } catch (Exception e) {
        Integer status = responseStatusFromException(e)
        if (status == 401 && allowRetry) {
            log.warn "Netatmo API request returned 401; refreshing token and retrying once"
            if (refreshAccessToken()) {
                return apiRequestInternal(method, path, query, body, false)
            }
            log.error "Netatmo API retry skipped: token refresh after 401 failed"
            return [success: false, status: 401, data: null, error: "Unauthorized and token refresh failed"]
        }

        log.error "Netatmo API ${verb} ${path} failed: ${exceptionSummary(e)}"
        debugLog "Netatmo API exception details: ${e}"
        return [success: false, status: status, data: null, error: exceptionSummary(e)]
    }
}

Boolean exchangeCodeForTokens(String code) {
    if (!credentialsConfigured()) {
        log.error "Netatmo token exchange skipped: client credentials are not configured"
        return false
    }

    Map body = [
        grant_type: "authorization_code",
        client_id: settings.clientId?.trim(),
        client_secret: settings.clientSecret?.trim(),
        code: code,
        redirect_uri: callbackUrl(),
        scope: "read_station"
    ]

    try {
        Boolean tokensStored = false
        httpPost(tokenRequestParams(body)) { resp ->
            Map tokenData = responseDataAsMap(resp?.data)
            tokensStored = storeTokenData(tokenData)
        }

        if (tokensStored) {
            debugLog "Netatmo token exchange succeeded; expires at ${formatTimestamp(state.netatmoTokenExpiresAt)}"
            return true
        }

        log.error "Netatmo token exchange failed: token response was missing required fields"
    } catch (Exception e) {
        log.error "Netatmo token exchange failed: ${exceptionSummary(e)}"
        debugLog "Netatmo token exchange exception details: ${e}"
    }

    return false
}

void clearAuthState() {
    state.remove("netatmoAccessToken")
    state.remove("netatmoRefreshToken")
    state.remove("netatmoTokenExpiresAt")
    state.remove("netatmoOAuthState")
    state.remove("refreshToken")
    state.remove("tokenExpiresAt")
    state.remove("oauthState")
    state.remove("authenticated")
    state.netatmoAuthenticated = false
}

void runStationsDataDiagnostic() {
    Map result = apiRequest("GET", "/api/getstationsdata")

    if (!result.success) {
        String message = "getstationsdata failed${result.status ? ' with HTTP ' + result.status : ''}: ${result.error ?: 'unknown error'}"
        state.lastDiagnosticStatus = message
        log.error "Netatmo diagnostic ${message}"
        return
    }

    Map summary = summarizeStationsData(result.data)
    state.lastDiagnosticStatus = "getstationsdata succeeded: ${summary.stationCount} station(s), ${summary.moduleCount} module(s)."
    log.info "Netatmo diagnostic succeeded: ${summary.stationCount} station(s), ${summary.moduleCount} module(s)"
    debugLog "Netatmo diagnostic summary: ${summary}"
}

void runFieldDiagnostic() {
    Map result = apiRequest("GET", "/api/getstationsdata")

    if (!result.success) {
        String message = "Field diagnostic failed${result.status ? ' with HTTP ' + result.status : ''}: ${result.error ?: 'unknown error'}"
        state.lastDiagnosticStatus = message
        log.error "Netatmo ${message}"
        return
    }

    Map normalized = normalizeStationData(result.data)
    state.lastDiscovery = normalized
    state.lastDiscoveryAt = now()
    state.lastFieldDiagnostic = buildFieldDiagnostic(result.data, normalized)
    state.lastFieldDiagnosticAt = now()
    Integer deviceCount = state.lastFieldDiagnostic?.devices instanceof List ? state.lastFieldDiagnostic.devices.size() : 0
    state.lastDiagnosticStatus = "Field diagnostic captured ${deviceCount} discovered device(s)."
    log.info "Netatmo field diagnostic captured ${deviceCount} discovered device(s)"
}

void runStationDiscovery() {
    Map result = apiRequest("GET", "/api/getstationsdata")

    if (!result.success) {
        String message = "Discovery failed${result.status ? ' with HTTP ' + result.status : ''}: ${result.error ?: 'unknown error'}"
        state.lastDiscoveryStatus = message
        log.error "Netatmo ${message}"
        return
    }

    Map discovery = normalizeStationData(result.data)
    state.lastDiscovery = discovery
    state.lastDiscoveryAt = now()

    if (discovery) {
        state.lastDiscoveryStatus = "Discovery found ${discovery.size()} supported or visible device(s)."
        log.info "Netatmo discovery found ${discovery.size()} device(s)"
    } else {
        state.lastDiscoveryStatus = "Discovery succeeded, but no stations or modules were found."
        log.warn "Netatmo discovery returned no devices"
    }
}

void syncSelectedSupportedDevices() {
    Map normalized = fetchAndNormalizeStations()
    if (normalized == null) {
        state.lastSupportedDeviceSyncStatus = "Supported device sync failed because station data could not be loaded."
        state.lastSupportedDeviceSyncAt = now()
        return
    }

    Map summary = createSelectedSupportedChildren(normalized)
    Integer updated = updateSelectedSupportedDevices(normalized)
    state.lastSupportedDeviceSyncAt = now()
    state.lastSupportedDeviceSyncStatus = "Supported device sync complete: ${summary.created} created, ${summary.existing} already existed, ${updated} updated."
    log.info "Netatmo supported device sync complete: ${summary.created} created, ${summary.existing} existing, ${updated} updated"
}

void poll() {
    if (state.netatmoAuthenticated != true) {
        state.lastPollAt = now()
        state.lastPollStatus = "Skipped"
        state.lastPollMessage = "Netatmo is not authenticated."
        log.warn "Netatmo poll skipped: app is not authenticated"
        return
    }

    if (!selectedDeviceDniList()) {
        state.lastPollAt = now()
        state.lastPollStatus = "Skipped"
        state.lastPollMessage = "No selected devices."
        debugLog "Netatmo poll skipped: no selected devices"
        return
    }

    Map normalized = fetchAndNormalizeStations()
    if (normalized == null) {
        state.lastPollAt = now()
        state.lastPollStatus = "Error"
        state.lastPollMessage = "Station data could not be loaded."
        log.warn "Netatmo poll failed: station data could not be loaded"
        return
    }

    Integer updated = updateExistingSelectedSupportedDevices(normalized)
    state.lastPollAt = now()
    state.lastPollStatus = "OK"
    state.lastPollMessage = "Updated ${updated} existing selected child device(s)."
    debugLog "Netatmo poll complete: updated ${updated} child device(s)"
}

Integer updateExistingSelectedSupportedDevices(Map normalizedDevices = null) {
    Map normalized = normalizedDevices ?: cachedDiscovery()
    Integer updated = 0

    selectedDeviceDniList().each { dni ->
        Map normalizedDevice = normalized[dni] instanceof Map ? (Map)normalized[dni] : null
        if (!normalizedDevice) {
            debugLog "Netatmo poll skipped selected device ${dni}: not found in latest station data"
            return
        }

        if (!isSupportedChildClass(normalizedDevice.deviceClass as String)) {
            debugLog "Netatmo poll skipped selected device ${dni}: unsupported class ${normalizedDevice.deviceClass}"
            return
        }

        if (!getChildDevice(dni as String)) {
            debugLog "Netatmo poll skipped selected device ${dni}: child does not exist; run manual sync to create it"
            return
        }

        if (updateChildFromNormalizedData(dni as String, normalizedDevice)) {
            updated += 1
        }
    }

    return updated
}

Map fetchAndNormalizeStations() {
    Map result = apiRequest("GET", "/api/getstationsdata")

    if (!result.success) {
        String message = "Station load failed${result.status ? ' with HTTP ' + result.status : ''}: ${result.error ?: 'unknown error'}"
        log.error "Netatmo ${message}"
        return null
    }

    Map normalized = normalizeStationData(result.data)
    state.lastDiscovery = normalized
    state.lastDiscoveryAt = now()
    state.lastDiscoveryStatus = normalized ? "Discovery found ${normalized.size()} supported or visible device(s)." : "Discovery succeeded, but no stations or modules were found."
    return normalized
}

Map createSelectedSupportedChildren(Map normalizedDevices = null) {
    Map normalized = normalizedDevices ?: cachedDiscovery()
    List selectedDnis = selectedDeviceDniList()
    Integer created = 0
    Integer existing = 0

    if (!selectedDnis) {
        log.warn "Netatmo supported device sync skipped: no selected devices"
        return [created: created, existing: existing]
    }

    selectedDnis.each { dni ->
        Map normalizedDevice = normalized[dni] instanceof Map ? (Map)normalized[dni] : null
        if (!normalizedDevice) {
            log.warn "Selected Netatmo device ${dni} was not found in the latest discovery data"
            return
        }

        if (!isSupportedChildClass(normalizedDevice.deviceClass as String)) {
            log.warn "Selected Netatmo device ${dni} is ${normalizedDevice.deviceClass}; supported child classes are base, outdoor, indoor, rain, and wind"
            return
        }

        if (getChildDevice(dni as String)) {
            maybeSyncChildLabel(dni as String, normalizedDevice)
            existing += 1
            return
        }

        if (createChildDeviceForNormalizedDevice(normalizedDevice)) {
            created += 1
        }
    }

    return [created: created, existing: existing]
}

Boolean createChildDeviceForNormalizedDevice(Map deviceData) {
    if (!deviceData?.dni) {
        log.warn "Netatmo child creation skipped: normalized device data did not include a DNI"
        return false
    }

    if (!isSupportedChildClass(deviceData.deviceClass as String)) {
        log.warn "Netatmo child creation skipped for ${deviceData.dni}: unsupported device class ${deviceData.deviceClass}"
        return false
    }

    String dni = deviceData.dni as String
    String label = childLabelForDevice(deviceData)
    String driverName = driverNameForDeviceClass(deviceData.deviceClass as String)
    if (!driverName) {
        log.error "Netatmo child creation skipped for ${dni}: no driver mapping exists for device class ${deviceData.deviceClass}"
        return false
    }

    try {
        addChildDevice("brossow", driverName, dni, [
            name: label,
            label: label,
            isComponent: false
        ])
        log.info "Created ${driverName} child ${dni} (${label})"
        return true
    } catch (Exception e) {
        log.error "Failed to create ${driverName} child ${dni}: ${exceptionSummary(e)}"
        debugLog "Netatmo child creation exception details: ${e}"
        return false
    }
}

Integer updateSelectedSupportedDevices(Map normalizedDevices = null) {
    Map normalized = normalizedDevices ?: cachedDiscovery()
    Integer updated = 0

    selectedDeviceDniList().each { dni ->
        Map normalizedDevice = normalized[dni] instanceof Map ? (Map)normalized[dni] : null
        if (!normalizedDevice) {
            log.warn "Selected Netatmo device ${dni} was not found in the latest discovery data"
            return
        }

        if (!isSupportedChildClass(normalizedDevice.deviceClass as String)) {
            log.warn "Selected Netatmo device ${dni} is ${normalizedDevice.deviceClass}; supported child classes are base, outdoor, indoor, rain, and wind"
            return
        }

        if (updateChildFromNormalizedData(dni as String, normalizedDevice)) {
            updated += 1
        }
    }

    return updated
}

Boolean updateChildFromNormalizedData(String dni, Map normalizedDevice) {
    def child = getChildDevice(dni)
    if (!child) {
        log.warn "Cannot update Netatmo child ${dni}: child device does not exist"
        return false
    }

    try {
        child.updatedFromParent(normalizedDevice)
        debugLog "Updated Netatmo child ${dni} from normalized data"
        return true
    } catch (Exception e) {
        log.error "Failed to update Netatmo child ${dni}: ${exceptionSummary(e)}"
        debugLog "Netatmo child update exception details: ${e}"
        return false
    }
}

void refreshChild(String dni) {
    if (!dni) {
        log.warn "Netatmo child refresh skipped: DNI was not provided"
        return
    }

    Map normalized = fetchAndNormalizeStations()
    if (normalized == null) {
        log.error "Netatmo child refresh failed for ${dni}: station data could not be loaded"
        return
    }

    Map normalizedDevice = normalized[dni] instanceof Map ? (Map)normalized[dni] : null
    if (!normalizedDevice) {
        log.warn "Netatmo child refresh could not find ${dni} in latest station data"
        return
    }

    if (!isSupportedChildClass(normalizedDevice.deviceClass as String)) {
        log.warn "Netatmo child refresh skipped for ${dni}: unsupported device class ${normalizedDevice.deviceClass}"
        return
    }

    updateChildFromNormalizedData(dni, normalizedDevice)
}

Map normalizeStationData(rawResponse) {
    Map payload = responseDataAsMap(rawResponse)
    Map body = payload.body instanceof Map ? (Map)payload.body : [:]
    List stations = body.devices instanceof List ? (List)body.devices : []
    Map normalized = [:]

    if (!stations) {
        debugLog "Netatmo normalizeStationData: no devices array found in response"
        return normalized
    }

    stations.each { station ->
        if (!(station instanceof Map)) {
            log.warn "Netatmo discovery skipped malformed station entry"
            return
        }

        Map stationMap = (Map)station
        String stationId = stringValue(stationMap._id)
        if (!stationId) {
            log.warn "Netatmo discovery skipped station without _id"
            return
        }

        String stationName = stringValue(stationMap.station_name) ?: stringValue(stationMap.module_name) ?: stringValue(stationMap.name) ?: "Netatmo Station ${stationId}"
        String baseType = stringValue(stationMap.type) ?: "NAMain"
        Map baseData = normalizeDeviceEntry(stationMap, stationId, stationId, baseType, stationName, stationName)
        normalized[baseData.dni] = baseData

        List modules = stationMap.modules instanceof List ? (List)stationMap.modules : []
        modules.each { module ->
            if (!(module instanceof Map)) {
                log.warn "Netatmo discovery skipped malformed module entry for station ${stationId}"
                return
            }

            Map moduleMap = (Map)module
            String moduleId = stringValue(moduleMap._id)
            if (!moduleId) {
                log.warn "Netatmo discovery skipped module without _id for station ${stationId}"
                return
            }

            String moduleType = stringValue(moduleMap.type) ?: "unknown"
            String moduleName = stringValue(moduleMap.module_name) ?: stringValue(moduleMap.name) ?: "${deviceClassForModuleType(moduleType).capitalize()} Module ${moduleId}"
            Map moduleData = normalizeDeviceEntry(moduleMap, stationId, moduleId, moduleType, stationName, moduleName)
            normalized[moduleData.dni] = moduleData
        }
    }

    return normalized
}

private Map normalizeDeviceEntry(Map source, String stationId, String moduleId, String moduleType, String stationName, String moduleName) {
    String deviceClass = deviceClassForModuleType(moduleType)
    if (deviceClass == "unknown") {
        log.warn "Netatmo discovery found unsupported module type ${moduleType} for ${stationId}/${moduleId}"
    }

    String dni = buildDni(stationId, moduleId)
    return [
        dni: dni,
        stationId: stationId,
        moduleId: moduleId,
        moduleType: moduleType,
        deviceClass: deviceClass,
        stationName: stationName,
        moduleName: moduleName,
        displayName: displayNameForDevice(stationName, moduleName, deviceClass),
        reachable: reachableFromSource(source),
        lastSeen: lastSeenFromSource(source),
        measurementTime: measurementTimeFromSource(source),
        units: currentUnitLabels(),
        dashboard: normalizeDashboard(source.dashboard_data instanceof Map ? (Map)source.dashboard_data : [:]),
        metadata: normalizeMetadata(source)
    ]
}

private Map normalizeDashboard(Map dashboard) {
    return [
        temperature: convertTemperature(numberValue(dashboard.Temperature)),
        humidity: numberValue(dashboard.Humidity),
        pressure: convertPressure(numberValue(dashboard.Pressure)),
        co2: numberValue(dashboard.CO2),
        noise: numberValue(dashboard.Noise),
        rain: convertRain(numberValue(dashboard.Rain)),
        rainLastHour: convertRain(numberValue(dashboard.sum_rain_1)),
        rainToday: convertRain(numberValue(dashboard.sum_rain_24)),
        windStrength: convertWind(numberValue(dashboard.WindStrength)),
        windAngle: numberValue(dashboard.WindAngle),
        gustStrength: convertWind(numberValue(dashboard.GustStrength)),
        gustAngle: numberValue(dashboard.GustAngle),
        maxWindStrength: convertWind(numberValue(dashboard.max_wind_str)),
        maxWindAngle: numberValue(dashboard.max_wind_angle),
        dateMaxWindStrength: safeEpochSecondsAsString(dashboard.date_max_wind_str),
        tempTrend: stringValue(dashboard.temp_trend),
        pressureTrend: stringValue(dashboard.pressure_trend)
    ]
}

private Map normalizeMetadata(Map source) {
    return [
        batteryPercent: numberValue(source.battery_percent),
        rfStatus: numberValue(source.rf_status),
        wifiStatus: numberValue(source.wifi_status)
    ]
}

private Map currentUnitLabels() {
    String temp = effectiveTemperatureUnitPreference()
    String pressure = effectivePressureUnitPreference()
    String rain = effectiveRainUnitPreference()
    String wind = effectiveWindUnitPreference()

    return [
        temperature: temp == "F" ? "F" : "C",
        pressure: pressure == "inHg" ? "inHg" : "hPa",
        rain: rain == "in" ? "in" : "mm",
        wind: windUnitLabel(wind)
    ]
}

private String unitPreferenceSummaryText() {
    Map units = currentUnitLabels()
    String tempPreference = settings.temperatureUnitPreference ?: "location"
    String tempText = tempPreference == "location" ? "Hubitat location default (${units.temperature})" : units.temperature
    return "temperature ${tempText}; pressure ${units.pressure}; rain ${units.rain}; wind ${units.wind}"
}

private String effectiveTemperatureUnitPreference() {
    String preference = settings.temperatureUnitPreference ?: "location"
    if (preference == "F" || preference == "C") {
        return preference
    }

    return location?.temperatureScale == "F" ? "F" : "C"
}

private String effectivePressureUnitPreference() {
    return settings.pressureUnitPreference == "inHg" ? "inHg" : "hpa"
}

private String effectiveRainUnitPreference() {
    return settings.rainUnitPreference == "in" ? "in" : "mm"
}

private String effectiveWindUnitPreference() {
    String preference = settings.windUnitPreference
    return preference in ["kmh", "mph", "ms"] ? preference : defaultWindUnitPreference()
}

private String defaultWindUnitPreference() {
    return location?.temperatureScale == "F" ? "mph" : "kmh"
}

private String windUnitLabel(String unitPreference) {
    switch (unitPreference) {
        case "mph":
            return "mph"
        case "ms":
            return "m/s"
        default:
            return "km/h"
    }
}

// Netatmo getstationsdata source units are treated as C, hPa/mbar, mm, and km/h.
private BigDecimal convertTemperature(BigDecimal celsiusValue) {
    if (celsiusValue == null) {
        return null
    }

    BigDecimal value = celsiusValue
    if (effectiveTemperatureUnitPreference() == "F") {
        value = (celsiusValue * new BigDecimal("9") / new BigDecimal("5")) + new BigDecimal("32")
    }
    return roundNumber(value, 1)
}

private BigDecimal convertPressure(BigDecimal hpaValue) {
    if (hpaValue == null) {
        return null
    }

    if (effectivePressureUnitPreference() == "inHg") {
        return roundNumber(hpaValue * new BigDecimal("0.0295299830714"), 2)
    }
    return roundNumber(hpaValue, 1)
}

private BigDecimal convertRain(BigDecimal mmValue) {
    if (mmValue == null) {
        return null
    }

    if (effectiveRainUnitPreference() == "in") {
        BigDecimal inches = mmValue * new BigDecimal("0.0393700787")
        return roundNumber(inches, inches > BigDecimal.ZERO && inches < new BigDecimal("0.1") ? 3 : 2)
    }
    return roundNumber(mmValue, mmValue > BigDecimal.ZERO && mmValue < BigDecimal.ONE ? 2 : 1)
}

private BigDecimal convertWind(BigDecimal kmhValue) {
    if (kmhValue == null) {
        return null
    }

    String unitPreference = effectiveWindUnitPreference()
    if (unitPreference == "mph") {
        return roundNumber(kmhValue * new BigDecimal("0.6213711922"), 1)
    }
    if (unitPreference == "ms") {
        return roundNumber(kmhValue * new BigDecimal("0.2777777778"), 1)
    }
    return roundNumber(kmhValue, 1)
}

private BigDecimal roundNumber(BigDecimal value, Integer scale) {
    return value == null ? null : value.setScale(scale, BigDecimal.ROUND_HALF_UP)
}

private Map cachedDiscovery() {
    return state.lastDiscovery instanceof Map ? (Map)state.lastDiscovery : [:]
}

private Map discoverySelectionOptions(Map discovery) {
    Map options = [:]
    discovery.each { dni, device ->
        if (device instanceof Map) {
            options[dni] = "${device.displayName} (${device.deviceClass}, ${device.moduleType})"
        }
    }
    return options.sort { a, b -> a.value <=> b.value }
}

private Map buildFieldDiagnostic(Object rawResponse, Map normalized) {
    Map payload = responseDataAsMap(rawResponse)
    Map body = payload.body instanceof Map ? (Map)payload.body : [:]
    List stations = body.devices instanceof List ? (List)body.devices : []
    List devices = []

    stations.each { station ->
        if (!(station instanceof Map)) {
            return
        }

        Map stationMap = (Map)station
        String stationId = stringValue(stationMap._id)
        if (stationId) {
            devices << diagnosticForRawDevice(stationMap, normalized, stationId, stationId, stringValue(stationMap.type) ?: "NAMain")
        }

        List modules = stationMap.modules instanceof List ? (List)stationMap.modules : []
        modules.each { module ->
            if (!(module instanceof Map)) {
                return
            }

            Map moduleMap = (Map)module
            String moduleId = stringValue(moduleMap._id)
            String moduleType = stringValue(moduleMap.type) ?: "unknown"
            if (stationId && moduleId) {
                devices << diagnosticForRawDevice(moduleMap, normalized, stationId, moduleId, moduleType)
            }
        }
    }

    return [
        generatedAt: now(),
        unitPreferences: unitPreferenceSummaryText(),
        devices: devices
    ]
}

private Map diagnosticForRawDevice(Map rawDevice, Map normalized, String stationId, String moduleId, String moduleType) {
    String dni = buildDni(stationId, moduleId)
    Map normalizedDevice = normalized[dni] instanceof Map ? (Map)normalized[dni] : [:]
    Map dashboard = normalizedDevice.dashboard instanceof Map ? (Map)normalizedDevice.dashboard : [:]
    Map metadata = normalizedDevice.metadata instanceof Map ? (Map)normalizedDevice.metadata : [:]
    Map units = normalizedDevice.units instanceof Map ? (Map)normalizedDevice.units : currentUnitLabels()
    Map rawDashboard = rawDevice.dashboard_data instanceof Map ? (Map)rawDevice.dashboard_data : [:]
    Map expected = expectedFieldsForDeviceClass(normalizedDevice.deviceClass as String)

    return [
        displayName: normalizedDevice.displayName ?: stringValue(rawDevice.module_name) ?: stringValue(rawDevice.station_name) ?: dni,
        dni: dni,
        moduleType: moduleType,
        deviceClass: normalizedDevice.deviceClass ?: deviceClassForModuleType(moduleType),
        reachable: normalizedDevice.reachable,
        lastSeen: normalizedDevice.lastSeen,
        measurementTime: normalizedDevice.measurementTime,
        units: units,
        rawDashboardKeys: rawDashboard.keySet().collect { it as String }.sort(),
        normalizedDashboardPresent: presentKeys(dashboard),
        normalizedDashboardValues: diagnosticDashboardValues(dashboard, units),
        normalizedMetadataPresent: presentKeys(metadata),
        expectedDashboardFields: expected.dashboard,
        expectedMetadataFields: expected.metadata,
        missingDashboardFields: missingKeys(dashboard, expected.dashboard),
        missingMetadataFields: missingKeys(metadata, expected.metadata)
    ]
}

private Map expectedFieldsForDeviceClass(String deviceClass) {
    switch (deviceClass) {
        case "base":
            return [dashboard: ["temperature", "humidity", "pressure", "co2", "noise", "tempTrend", "pressureTrend"], metadata: ["wifiStatus"]]
        case "outdoor":
            return [dashboard: ["temperature", "humidity", "tempTrend"], metadata: ["batteryPercent", "rfStatus"]]
        case "indoor":
            return [dashboard: ["temperature", "humidity", "co2", "tempTrend"], metadata: ["batteryPercent", "rfStatus"]]
        case "rain":
            return [dashboard: ["rain", "rainLastHour", "rainToday"], metadata: ["batteryPercent", "rfStatus"]]
        case "wind":
            return [dashboard: ["windStrength", "windAngle", "gustStrength", "gustAngle", "maxWindStrength", "maxWindAngle", "dateMaxWindStrength"], metadata: ["batteryPercent", "rfStatus"]]
        default:
            return [dashboard: [], metadata: []]
    }
}

private List presentKeys(Map source) {
    return (source ?: [:]).findAll { key, value -> value != null }.keySet().collect { it as String }.sort()
}

private List diagnosticDashboardValues(Map dashboard, Map units) {
    List values = []
    Map unitByField = [
        temperature: units.temperature,
        pressure: units.pressure,
        rain: units.rain,
        rainLastHour: units.rain,
        rainToday: units.rain,
        windStrength: units.wind,
        gustStrength: units.wind,
        maxWindStrength: units.wind,
        humidity: "%",
        co2: "ppm",
        noise: "dB",
        windAngle: "degrees",
        gustAngle: "degrees",
        maxWindAngle: "degrees"
    ]

    (dashboard ?: [:]).findAll { key, value -> value != null }.keySet().collect { it as String }.sort().each { key ->
        String unit = unitByField[key]
        values << "${key}=${dashboard[key]}${unit ? ' ' + unit : ''}"
    }
    return values
}

private List missingKeys(Map source, List expectedKeys) {
    return (expectedKeys ?: []).findAll { key -> !source.containsKey(key) || source[key] == null }
}

private String discoveryDisplayHtml(Map discovery) {
    if (!discovery) {
        return "No discovered devices."
    }

    Map grouped = [:]
    discovery.each { dni, device ->
        if (device instanceof Map) {
            String stationName = device.stationName ?: "Unknown Station"
            if (!(grouped[stationName] instanceof List)) {
                grouped[stationName] = []
            }
            grouped[stationName] << device
        }
    }

    String html = ""
    grouped.keySet().sort().each { stationName ->
        html += "<p><strong>${escapeHtml(stationName as String)}</strong></p><ul>"
        grouped[stationName].sort { a, b -> (a.displayName ?: "") <=> (b.displayName ?: "") }.each { device ->
            html += "<li>${escapeHtml(device.displayName as String)} - ${escapeHtml(device.deviceClass as String)} / ${escapeHtml(device.moduleType as String)} - <code>${escapeHtml(device.dni as String)}</code></li>"
        }
        html += "</ul>"
    }
    return html
}

private String fieldDiagnosticDisplayHtml(Map diagnostic) {
    List devices = diagnostic.devices instanceof List ? (List)diagnostic.devices : []
    if (!devices) {
        return "No field diagnostic data is available."
    }

    String generated = diagnostic.generatedAt ? formatTimestamp(diagnostic.generatedAt) : "unknown"
    String html = "<p><strong>Available field diagnostic</strong><br>Generated: ${escapeHtml(generated)}</p>"
    html += "<p>Active unit preferences: ${escapeHtml(valueText(diagnostic.unitPreferences))}</p>"
    html += "<p>lastSeen is module communication time. measurementTime is the timestamp of the latest dashboard reading.</p>"
    devices.sort { a, b -> (a.displayName ?: "") <=> (b.displayName ?: "") }.each { device ->
        html += "<p><strong>${escapeHtml(device.displayName as String)}</strong><br>"
        html += "DNI: <code>${escapeHtml(device.dni as String)}</code><br>"
        html += "Type/Class: ${escapeHtml(device.moduleType as String)} / ${escapeHtml(device.deviceClass as String)}<br>"
        html += "Reachable: ${escapeHtml(valueText(device.reachable))}<br>"
        html += "Last seen: ${escapeHtml(formatEpochSecondsForDisplay(device.lastSeen))}<br>"
        html += "Measurement time: ${escapeHtml(valueText(device.measurementTime))}<br>"
        html += "Raw dashboard_data keys: ${escapeHtml(listText(device.rawDashboardKeys))}<br>"
        html += "Normalized dashboard values present: ${escapeHtml(listText(device.normalizedDashboardPresent))}<br>"
        html += "Normalized dashboard values: ${escapeHtml(listText(device.normalizedDashboardValues))}<br>"
        html += "Normalized metadata values present: ${escapeHtml(listText(device.normalizedMetadataPresent))}<br>"
        html += "Expected dashboard fields: ${escapeHtml(listText(device.expectedDashboardFields))}<br>"
        html += "Expected metadata fields: ${escapeHtml(listText(device.expectedMetadataFields))}<br>"
        html += "Missing/null dashboard fields: ${escapeHtml(listText(device.missingDashboardFields))}<br>"
        html += "Missing/null metadata fields: ${escapeHtml(listText(device.missingMetadataFields))}</p>"
    }
    return html
}

private String listText(Object value) {
    if (value instanceof List) {
        return value ? value.join(", ") : "none"
    }
    return value == null ? "none" : value as String
}

private String valueText(Object value) {
    return value == null ? "unknown" : value as String
}

private String formatEpochSecondsForDisplay(Object value) {
    Long seconds = safeLong(value)
    if (seconds == null) {
        return "unknown"
    }

    try {
        return new Date(seconds * 1000L).format("yyyy-MM-dd HH:mm:ss z", location?.timeZone ?: TimeZone.getDefault())
    } catch (Exception e) {
        return value as String
    }
}

private List selectedDeviceDniList() {
    def selected = settings.selectedDeviceDnis
    if (selected == null) {
        return []
    }

    if (selected instanceof List) {
        return selected.findAll { it != null }.collect { it as String }
    }

    return [selected as String]
}

private String childLabelForDevice(Map deviceData) {
    String fallback = driverNameForDeviceClass(deviceData.deviceClass as String) ?: "Netatmo Device"
    return stringValue(deviceData.displayName) ?: stringValue(deviceData.moduleName) ?: stringValue(deviceData.stationName) ?: fallback
}

private void maybeSyncChildLabel(String dni, Map deviceData) {
    if (settings.syncLabels != true) {
        return
    }

    def child = getChildDevice(dni)
    if (!child) {
        return
    }

    String label = childLabelForDevice(deviceData)
    try {
        child.setLabel(label)
        debugLog "Synced Netatmo child label for ${dni} to ${label}"
    } catch (Exception e) {
        log.warn "Could not sync Netatmo child label for ${dni}: ${exceptionSummary(e)}"
    }
}

private Boolean isSupportedChildClass(String deviceClass) {
    return deviceClass in ["base", "outdoor", "indoor", "rain", "wind"]
}

private String driverNameForDeviceClass(String deviceClass) {
    switch (deviceClass) {
        case "base":
            return "Netatmo Weather Base Station"
        case "outdoor":
            return "Netatmo Weather Outdoor Module"
        case "indoor":
            return "Netatmo Weather Indoor Module"
        case "rain":
            return "Netatmo Weather Rain Gauge"
        case "wind":
            return "Netatmo Weather Wind Gauge"
        default:
            return null
    }
}

private Map summarizeStationsData(Object data) {
    Map payload = data instanceof Map ? (Map)data : [:]
    Map body = payload.body instanceof Map ? (Map)payload.body : [:]
    List devices = body.devices instanceof List ? (List)body.devices : []

    Integer moduleCount = 0
    devices.each { device ->
        if (device instanceof Map && device.modules instanceof List) {
            moduleCount += device.modules.size()
        }
    }

    return [stationCount: devices.size(), moduleCount: moduleCount]
}

private Map tokenRequestParams(Map body) {
    return [
        uri: "${netatmoApiBaseUrl()}${netatmoTokenPath()}",
        requestContentType: "application/x-www-form-urlencoded",
        contentType: "application/json",
        body: body
    ]
}

private Boolean storeTokenData(Map tokenData) {
    if (!tokenData?.access_token || !tokenData?.refresh_token) {
        log.error "Netatmo token response did not contain both access_token and refresh_token"
        return false
    }

    Integer expiresIn = safeInteger(tokenData.expires_in) ?: 0
    if (expiresIn <= 0) {
        log.warn "Netatmo token response did not include a valid expires_in value; using 3600 seconds"
        expiresIn = 3600
    }

    state.netatmoAccessToken = tokenData.access_token
    state.netatmoRefreshToken = tokenData.refresh_token
    state.netatmoTokenExpiresAt = now() + (expiresIn * 1000L)
    state.netatmoAuthenticated = true
    return true
}

private String netatmoAccessToken() {
    return state.netatmoAccessToken as String
}

private void migrateNetatmoTokenState() {
    if (!state.netatmoAccessToken && state.accessToken && state.hubitatEndpointAccessToken && state.accessToken != state.hubitatEndpointAccessToken) {
        state.netatmoAccessToken = state.accessToken
        debugLog "Migrated Netatmo access token away from Hubitat endpoint token state"
    }

    if (!state.netatmoRefreshToken && state.refreshToken) {
        state.netatmoRefreshToken = state.refreshToken
        state.remove("refreshToken")
        debugLog "Migrated Netatmo refresh token to netatmo-specific state"
    }

    if (!state.netatmoTokenExpiresAt && state.tokenExpiresAt) {
        state.netatmoTokenExpiresAt = state.tokenExpiresAt
        state.remove("tokenExpiresAt")
        debugLog "Migrated Netatmo token expiry to netatmo-specific state"
    }

    if (!state.netatmoOAuthState && state.oauthState) {
        state.netatmoOAuthState = state.oauthState
        state.remove("oauthState")
        debugLog "Migrated Netatmo OAuth state to netatmo-specific state"
    }

    if (state.netatmoAuthenticated == null && state.authenticated != null) {
        state.netatmoAuthenticated = state.authenticated
        state.remove("authenticated")
        debugLog "Migrated Netatmo authenticated flag to netatmo-specific state"
    }
}

private String callbackUrl() {
    if (!ensureEndpointAccessToken()) {
        return ""
    }
    return "${getFullApiServerUrl()}/oauth/callback?access_token=${state.hubitatEndpointAccessToken}"
}

private void schedulePolling() {
    String interval = settings.pollIntervalMinutes ?: "5"
    if (interval == "Disabled") {
        state.lastPollStatus = state.lastPollStatus ?: "Disabled"
        state.lastPollMessage = "Scheduled polling is disabled."
        debugLog "Netatmo scheduled polling disabled"
        return
    }

    try {
        switch (interval) {
            case "1":
                runEvery1Minute("poll")
                break
            case "5":
                runEvery5Minutes("poll")
                break
            case "10":
                runEvery10Minutes("poll")
                break
            case "15":
                runEvery15Minutes("poll")
                break
            case "30":
                runEvery30Minutes("poll")
                break
            default:
                log.warn "Unsupported Netatmo poll interval ${interval}; scheduled polling disabled"
                state.lastPollStatus = "Disabled"
                state.lastPollMessage = "Unsupported poll interval ${interval}."
                return
        }
        debugLog "Netatmo scheduled polling every ${interval} minute(s)"
    } catch (Exception e) {
        log.error "Failed to schedule Netatmo polling: ${exceptionSummary(e)}"
        debugLog "Netatmo polling schedule exception details: ${e}"
        state.lastPollStatus = "Error"
        state.lastPollMessage = "Failed to schedule polling."
    }
}

private Boolean ensureEndpointAccessToken() {
    if (state.hubitatEndpointAccessToken && state.accessToken != state.hubitatEndpointAccessToken) {
        state.accessToken = state.hubitatEndpointAccessToken
        debugLog "Restored Hubitat endpoint token state"
    }

    if (!state.hubitatEndpointAccessToken) {
        try {
            state.hubitatEndpointAccessToken = createAccessToken()
            state.accessToken = state.hubitatEndpointAccessToken
            debugLog "Created Hubitat endpoint access token"
        } catch (Exception e) {
            state.hubitatEndpointAccessToken = null
            log.warn "Hubitat app OAuth is not enabled yet; Netatmo authorization cannot start until it is enabled in Apps Code."
            debugLog "Hubitat endpoint token creation failed: ${exceptionSummary(e)}"
            return false
        }
    }
    return !!state.hubitatEndpointAccessToken
}

private String buildDni(String stationId, String moduleId) {
    return "netatmo:${stationId}:${moduleId}"
}

private String deviceClassForModuleType(String moduleType) {
    switch (moduleType) {
        case "NAMain":
            return "base"
        case "NAModule1":
            return "outdoor"
        case "NAModule4":
            return "indoor"
        case "NAModule3":
            return "rain"
        case "NAModule2":
            return "wind"
        default:
            return "unknown"
    }
}

private String displayNameForDevice(String stationName, String moduleName, String deviceClass) {
    if (deviceClass == "base") {
        return stationName ?: moduleName ?: "Netatmo Weather Base Station"
    }

    if (stationName && moduleName) {
        return "${stationName} - ${moduleName}"
    }

    return moduleName ?: stationName ?: "Netatmo ${deviceClass ?: 'unknown'}"
}

private Boolean reachableFromSource(Map source) {
    if (source.reachable != null) {
        return source.reachable == true || source.reachable == "true"
    }

    return true
}

private Long lastSeenFromSource(Map source) {
    return safeLong(source.last_seen)
}

private String measurementTimeFromSource(Map source) {
    Map dashboard = source.dashboard_data instanceof Map ? (Map)source.dashboard_data : [:]
    return safeEpochSecondsAsString(dashboard.time_utc)
}

private String buildApiUrl(String path) {
    String normalizedPath = path ?: ""
    if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
        return normalizedPath
    }
    if (!normalizedPath.startsWith("/")) {
        normalizedPath = "/${normalizedPath}"
    }
    return "${netatmoApiBaseUrl()}${normalizedPath}"
}

private Boolean successStatus(Object status) {
    Integer statusCode = safeInteger(status)
    return statusCode != null && statusCode >= 200 && statusCode < 300
}

private Integer responseStatusFromException(Exception e) {
    try {
        return safeInteger(e?.response?.status)
    } catch (Exception ignored) {
        return null
    }
}

private Map responseDataAsMap(Object data) {
    if (data instanceof Map) {
        return (Map)data
    }

    if (data instanceof String) {
        try {
            Object parsed = parseJson(data as String)
            if (parsed instanceof Map) {
                return (Map)parsed
            }
        } catch (Exception e) {
            log.warn "Could not parse Netatmo response JSON text: ${exceptionSummary(e)}"
            debugLog "Unexpected Netatmo JSON text: ${data}"
        }
    }

    log.warn "Netatmo response data was not a map"
    debugLog "Unexpected Netatmo response data: ${data}"
    return [:]
}

private String toQueryString(Map query) {
    return (query ?: [:]).collect { key, value ->
        "${urlEncode(key as String)}=${urlEncode(value == null ? "" : value as String)}"
    }.join("&")
}

private String urlEncode(String value) {
    return java.net.URLEncoder.encode(value ?: "", "UTF-8")
}

private Long safeLong(Object value) {
    try {
        return value == null ? null : value as Long
    } catch (Exception e) {
        debugLog "Could not convert value to Long: ${value}"
        return null
    }
}

private Integer safeInteger(Object value) {
    try {
        return value == null ? null : value as Integer
    } catch (Exception e) {
        debugLog "Could not convert value to Integer: ${value}"
        return null
    }
}

private BigDecimal numberValue(Object value) {
    try {
        return value == null ? null : value as BigDecimal
    } catch (Exception e) {
        debugLog "Could not convert value to number: ${value}"
        return null
    }
}

private String stringValue(Object value) {
    if (value == null) {
        return null
    }

    String text = value as String
    return text.trim() ? text : null
}

private String formatTimestamp(Object timestamp) {
    Long millis = safeLong(timestamp)
    if (!millis) {
        return "unknown"
    }
    return new Date(millis).format("yyyy-MM-dd HH:mm:ss z", location?.timeZone ?: TimeZone.getDefault())
}

private String safeEpochSecondsAsString(Object value) {
    Long seconds = safeLong(value)
    if (seconds == null) {
        return null
    }

    try {
        return new Date(seconds * 1000L).format("yyyy-MM-dd HH:mm:ss z", location?.timeZone ?: TimeZone.getDefault())
    } catch (Exception e) {
        log.warn "Could not format Netatmo epoch seconds value ${value}: ${exceptionSummary(e)}"
        return value as String
    }
}

private String exceptionSummary(Exception e) {
    String message = e?.message ?: e?.toString() ?: "Unknown exception"
    Integer status = responseStatusFromException(e)
    return status ? "HTTP ${status}: ${message}" : message
}

private Map safeCallbackParams(Map callbackParams) {
    return [
        code: callbackParams?.code ? "present" : "missing",
        state: callbackParams?.state ? "present" : "missing",
        error: callbackParams?.error
    ]
}

private renderCallbackPage(String title, String message) {
    def html = """
<!DOCTYPE html>
<html>
<head>
  <title>${escapeHtml(title)}</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 32px; line-height: 1.45; color: #202124; }
    .panel { max-width: 640px; }
  </style>
</head>
<body>
  <main class="panel">
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(message)}</p>
    <p>Close this tab, then return to the Netatmo Weather Station Connect app in Hubitat.</p>
    <p>Refresh the Netatmo Weather Station Connect app page to see the latest authorization status.</p>
  </main>
</body>
</html>
"""
    return render(contentType: 'text/html', data: html)
}

private String escapeHtml(String value) {
    return (value ?: "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private void debugLog(String message) {
    if (settings.debugLogging == true) {
        log.debug message
    }
}
