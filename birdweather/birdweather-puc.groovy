/**
 * BirdWeather PUC — Hubitat Driver
 *
 * Polls the BirdWeather API for live bird detections from your PUC station
 * and exposes them as Hubitat attributes and events for use in automations.
 *
 * ── INSTALLATION ──────────────────────────────────────────────────────────
 *  1. Hubitat UI → Drivers Code → New Driver → paste this file → Save
 *  2. Devices → Add Device → Virtual → select "BirdWeather PUC"
 *  3. Open the device, enter your Station ID in Preferences → Save
 *  4. Click Refresh once to verify the connection — polling starts automatically
 *
 * ── FINDING YOUR STATION ID ───────────────────────────────────────────────
 *  Your station ID is the number in the URL when viewing your station at
 *  app.birdweather.com (e.g. app.birdweather.com/stations/25574 → ID is 25574).
 *
 *  The longer API Token (found in the app under Advanced Settings) is only
 *  needed for private stations. Leave it blank for public stations.
 *
 * ── AUTOMATION IDEAS ──────────────────────────────────────────────────────
 *  • "birdDetected" event fires on every new detection → announce on speaker
 *  • "newSpeciesDetected" event fires for first sighting of a species today
 *  • "lastCertainty" = "Almost Certain" filter → only high-confidence alerts
 *  • "todaySpecies" attribute → display on a dashboard tile
 *  • "lifetimeSpeciesList" → ever-growing JSON array of all species detected since install
 *  • Rule Machine: IF newSpeciesDetected THEN send push notification with
 *    "%value% (%lastSpeciesScientific%)" as the message text
 */

private String getDriverVersion() { return "1.2.1" }

metadata {
    definition(
        name:        "BirdWeather PUC",
        namespace:   "community",
        author:      "Brent Rossow",
        description: "Live bird detection data from a BirdWeather PUC station"
    ) {
        capability "Refresh"
        capability "Sensor"

        // ── Latest Detection ──────────────────────────────────────────────
        attribute "lastSpecies",          "string"   // Common name
        attribute "lastSpeciesScientific","string"   // Scientific name
        attribute "lastConfidence",       "number"   // Detection confidence 0–100 %
        attribute "lastCertainty",        "string"   // Almost Certain / Very Likely / Uncertain / Unlikely
        attribute "lastDetectedAt",       "string"   // ISO 8601 timestamp
        attribute "lastDetectedTime",     "string"   // Display-friendly time (e.g. "10:47 AM")
        attribute "lastSpeciesImageUrl",  "string"   // Species thumbnail
        attribute "lastSoundscapeUrl",    "string"   // Audio clip URL (if available)

        // ── Recent Detection History (JSON array) ────────────────────────
        attribute "recentDetections",     "string"

        // ── Today's Summary ───────────────────────────────────────────────
        attribute "todaySpecies",         "number"   // Unique species detected today
        attribute "todayDetections",      "number"   // Total detections today

        // ── Top Species Today (ranked by detection count) ─────────────────
        attribute "topSpeciesToday",      "string"   // Most-detected species (common name)
        attribute "topSpeciesCount",      "number"   // Detection count for top species

        // ── Today's Species List ──────────────────────────────────────────
        attribute "todaySpeciesList",     "string"   // JSON array of species names seen today

        // ── Lifetime Totals (driver-tracked, never resets) ───────────────
        attribute "lifetimeSpecies",      "number"   // Unique species ever detected since driver install
        attribute "lifetimeDetections",   "number"   // Total detections since driver install
        attribute "lifetimeSpeciesList",  "string"   // JSON array of all species, sorted alphabetically

        // ── Automation Trigger Events (also appear in event log) ──────────
        // birdDetected       — every new detection; value = common name
        // newSpeciesDetected — first sighting of a species today; value = common name
        attribute "birdDetected",         "string"
        attribute "newSpeciesDetected",   "string"

        // ── Driver Health ─────────────────────────────────────────────────
        attribute "driverVersion",        "string"
        attribute "lastPollStatus",       "string"   // "OK" or "Error: ..."
        attribute "lastPollTime",         "string"

        command "refresh"
        command "resetHistory"
        command "setLifetimeDetections", [[name: "count", type: "NUMBER", description: "Set starting detection count (e.g. from BirdWeather Data Explorer)"]]
    }
}

