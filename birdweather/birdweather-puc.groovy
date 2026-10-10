/**
 * BirdWeather PUC — Hubitat Driver
 *
 * Copyright 2026 Brent Rossow
 * SPDX-License-Identifier: Apache-2.0
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
 *  app.birdweather.com (e.g. app.birdweather.com/stations/12345 → ID is 12345).
 *
 *  The longer API Token (found in the app under Advanced Settings) is only
 *  needed for private stations. Leave it blank for public stations. When it is
 *  set, the driver reads the station by its token instead of its ID.
 *
 * ── AUTOMATION IDEAS ──────────────────────────────────────────────────────
 *  • "birdDetected" event fires on every new detection → announce on speaker
 *  • "newSpeciesDetected" fires the first time a species is seen each day
 *  • "newLifetimeSpeciesDetected" fires when a species is seen for the very first time ever
 *  • "lastCertainty" = "Almost Certain" filter → only high-confidence alerts
 *  • "todaySpecies" attribute → display on a dashboard tile
 *  • Rule Machine: IF newLifetimeSpeciesDetected THEN send push "%value%"
 */

private String getDriverVersion() { return "1.6.0" }

// The BirdWeather /species endpoint silently caps `limit` at 100 and ignores
// anything larger, so the all-time list has to be walked one page at a time.
private int  getSpeciesPageSize()   { return 100 }
private int  getSpeciesMaxPages()   { return 20 }        // safety stop — 2,000 species
private long getLifetimeRefreshMs() { return 3600000L }  // re-read the all-time list hourly

// A busy station logs more than 10 detections in 5 minutes. Each poll asks for a
// small page; if the last detection already seen isn't on it, the driver walks
// back with the API's cursor in full pages (the API caps limit at 100 too).
private int getDetectionFirstPage()  { return 25 }
private int getDetectionPageSize()   { return 100 }
private int getDetectionMaxPages()   { return 5 }         // safety stop — about 425 detections
private int getMaxBirdDetectedPerPoll() { return 10 }     // a backlog doesn't replay every bird

metadata {
    definition(
        name:        "BirdWeather PUC",
        namespace:   "brossow",
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

        // ── Lifetime Totals (API-sourced from period=all) ────────────────
        attribute "lifetimeSpecies",      "number"   // Unique species ever detected (all time)
        attribute "lifetimeDetections",   "number"   // Total detections (all time)
        attribute "lifetimeSpeciesList",  "string"   // JSON array of all species, sorted alphabetically

        // ── Automation Trigger Events (also appear in event log) ──────────
        // birdDetected               — every new detection; value = common name
        // newSpeciesDetected         — first sighting of a species today; value = common name
        // newLifetimeSpeciesDetected — first time a species has ever been detected; value = common name
        attribute "birdDetected",                "string"
        attribute "newSpeciesDetected",          "string"
        attribute "newLifetimeSpeciesDetected",  "string"

        // ── Driver Health ─────────────────────────────────────────────────
        attribute "driverVersion",        "string"
        attribute "lastPollStatus",       "string"   // "OK" or "Error: ..."
        attribute "lastPollTime",         "string"
        attribute "healthStatus",         "enum", ["online", "offline"]  // offline after a poll and its retry both fail

        command "refresh"
        command "resetHistory"
    }
}

preferences {
    input "stationId", "text",
        title:       "Station ID",
        description: "Numeric ID from your station's URL at app.birdweather.com (e.g. 12345)",
        required:    true

    input "apiToken", "text",
        title:       "API Token (optional)",
        description: "Only needed for private stations — found in the app under Advanced Settings. When set, the station is read by this token instead of its ID",
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

    input "enableBirdDetectedEvent", "bool",
        title:       "Fire birdDetected event on each detection",
        description: "Disable to reduce event log noise if you're not using this trigger in automations",
        defaultValue: true

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
    state.remove("retryScheduled")  // 1.5.0 and earlier; could stick and turn retries off for good
    if (!stationPath) {
        log.warn "BirdWeather PUC: no station ID configured"
        sendEvent(name: "lastPollStatus", value: "Error: Station ID not set")
        return
    }
    sendEvent(name: "driverVersion", value: driverVersion)
    schedulePolling()
    runIn(3, "refresh")
}

