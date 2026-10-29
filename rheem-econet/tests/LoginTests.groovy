import groovyx.net.http.HttpResponseException

/**
 * Login, session expiry, retry backoff and polling — shared by both drivers.
 *
 * The backoff protects the Rheem account the mobile app also uses: a wrong
 * password must not be retried every poll, and nothing (a refresh rule, a poll,
 * a command) may bypass the schedule.
 */
class LoginTests {
    static final List<String> FILES = ["EcoNetThermostat.groovy", "EcoNetWaterHeater.groovy"]

    static Map start(String file, Closure tweak = null) {
        def unit = file.contains("Thermostat") ? Fixtures.thermostat() : Fixtures.waterHeater()
        def ctx = Fixtures.connected(file, Fixtures.account([unit]))
        if (tweak) tweak(ctx.api)
        ctx.d.exec("initialize")
        return ctx
    }

    void testMissingCredentialsSayWhereTheyGo() {
        FILES.each { f ->
            def d = Fixtures.load(f)
            d.httpHandler = new FakeEcoNet().handler()
            d.exec("initialize")
            assert d.requests.isEmpty()
            assert d.log.at("warn").any { it.contains("Preferences tab") && it.contains("no separate login prompt") }
        }
    }

    void testSuccessfulLoginPollsImmediatelyAndOnSchedule() {
        FILES.each { f ->
            def ctx = start(f)
            assert ctx.api.authCalls == 1
            assert ctx.api.pollCalls == 1
            assert ctx.d.state.userToken == "TOKEN-PLACEHOLDER"
            assert ctx.d.scheduled.any { it.kind == "every" && it.minutes == 5 && it.method == "fetchEquipment" }
        }
    }

    void testPollIntervals() {
        def expected = ["1 minute": 1, "5 minutes": 5, "15 minutes": 15, "30 minutes": 30, "1 hour": 60]
        FILES.each { f ->
            expected.each { setting, minutes ->
                def ctx = Fixtures.connected(f, [], [pollInterval: setting])
                ctx.d.exec("schedulePoll")
                assert ctx.d.scheduled.findAll { it.method == "fetchEquipment" } ==
                       [[kind: "every", minutes: minutes, method: "fetchEquipment"]]
            }
            def ctx = Fixtures.connected(f, [], [pollInterval: "10 minutes"])
            ctx.d.exec("schedulePoll")
            assert ctx.d.scheduled.findAll { it.method == "fetchEquipment" }*.kind == ["cron"]
        }
    }

    void testRejectedPasswordWaitsAnHour() {
        FILES.each { f ->
            def ctx = start(f) { it.rejectCredentials = true }
            assert ctx.d.runInsFor("login")*.seconds == [3600L]
            assert ctx.d.log.at("error").any { it.contains("login rejected") && it.contains("1 hour") }
            assert ctx.d.state.userToken == null
        }
    }

    void testHttp401Or403OnLoginCountsAsRejected() {
        FILES.each { f ->
            [401, 403].each { code ->
                def ctx = start(f) { it.authStatus = code }
                assert ctx.d.runInsFor("login")*.seconds == [3600L]
            }
        }
    }

    void testServerErrorsBackOffByDoublingToAnHour() {
        FILES.each { f ->
            def ctx = start(f) { it.authStatus = 503 }
            def delays = [ctx.d.runInsFor("login")[0].seconds]
            7.times {
                ctx.d.fire("login")
                delays << ctx.d.runInsFor("login")[0].seconds
            }
            assert delays == [120L, 240L, 480L, 960L, 1920L, 3600L, 3600L, 3600L]
        }
    }

    void testNetworkErrorBacksOff() {
        FILES.each { f ->
            def ctx = start(f) { it.networkError = new IOException("connect timed out") }
            assert ctx.d.runInsFor("login")*.seconds == [120L]
        }
    }

    void testPollsDuringBackoffDoNotLogIn() {
        FILES.each { f ->
            def ctx = start(f) { it.rejectCredentials = true }
            ctx.d.exec("fetchEquipment")
            ctx.d.exec("fetchEquipment")
            assert ctx.api.authCalls == 1
        }
    }

    void testRefreshDuringBackoffDoesNotLogIn() {
        FILES.each { f ->
            def ctx = start(f) { it.rejectCredentials = true }
            ctx.d.exec("refresh")
            assert ctx.api.authCalls == 1
            assert ctx.d.log.at("info").any { it.contains("next login attempt is in") }
        }
    }

    void testPollAfterBackoffExpiresLogsIn() {
        FILES.each { f ->
            def ctx = start(f) { it.authStatus = 500 }
            ctx.api.authStatus = null
            ctx.d.advance(121_000)
            ctx.d.exec("fetchEquipment")
            assert ctx.api.authCalls == 2
            assert ctx.d.state.userToken
            assert ctx.d.state.loginFailures == null, "a successful login clears the backoff"
        }
    }

    void testSavingPreferencesRetriesAtOnce() {
        FILES.each { f ->
            def ctx = start(f) { it.rejectCredentials = true }
            ctx.api.rejectCredentials = false
            ctx.d.exec("updated")
            assert ctx.api.authCalls == 2
            assert ctx.d.state.userToken
            assert ctx.d.runInsFor("login").isEmpty()
        }
    }