preferences {
    input "stationId", "text",
        title:       "Station ID",
        description: "Numeric ID from your station's URL at app.birdweather.com (e.g. 25574)",
        required:    true

    input "apiToken", "text",
        title:       "API Token (optional)",
        description: "Only needed for private stations — found in the app under Advanced Settings",
        required:    false

    input "pollInterval", "enum",
        title:       "Poll Interval",
        options:     ["1 minute", "2 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes"],
        defaultValue:"5 minutes",
        required:    true

    input "historyDepth", "enum",
        title:       "Recent Detections to Track",
        options:     ["3", "5", "10", "20"],
        defaultValue:"5"

    input "minConfidencePct", "number",
        title:       "Minimum Confidence % (0 = accept all)",
        description: "Detections below this threshold are ignored",
        defaultValue: 0,
        range:       "0..100"

    input "announceCertaintyFilter", "enum",
        title:       "Fire events only for certainty level ≥",
        description: "Filters 'birdDetected' and 'newSpeciesDetected' events",
        options:     ["all", "very_likely", "almost_certain"],
        defaultValue:"all"

    input "nightModeEnable", "bool",
        title:       "Pause polling at night",
        description: "Skip polls between sunset and sunrise (birds aren't active anyway)",
        defaultValue: false

    input "logEnable", "bool",
        title:       "Enable Debug Logging",
        defaultValue: false
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

def installed() {
    log.info "BirdWeather PUC: installed"
    initialize()
}

def updated() {
    log.info "BirdWeather PUC: preferences saved"
    unschedule()
    initialize()
}

def uninstalled() {
    unschedule()
}

def initialize() {
    if (!stationId?.trim()) {
        log.warn "BirdWeather PUC: no station ID configured"
        sendEvent(name: "lastPollStatus", value: "Error: Station ID not set")
        return
    }
    sendEvent(name: "driverVersion", value: driverVersion)
    schedulePolling()
    ensureLifetimeStateInitialized()
    runIn(3, refresh)
}

// ── Scheduling ─────────────────────────────────────────────────────────────

private schedulePolling() {
    def cron = pollIntervalToCron(pollInterval ?: "5 minutes")
    schedule(cron, poll)
    schedule("0 0 3 * * ?", initialize)  // daily watchdog at 3 AM re-registers schedule if dropped
    debugLog "Polling scheduled: every ${pollInterval} (${cron})"
}

private String pollIntervalToCron(String interval) {
    switch (interval) {
        case "1 minute":   return "0 * * ? * *"
        case "2 minutes":  return "0 0/2 * ? * *"
        case "5 minutes":  return "0 0/5 * ? * *"
        case "10 minutes": return "0 0/10 * ? * *"
        case "15 minutes": return "0 0/15 * ? * *"
        case "30 minutes": return "0 0/30 * ? * *"
        default:           return "0 0/5 * ? * *"
    }
}

// ── Commands ───────────────────────────────────────────────────────────────

def refresh() {
    poll()
}

def poll() {
    if (!stationId?.trim()) {
        log.warn "BirdWeather PUC: poll skipped — no station ID"
        return
    }
    if (nightModeEnable) {
        def sun = getSunriseAndSunset()
        def now = new Date()
        if (now.before(sun.sunrise) || now.after(sun.sunset)) {
            debugLog "Night mode: skipping poll (outside sunrise/sunset window)"
            return
        }
    }
    maybeResetDailyTracking()
    ensureLifetimeStateInitialized()
    fetchDetections()
    fetchDayStats()
    fetchTopSpecies()
}

def retryPoll() {
    state.retryScheduled = false
    log.info "BirdWeather PUC: retrying after transient error"
    poll()
}

def resetHistory() {
    log.info "BirdWeather PUC: resetting all state and attributes"
    state.clear()
    [
        "lastSpecies", "lastSpeciesScientific", "lastCertainty",
        "lastDetectedAt", "lastDetectedTime", "lastSpeciesImageUrl", "lastSoundscapeUrl",
        "recentDetections", "topSpeciesToday",
        "birdDetected", "newSpeciesDetected", "lastPollStatus", "lastPollTime"
    ].each { sendEvent(name: it, value: "—") }
    sendEvent(name: "todaySpeciesList",    value: "[]")
    sendEvent(name: "lifetimeSpeciesList", value: "[]")
    ["lastConfidence", "todaySpecies", "todayDetections",
     "topSpeciesCount", "lifetimeSpecies", "lifetimeDetections"
    ].each { sendEvent(name: it, value: 0) }
    runIn(2, refresh)
}

def setLifetimeDetections(count) {
    def n = count?.toLong() ?: 0
    state.lifetimeDetectionCount = n
    sendEvent(name: "lifetimeDetections", value: n)
    log.info "BirdWeather: lifetime detection count set to ${n}"
}

// ── Lifetime State Initialization ─────────────────────────────────────────
// Called from both initialize() and poll() so lifetime tracking self-heals
// if device state is wiped by the Hubitat platform without a hub reboot.

private ensureLifetimeStateInitialized() {
    if (state.lifetimeSpeciesSeen == null) {
        def seed = (state.todaySpeciesSeen ?: []).collect { it }
        state.lifetimeSpeciesSeen = seed
        if (seed) {
            sendEvent(name: "lifetimeSpeciesList", value: groovy.json.JsonOutput.toJson(seed.sort()))
            sendEvent(name: "lifetimeSpecies",     value: seed.size())
            log.info "BirdWeather: lifetime species state cleared — re-seeded with ${seed.size()} species from today's tracking"
        }
    }
    if (state.lifetimeDetectionCount == null) state.lifetimeDetectionCount = 0
    if (state.lifetimeSpeciesCount    == null) state.lifetimeSpeciesCount = state.lifetimeSpeciesSeen?.size() ?: 0
}

// ── Daily Tracking Reset ────────────────────────────────────────────────────

private maybeResetDailyTracking() {
    def today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.trackingDate != today) {
        debugLog "New day (${today}) — resetting daily species tracking"
        state.todaySpeciesSeen = []
        state.trackingDate     = today
        sendEvent(name: "todaySpeciesList", value: "[]")
    }
}