// ── Scheduling ─────────────────────────────────────────────────────────────

private schedulePolling() {
    def cron = pollIntervalToCron(pollInterval ?: "5 minutes")
    schedule(cron, "poll")
    schedule("0 0 3 * * ?", "initialize")  // daily watchdog at 3 AM re-registers schedule if dropped
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
    state.remove("lastLifetimeFetchMs")  // a manual refresh always re-reads the all-time list
    poll()
}

def poll() {
    if (!stationPath) {
        log.warn "BirdWeather PUC: poll skipped — no station ID"
        return
    }
    if (nightModeEnable) {
        def sun = getSunriseAndSunset()
        def rightNow = new Date(now())
        if (sun?.sunrise && sun?.sunset && (rightNow.before(sun.sunrise) || rightNow.after(sun.sunset))) {
            debugLog "Night mode: skipping poll (outside sunrise/sunset window)"
            return
        }
    }
    maybeResetDailyTracking()
    fetchDayStats()
    fetchTopSpecies()
    fetchAllTimeStats()
    // The detections handler and the all-time species walk both rewrite
    // state.lifetimeSpeciesSeen, so they run one after the other, never side by
    // side, where either execution could save its copy over the other's.
    // Detections go first: the walk would add a first-ever species to the list
    // before the detection that should announce it had been looked at.
    fetchDetections(lifetimeListDue())
}

def retryPoll() {
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
        "birdDetected", "newSpeciesDetected", "newLifetimeSpeciesDetected", "lastPollStatus", "lastPollTime"
    ].each { sendEvent(name: it, value: "—") }
    sendEvent(name: "todaySpeciesList",    value: "[]")
    sendEvent(name: "lifetimeSpeciesList", value: "[]")
    ["lastConfidence", "todaySpecies", "todayDetections",
     "topSpeciesCount", "lifetimeSpecies", "lifetimeDetections"
    ].each { sendEvent(name: it, value: 0) }
    runIn(2, "refresh")
}

// ── Daily Tracking Reset ────────────────────────────────────────────────────

private maybeResetDailyTracking() {
    def today = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    if (state.trackingDate != today) {
        debugLog "New day (${today}) — resetting daily species tracking"
        state.todaySpeciesSeen = []
        state.trackingDate     = today
        sendEvent(name: "todaySpeciesList", value: "[]")
    }
}

// ── API Calls ──────────────────────────────────────────────────────────────

/**
 * The station segment of every API URL. BirdWeather reads a station's token
 * only from the URL, in place of the station ID; the Authorization header is
 * for administrator tokens. So a private station is read by its token.
 */
private String getStationPath() {
    def token = apiToken?.toString()?.trim()
    if (token) return java.net.URLEncoder.encode(token, "UTF-8")
    def id = stationId?.toString()?.trim()
    if (!id) return null
    def fromUrl = (id =~ /\/stations\/(\d+)/)  // a pasted app.birdweather.com/stations/… link
    return fromUrl.find() ? fromUrl.group(1) : id
}

private String apiUrl(String endpoint) {
    return "https://app.birdweather.com/api/v1/stations/${stationPath}/${endpoint}"
}

/** Builds an asynchttpGet params map. */
private Map buildParams(String uri, Map query = [:]) {
    def params = [uri: uri, contentType: "application/json", timeout: 20]
    if (query) params.query = query
    return params
}

/**
 * Reads detections newest first. The first page is small; later pages walk back
 * from `cursor` (the oldest detection read so far) until the last one already
 * seen turns up. `collected` carries the new detections, trimmed to what the
 * handler needs, from page to page. `thenLifetime`: read the all-time species
 * list once the detections are done with, however that ends.
 */
private fetchDetections(boolean thenLifetime = false, Long cursor = null, int page = 1, List collected = []) {
    def query = [limit: page == 1 ? Math.max(safeInt(historyDepth, 5), detectionFirstPage) : detectionPageSize]
    if (cursor) query.cursor = cursor
    asynchttpGet("handleDetectionsResponse", buildParams(apiUrl("detections"), query),
                 [page: page, collected: collected, thenLifetime: thenLifetime])
}