    void testExpiredSessionOnPollReauthenticates() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.d.advance(3_600_000)
            ctx.api.pollStatus = 401
            ctx.api.pollFailTimes = 1
            ctx.d.exec("fetchEquipment")
            assert ctx.api.authCalls == 2
            assert ctx.api.pollCalls == 3, "the new session polls straight away"
            assert ctx.d.runInsFor("login").isEmpty()
        }
    }

    void testFreshSessionRefusedBacksOffInsteadOfLooping() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.api.pollStatus = 401
            ctx.d.advance(10_000)   // within a minute of logging in
            ctx.d.exec("fetchEquipment")
            assert ctx.api.authCalls == 1
            assert ctx.d.runInsFor("login")*.seconds == [120L]
        }
    }

    void testExpiredSessionOnCommandRetriesOnce() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.d.advance(3_600_000)
            def calls = 0
            def real = ctx.api.handler()
            ctx.d.httpHandler = { Map p ->
                if (p.uri.toString().contains("/publish") && calls++ == 0) throw new HttpResponseException(401)
                real.call(p)
            }
            ctx.d.exec("setAwayMode", "away")
            assert ctx.api.authCalls == 2
            assert ctx.api.published.size() == 1
            assert ctx.d.current("awayMode") == "away"
        }
    }

    void testFailedCommandDoesNotUpdateAttributes() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.api.publishStatus = 500
            ctx.d.exec("setAwayMode", "away")
            assert ctx.d.current("awayMode") == "home"
            assert ctx.d.log.at("error").any { it.contains("publishCommand") }
        }
    }

    void testAcceptedCommandSchedulesConfirmingPoll() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.d.exec("setAwayMode", "away")
            assert ctx.d.runInsFor("fetchEquipment")*.seconds == [5L]
        }
    }

    void testCommandsAreRefusedWhenNotLoggedIn() {
        FILES.each { f ->
            def ctx = start(f) { it.rejectCredentials = true }
            ctx.d.exec("setAwayMode", "away")
            assert ctx.api.published.isEmpty()
            assert ctx.d.log.at("error").any { it.contains("not logged in") }
        }
    }

    void testDebugLoggingSwitchesItselfOff() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.d.exec("updated")
            assert ctx.d.runInsFor("logsOff")*.seconds == [1800L]
            ctx.d.fire("logsOff")
            assert ctx.d.settings.logEnable == false
        }
    }

    // ---- failed polls -----------------------------------------------------------------

    void testABriefOutageLogsOneWarningAndOneRecovery() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.d.log.clear()
            ctx.api.pollError = new java.net.SocketTimeoutException("Read timed out")
            10.times { ctx.d.advance(60_000); ctx.d.exec("fetchEquipment") }
            assert ctx.d.log.at("error").isEmpty(), f
            assert ctx.d.log.at("warn").size() == 1
            assert ctx.d.log.at("warn")[0].contains("Read timed out")
            ctx.api.pollError = null
            ctx.d.advance(60_000)
            ctx.d.exec("fetchEquipment")
            def back = ctx.d.log.at("info").findAll { it.contains("reaching EcoNet again") }
            assert back.size() == 1
            assert back[0].contains("10 failed polls over 10 minutes"), back[0]
            assert !ctx.d.state.containsKey("pollFailures")
        }
    }

    void testAnOutageOverHalfAnHourBecomesOneError() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.api.pollError = new UnknownHostException("rheem.rheemconnect.com: Temporary failure in name resolution")
            61.times { ctx.d.advance(60_000); ctx.d.exec("fetchEquipment") }
            def errors = ctx.d.log.at("error")
            assert errors.size() == 1, f
            assert errors[0].contains("after 30 minutes") && errors[0].contains("name resolution")
        }
    }

    void testEveryKindOfPollFailureCounts() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.api.pollStatus = 400
            ctx.d.exec("fetchEquipment")
            ctx.api.pollStatus = null
            ctx.api.pollUnsuccessful = true
            ctx.d.exec("fetchEquipment")
            assert ctx.d.log.at("error").isEmpty()
            assert ctx.d.state.pollFailures == 2
            assert ctx.d.log.at("warn").count { it.contains("couldn't reach EcoNet") && it.contains("400") } == 1
        }
    }

    void testAFailedPollKeepsTheLastReadings() {
        FILES.each { f ->
            def ctx = start(f)
            def before = new LinkedHashMap(ctx.d.device.current)
            ctx.api.pollError = new java.net.SocketTimeoutException("Read timed out")
            ctx.d.exec("fetchEquipment")
            assert ctx.d.device.current == before
        }
    }

    void testSaveClearsAnOutageInProgress() {
        FILES.each { f ->
            def ctx = start(f)
            ctx.api.pollError = new java.net.SocketTimeoutException("Read timed out")
            ctx.d.exec("fetchEquipment")
            ctx.api.pollError = null
            ctx.d.exec("updated")
            assert !ctx.d.state.containsKey("pollFailures")
        }
    }
}