// ── API Calls ──────────────────────────────────────────────────────────────

/**
 * Builds an asynchttpGet params map, adding an Authorization header
 * when an API token has been configured.
 */
private Map buildParams(String uri, Map query = [:]) {
    def params = [uri: uri, contentType: "application/json", timeout: 20]
    if (query) params.query = query
    if (apiToken?.trim()) params.headers = ["Authorization": "Bearer ${apiToken.trim()}"]
    return params
}

private fetchDetections() {
    def depth = safeInt(historyDepth, 5)
    def limit = Math.max(depth, 10)
    asynchttpGet("handleDetectionsResponse",
        buildParams("https://app.birdweather.com/api/v1/stations/${stationId}/detections",
                    [limit: limit]),
        [depth: depth])
}

private fetchDayStats() {
    asynchttpGet("handleDayStatsResponse",
        buildParams("https://app.birdweather.com/api/v1/stations/${stationId}/stats",
                    [period: "day"]))
}

private fetchTopSpecies() {
    asynchttpGet("handleTopSpeciesResponse",
        buildParams("https://app.birdweather.com/api/v1/stations/${stationId}/species",
                    [period: "day", limit: 5]))
}

// ── Response Handlers ──────────────────────────────────────────────────────

def handleDetectionsResponse(response, data) {
    if (response.hasError()) {
        def status = response.status
        def msg = "Error: HTTP ${status}"
        log.warn "BirdWeather detections API — ${msg}"
        sendEvent(name: "lastPollStatus", value: msg)
        if ((status == 408 || status >= 500) && !state.retryScheduled) {
            state.retryScheduled = true
            log.info "BirdWeather PUC: scheduling retry in 60 seconds"
            runIn(60, "retryPoll")
        }
        return
    }

    try {
        def json       = response.json
        def detections = json?.detections
        def depth      = data?.depth ?: safeInt(historyDepth, 5)

        if (!detections) {
            debugLog "Detections response contained no detections array"
            sendEvent(name: "lastPollStatus", value: "OK")
            sendEvent(name: "lastPollTime",   value: nowStr())
            return
        }

        // ── Confidence filter ─────────────────────────────────────────────
        def minConf = safeFloat(minConfidencePct, 0) / 100.0
        if (minConf > 0) {
            detections = detections.findAll { safeFloat(it?.confidence, 0) >= minConf }
        }

        // ── Build recent-detections list ───────────────────────────────────
        def recentList = detections.take(depth).collect { d ->
            def sp = d.species ?: [:]
            [
                id:         d.id,
                species:    speciesName(sp),
                scientific: scientificName(sp),
                confidence: pct(d.confidence),
                certainty:  d.certainty ?: "",
                timestamp:  d.timestamp ?: "",
                imageUrl:   imageUrl(sp)
            ]
        }
        sendEvent(name: "recentDetections", value: groovy.json.JsonOutput.toJson(recentList))

        // ── Latest detection attributes ────────────────────────────────────
        def latest     = detections[0]
        def latestId   = latest?.id?.toString()
        def lastSeenId = state.lastDetectionId ?: ""

        def sp           = latest.species ?: [:]
        def commonName   = speciesName(sp)
        def sciName      = scientificName(sp)
        def confidence   = pct(latest.confidence)
        def certaintyRaw = latest.certainty ?: ""
        def certainty    = formatCertainty(certaintyRaw)
        def timestamp    = latest.timestamp ?: "—"
        def imgUrl       = imageUrl(sp)
        def soundUrl     = latest.soundscape?.url ?: ""

        sendEvent(name: "lastSpecies",          value: commonName)
        sendEvent(name: "lastSpeciesScientific", value: sciName)
        sendEvent(name: "lastConfidence",        value: confidence, unit: "%")
        sendEvent(name: "lastCertainty",         value: certainty)
        sendEvent(name: "lastDetectedAt",        value: timestamp)
        sendEvent(name: "lastDetectedTime",      value: formatDetectionTime(timestamp))
        if (imgUrl)   sendEvent(name: "lastSpeciesImageUrl", value: imgUrl)
        if (soundUrl) sendEvent(name: "lastSoundscapeUrl",   value: soundUrl)

        // ── Fire events only for NEW detections ────────────────────────────
        if (latestId && latestId != lastSeenId) {
            state.lastDetectionId = latestId
            debugLog "New detection: ${commonName} (${confidence}%, ${certainty})"

            def newLifetimeCount = (state.lifetimeDetectionCount ?: 0) + 1
            state.lifetimeDetectionCount = newLifetimeCount
            sendEvent(name: "lifetimeDetections", value: newLifetimeCount)

            if (passesEventFilter(certaintyRaw, latest.confidence)) {
                sendEvent(
                    name:            "birdDetected",
                    value:           commonName,
                    descriptionText: "${commonName} detected (${confidence}%, ${certainty})"
                )

                def seenToday = state.todaySpeciesSeen ?: []
                if (!(commonName in seenToday)) {
                    seenToday << commonName
                    state.todaySpeciesSeen = seenToday
                    sendEvent(name: "todaySpeciesList", value: groovy.json.JsonOutput.toJson(seenToday))
                    sendEvent(
                        name:            "newSpeciesDetected",
                        value:           commonName,
                        descriptionText: "First ${commonName} today! (${sciName})"
                    )
                    log.info "BirdWeather: first ${commonName} today — ${seenToday.size()} species so far"
                }

                def seenLifetime = state.lifetimeSpeciesSeen ?: []
                if (!(commonName in seenLifetime)) {
                    seenLifetime << commonName
                    state.lifetimeSpeciesSeen = seenLifetime
                    def newCount = (state.lifetimeSpeciesCount ?: 0) + 1
                    state.lifetimeSpeciesCount = newCount
                    sendEvent(name: "lifetimeSpeciesList", value: groovy.json.JsonOutput.toJson(seenLifetime.sort()))
                    sendEvent(name: "lifetimeSpecies",     value: newCount)
                    log.info "BirdWeather: new lifetime species — ${commonName} (${newCount} total)"
                }
            } else {
                debugLog "Event suppressed by certainty filter: ${certainty}"
            }
        }

        sendEvent(name: "lastPollStatus", value: "OK")
        sendEvent(name: "lastPollTime",   value: nowStr())

    } catch (Exception e) {
        log.error "BirdWeather: error parsing detections — ${e.message}"
        sendEvent(name: "lastPollStatus", value: "Error: ${e.message}")
    }
}