private fetchDayStats() {
    asynchttpGet("handleDayStatsResponse", buildParams(apiUrl("stats"), [period: "day"]))
}

private fetchTopSpecies() {
    asynchttpGet("handleTopSpeciesResponse", buildParams(apiUrl("species"), [period: "day", limit: 5]))
}

private fetchAllTimeStats() {
    asynchttpGet("handleAllTimeStatsResponse", buildParams(apiUrl("stats"), [period: "all"]))
}

/**
 * The all-time species list only changes when a genuinely new bird shows up,
 * so walking every page on every poll is wasted API calls. Refresh it hourly
 * (or on demand via Refresh); newLifetimeSpeciesDetected fills the gap in
 * between by adding species as they are detected.
 */
private boolean lifetimeListDue() {
    def lastMs = state.lastLifetimeFetchMs ?: 0
    if (now() - lastMs < lifetimeRefreshMs) {
        debugLog "All-time species list is current — skipping refresh"
        return false
    }
    return true
}

private fetchAllTimeSpecies(int page) {
    if (page == 1) state.remove("lifetimeFetchBuffer")
    asynchttpGet("handleAllTimeSpeciesResponse",
        buildParams(apiUrl("species"), [period: "all", limit: speciesPageSize, page: page]),
        [page: page])
}

// ── Response Handlers ──────────────────────────────────────────────────────

def handleDetectionsResponse(response, data) {
    boolean walkingOn = false
    try {
        if (response.hasError()) {
            pollFailed(response.status)
            return
        }
        int  page        = safeInt(data?.page, 1)
        List collected   = (data?.collected ?: []).collect()
        def  detections  = response.json?.detections ?: []
        def  lastSeenId  = state.lastDetectionId?.toString()

        if (page == 1) showLatest(detections)

        if (!detections && page == 1) {
            debugLog "Detections response contained no detections"
            pollSucceeded()
            return
        }

        if (!lastSeenId) {
            // First run: process only the most recent to establish a baseline
            state.lastDetectionId = detections[0].id?.toString()
            processNewDetections([compactDetection(detections[0])], true)
            pollSucceeded()
            return
        }

        // Look for the last detection already seen before any filtering, so
        // changing the confidence threshold can't make it look missing.
        def cutoff = detections.findIndexOf { it?.id?.toString() == lastSeenId }
        collected.addAll((cutoff == -1 ? detections : detections.take(cutoff)).collect { compactDetection(it) })

        if (cutoff == -1 && detections) {
            if (page < detectionMaxPages) {
                walkingOn = true
                fetchDetections(data?.thenLifetime == true, detections[-1].id as Long, page + 1, collected)
                return
            }
            log.warn "BirdWeather: more than ${collected.size()} detections since the last poll — older ones were skipped"
        }

        if (collected) {
            state.lastDetectionId = collected[0].id
            processNewDetections(collected.reverse())  // oldest first
        }
        pollSucceeded()

    } catch (Exception e) {
        log.error "BirdWeather: error parsing detections — ${e.message}"
        sendEvent(name: "lastPollStatus", value: "Error: ${e.message}")
    } finally {
        if (!walkingOn && data?.thenLifetime) fetchAllTimeSpecies(1)
    }
}