def handleDayStatsResponse(response, data) {
    if (response.hasError()) {
        log.warn "BirdWeather: day stats API returned HTTP ${response.status} — skipping"
        return
    }
    try {
        def json = response.json
        if (json?.success == false) {
            log.warn "BirdWeather: day stats API returned success=false — response: ${json}"
            return
        }
        if (json?.species    != null) sendEvent(name: "todaySpecies",    value: json.species)
        if (json?.detections != null) sendEvent(name: "todayDetections", value: json.detections)
        debugLog "Day stats: ${json?.species} species, ${json?.detections} detections"
    } catch (Exception e) {
        log.error "BirdWeather: error parsing day stats — ${e.message}"
    }
}

def handleTopSpeciesResponse(response, data) {
    if (response.hasError()) {
        debugLog "Species API returned HTTP ${response.status} — skipping"
        return
    }
    try {
        def json        = response.json
        def speciesList = json?.species
        if (!speciesList) return

        def sorted   = speciesList.sort { -(it?.detections?.total ?: 0) }
        def top      = sorted[0]
        if (!top) return

        def topName  = top.commonName ?: top.common_name ?: "(unidentified)"
        def topCount = top.detections?.total ?: 0

        sendEvent(name: "topSpeciesToday", value: topName)
        sendEvent(name: "topSpeciesCount", value: topCount)
        debugLog "Top species today: ${topName} (${topCount} detections)"

    } catch (Exception e) {
        log.error "BirdWeather: error parsing top species — ${e.message}"
    }
}

// ── Species Field Helpers ──────────────────────────────────────────────────
// The detections endpoint uses snake_case; the species endpoint uses camelCase.
// Both are handled here so parsing is consistent across all response types.

private String speciesName(Map sp) {
    return sp?.common_name ?: sp?.commonName ?: "(unidentified)"
}

private String scientificName(Map sp) {
    return sp?.scientific_name ?: sp?.scientificName ?: "—"
}

private String imageUrl(Map sp) {
    return sp?.thumbnail_url ?: sp?.thumbnailUrl ?: sp?.image_url ?: sp?.imageUrl ?: ""
}

// ── Other Helpers ──────────────────────────────────────────────────────────

/**
 * Returns true if the certainty level meets the configured event filter.
 * Ascending confidence order: unlikely < uncertain < very_likely < almost_certain
 */
private boolean passesEventFilter(String certainty, confidence) {
    def filter = announceCertaintyFilter ?: "all"
    if (filter == "all") return true
    def rank = [unlikely: 0, uncertain: 1, very_likely: 2, almost_certain: 3]
    return (rank[certainty] ?: 0) >= (rank[filter] ?: 0)
}

private String formatCertainty(String raw) {
    switch (raw) {
        case "almost_certain": return "Almost Certain"
        case "very_likely":    return "Very Likely"
        case "uncertain":      return "Uncertain"
        case "unlikely":       return "Unlikely"
        default:               return raw ?: "—"
    }
}

private String formatDetectionTime(String isoTimestamp) {
    if (!isoTimestamp || isoTimestamp == "—") return "—"
    try {
        def inFmt  = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        def outFmt = new java.text.SimpleDateFormat("h:mm a")
        outFmt.setTimeZone(location.timeZone)
        return outFmt.format(inFmt.parse(isoTimestamp))
    } catch (e1) {
        try {
            def inFmt  = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
            def outFmt = new java.text.SimpleDateFormat("h:mm a")
            outFmt.setTimeZone(location.timeZone)
            return outFmt.format(inFmt.parse(isoTimestamp))
        } catch (e2) {
            return isoTimestamp
        }
    }
}

/** Converts 0.0–1.0 confidence to an integer percentage. Guards against APIs returning 0–100 directly. */
private int pct(raw) {
    if (raw == null) return 0
    def f = raw.toFloat()
    return f > 1.0 ? Math.round(f) : Math.round(f * 100)
}

private int safeInt(val, int def_) {
    try { return val?.toInteger() ?: def_ } catch (e) { return def_ }
}

private float safeFloat(val, float def_) {
    try { return val?.toFloat() ?: def_ } catch (e) { return def_ }
}

private String nowStr() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private void debugLog(String msg) {
    if (logEnable) log.debug "BirdWeather: ${msg}"
}