/** Latest-detection attributes and recentDetections, from the newest page. */
private showLatest(List detections) {
    def shown  = detections.findAll { passesConfidence(it?.confidence) }
    def depth  = safeInt(historyDepth, 5)

    def recentList = shown.take(depth).collect { d ->
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
    if (detections) sendEvent(name: "recentDetections", value: groovy.json.JsonOutput.toJson(recentList))

    def latest = shown ? shown[0] : null
    if (!latest) return

    def sp        = latest.species ?: [:]
    def timestamp = latest.timestamp ?: "—"
    def imgUrl    = imageUrl(sp)
    def soundUrl  = latest.soundscape?.url ?: ""

    sendEvent(name: "lastSpecies",           value: speciesName(sp))
    sendEvent(name: "lastSpeciesScientific", value: scientificName(sp))
    sendEvent(name: "lastConfidence",        value: pct(latest.confidence), unit: "%")
    sendEvent(name: "lastCertainty",         value: formatCertainty(latest.certainty ?: ""))
    sendEvent(name: "lastDetectedAt",        value: timestamp)
    sendEvent(name: "lastDetectedTime",      value: formatDetectionTime(timestamp))
    if (imgUrl)   sendEvent(name: "lastSpeciesImageUrl", value: imgUrl)
    if (soundUrl) sendEvent(name: "lastSoundscapeUrl",   value: soundUrl)
}

/** Just what processNewDetections needs, so a long backlog stays small between pages. */
private Map compactDetection(det) {
    def sp = det?.species ?: [:]
    return [id: det?.id?.toString(), name: speciesName(sp), sci: scientificName(sp),
            confidence: det?.confidence, certainty: det?.certainty ?: ""]
}

/**
 * Fires events for new detections, oldest first. Every one counts toward
 * today's and the lifetime species, so a first sighting is never missed, but
 * after a backlog only the newest few fire birdDetected.
 */
private processNewDetections(List detections, boolean baseline = false) {
    def wanted = detections.findAll { passesConfidence(it.confidence) }
    if (!wanted) return
    debugLog "Processing ${wanted.size()} new detection(s)"

    def seenToday    = (state.todaySpeciesSeen    ?: []).collect()
    def seenLifetime = (state.lifetimeSpeciesSeen ?: []).collect()
    int birdEventsFrom = wanted.size() - maxBirdDetectedPerPoll
    if (birdEventsFrom > 0) debugLog "Backlog of ${wanted.size()} — birdDetected fires for the newest ${maxBirdDetectedPerPoll} only"

    wanted.eachWithIndex { det, i ->
        def dName = det.name
        def dSci  = det.sci
        def dConf = pct(det.confidence)
        def dCert = formatCertainty(det.certainty)

        if (!passesEventFilter(det.certainty)) {
            debugLog "Event suppressed by certainty filter: ${dCert}"
            return
        }
        debugLog "New detection: ${dName} (${dConf}%, ${dCert})"

        if (enableBirdDetectedEvent != false && i >= birdEventsFrom) {
            sendEvent(
                name:            "birdDetected",
                value:           dName,
                descriptionText: "${dName} detected (${dConf}%, ${dCert})"
            )
        }

        if (!(dName in seenToday)) {
            seenToday << dName
            sendEvent(name: "todaySpeciesList", value: groovy.json.JsonOutput.toJson(seenToday))
            sendEvent(
                name:            "newSpeciesDetected",
                value:           dName,
                descriptionText: "First ${dName} today! (${dSci})"
            )
            log.info "BirdWeather: first ${dName} today — ${seenToday.size()} species so far"
        }

        if (!(dName in seenLifetime)) {
            seenLifetime << dName
            // Until the all-time list has loaded at least once there is no way to
            // tell a genuine first-ever sighting from a bird we simply haven't read
            // in yet, and the baseline detection on a first run or after Reset
            // History may be older than the list. Record it, but don't cry wolf.
            if (state.lifetimeBootstrapped && !baseline) {
                sendEvent(
                    name:            "newLifetimeSpeciesDetected",
                    value:           dName,
                    descriptionText: "New lifetime species: ${dName} (${dSci})"
                )
                log.info "BirdWeather: new lifetime species — ${dName}"
            } else {
                debugLog "All-time list not loaded yet — recording ${dName} without firing an event"
            }
        }
    }

    state.todaySpeciesSeen    = seenToday
    state.lifetimeSpeciesSeen = seenLifetime
}

private pollSucceeded() {
    state.failedPolls = 0
    sendEvent(name: "lastPollStatus", value: "OK")
    sendEvent(name: "lastPollTime",   value: nowStr())
    sendEvent(name: "healthStatus",   value: "online")
}

/** A failed detections request. One failure is retried; a second in a row is offline. */
private pollFailed(status) {
    def msg = "Error: HTTP ${status}"
    log.warn "BirdWeather detections API — ${msg}"
    sendEvent(name: "lastPollStatus", value: msg)
    state.failedPolls = (state.failedPolls ?: 0) + 1
    if (state.failedPolls >= 2) sendEvent(name: "healthStatus", value: "offline")
    if (status == 408 || status >= 500) {
        log.info "BirdWeather PUC: scheduling retry in 60 seconds"
        runIn(60, "retryPoll")  // replaces any retry already pending
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

def handleAllTimeStatsResponse(response, data) {
    if (response.hasError()) {
        log.warn "BirdWeather: all-time stats API returned HTTP ${response.status} — skipping"
        return
    }
    try {
        def json = response.json
        if (json?.success == false) {
            log.warn "BirdWeather: all-time stats API returned success=false"
            return
        }
        if (json?.detections != null) sendEvent(name: "lifetimeDetections", value: json.detections)
        if (json?.species    != null) sendEvent(name: "lifetimeSpecies",     value: json.species)
        debugLog "All-time stats: ${json?.species} species, ${json?.detections} detections"
    } catch (Exception e) {
        log.error "BirdWeather: error parsing all-time stats — ${e.message}"
    }
}

def handleAllTimeSpeciesResponse(response, data) {
    def page = safeInt(data?.page, 1)

    try {
        if (response.hasError()) {
            debugLog "All-time species API returned HTTP ${response.status} on page ${page} — keeping existing list"
            state.remove("lifetimeFetchBuffer")
            return
        }
        def json        = response.json
        def speciesList = json?.species
        if (speciesList == null) {
            log.warn "BirdWeather: all-time species page ${page} had no species array — keeping existing list"
            state.remove("lifetimeFetchBuffer")
            return
        }

        def names = speciesList
            .collect { sp -> sp?.commonName ?: sp?.common_name ?: "" }
            .findAll { it }

        def buffer = (state.lifetimeFetchBuffer ?: []).collect()
        buffer.addAll(names)
        state.lifetimeFetchBuffer = buffer

        // A full page means there is probably another one behind it.
        if (speciesList.size() >= speciesPageSize && page < speciesMaxPages) {
            debugLog "All-time species page ${page}: ${names.size()} names (${buffer.size()} total) — fetching page ${page + 1}"
            fetchAllTimeSpecies(page + 1)
            return
        }

        commitLifetimeSpecies(buffer)
        state.remove("lifetimeFetchBuffer")
        state.lastLifetimeFetchMs = now()

    } catch (Exception e) {
        log.error "BirdWeather: error parsing all-time species — ${e.message}"
        state.remove("lifetimeFetchBuffer")
    }
}

/**
 * Merges a freshly-fetched all-time species list into the tracked set.
 *
 * This is a union, never a replacement. The detections poll can legitimately
 * record a species before BirdWeather's all-time aggregate catches up, and the
 * two run as independent async handlers — replacing the list here would drop
 * that species, and its next sighting would re-fire newLifetimeSpeciesDetected
 * for a bird already alerted on.
 */
private commitLifetimeSpecies(List fetched) {
    if (!fetched) {
        log.warn "BirdWeather: all-time species fetch returned no names — keeping existing list"
        return
    }

    def merged = ((state.lifetimeSpeciesSeen ?: []) + fetched).unique().sort()
    state.lifetimeSpeciesSeen  = merged
    state.lifetimeBootstrapped = true
    sendEvent(name: "lifetimeSpeciesList", value: groovy.json.JsonOutput.toJson(merged))
    debugLog "All-time species list: ${merged.size()} species"
}

// ── Species Field Helpers ──────────────────────────────────────────────────
// Both endpoints currently return camelCase, but snake_case is accepted first
// so an API that reverts to it keeps parsing consistently.

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
private boolean passesEventFilter(String certainty) {
    def filter = announceCertaintyFilter ?: "all"
    if (filter == "all") return true
    def rank = [unlikely: 0, uncertain: 1, very_likely: 2, almost_certain: 3]
    return (rank[certainty] ?: 0) >= (rank[filter] ?: 0)
}

/** True if a detection meets the Minimum Confidence % preference. */
private boolean passesConfidence(confidence) {
    def minConf = safeFloat(minConfidencePct, 0) / 100.0
    return minConf <= 0 || safeFloat(confidence, 0) >= minConf
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
    return new Date(now()).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private void debugLog(String msg) {
    if (logEnable) log.debug "BirdWeather: ${msg}"
}
