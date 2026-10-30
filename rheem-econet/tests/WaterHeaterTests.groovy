import groovy.json.JsonSlurper

/**
 * Water heater attributes and commands.
 *
 * Confirmed on two heat pump units so far (Gen 4 and Gen 5); gas, tankless and
 * electric-only heaters have not run it. The record shapes follow pyeconet; the
 * three control styles are @MODE only, @ENABLED + @MODE, and @ENABLED only.
 */
class WaterHeaterTests {
    static final String FILE = "EcoNetWaterHeater.groovy"
    static final List HEAT_PUMP_MODES = ["Off", "Energy Saving", "Heat Pump Only", "High Demand", "Electric Mode", "Vacation"]

    static Map start(Map unit = [:], Map settings = [:]) {
        def ctx = Fixtures.connected(FILE, Fixtures.account([Fixtures.waterHeater(unit)]), settings)
        ctx.d.exec("initialize")
        return ctx
    }

    static List json(def v) { new JsonSlurper().parseText(v as String) as List }

    static String modesAttr(DriverBase d) {
        d.meta.attributes*.name.contains("supportedWaterHeaterModes") ? "supportedWaterHeaterModes" : "supportedModes"
    }

    // ---- reading --------------------------------------------------------------------

    void testAttributesFromAPoll() {
        def d = start([mode: "Energy Saving", setpoint: 120]).d
        assert d.current("waterHeaterMode") == "energy saving"
        assert d.current("switch") == "on"
        assert d.current("thermostatMode") == "auto"
        assert d.current("heatingSetpoint") == 120
        assert d.current("thermostatSetpoint") == 120
        assert d.current("thermostatOperatingState") == "idle"
        assert d.current("hotWaterLevel") == 100
        assert d.current("online") == "true"
        assert d.current("awayMode") == "home"
        assert json(d.current(modesAttr(d))) ==
               ["off", "energy saving", "heat pump", "high demand", "electric", "vacation"]
        assert json(d.current("supportedThermostatModes")) == ["heat", "auto", "emergency heat", "off"]
    }

    void testRunningMeansHeating() {
        assert start([running: "Heating"]).d.current("thermostatOperatingState") == "heating"
    }

    void testModeToThermostatModeMapping() {
        def expected = ["Heat Pump Only": "heat", "Electric Mode": "heat", "High Demand": "emergency heat",
                        "Energy Saving": "auto", "Off": "off", "Vacation": "off"]
        expected.each { mode, tMode ->
            assert start([mode: mode]).d.current("thermostatMode") == tMode, mode
        }
    }

    void testInactiveModesTurnTheSwitchOff() {
        assert start([mode: "Vacation"]).d.current("switch") == "off"
        assert start([mode: "Off"]).d.current("switch") == "off"
        assert start([mode: "Heat Pump Only", enabled: 0]).d.current("switch") == "off"
        assert start([mode: "Heat Pump Only", enabled: 0]).d.current("waterHeaterMode") == "off"
    }

    void testElectricGasEntryResolvesByHeaterType() {
        def modes = ["Off", "Electric/Gas", "Vacation"]
        assert start([type: "gasWaterHeater", modes: modes, mode: "Electric/Gas"]).d.current("waterHeaterMode") == "gas"
        assert start([type: "tanklessWaterHeater", modes: modes, mode: "Electric/Gas"]).d.current("waterHeaterMode") == "gas"
        assert start([type: "electricWaterHeater", modes: modes, mode: "Electric/Gas"]).d.current("waterHeaterMode") == "electric"
    }

    void testFirmwareAliasesAreRecognized() {
        def modes = ["Off", "Energy Saver", "Heat Pump", "Vacation"]
        def d = start([modes: modes, mode: "Heat Pump"]).d
        assert d.current("waterHeaterMode") == "heat pump"
        assert json(d.current(modesAttr(d))) == ["off", "energy saving", "heat pump", "vacation"]
    }

    void testHeaterWithNoModeControlsReportsItsInferredMode() {
        def d = start([type: "gasWaterHeater", modes: null]).d
        assert d.current("waterHeaterMode") == "gas"
        assert json(d.current(modesAttr(d))) == ["gas"]
    }

    void testEnabledOnlyHeaterOffersOffAndItsHeatingMode() {
        def d = start([type: "electricWaterHeater", modes: null, enabled: 1]).d
        assert d.current("waterHeaterMode") == "electric"
        assert json(d.current(modesAttr(d))).sort() == ["electric", "off"]
        assert json(d.current("supportedThermostatModes")).sort() == ["heat", "off"]
    }

    void testHotWaterLevelIcons() {
        def icons = ["ic_tank_hundread_percent.png": 100, "ic_tank_fourty_percent.png": 66,
                     "ic_tank_ten_percent.png": 33, "ic_tank_empty.png": 0, "ic_tank_zero_percent.png": 0]
        icons.each { icon, level ->
            assert start([hotWater: icon]).d.current("hotWaterLevel") == level, icon
        }
        assert start([hotWater: "ic_something_new.png"]).d.current("hotWaterLevel") == null
    }

    // ---- commands ---------------------------------------------------------------------

    void testModeCommandOnModeOnlyHeater() {
        def ctx = start()
        ctx.d.exec("setWaterHeaterMode", "heat pump")
        assert ctx.api.lastCommand() == ["@MODE": 2]
        assert ctx.d.current("waterHeaterMode") == "heat pump"
        assert ctx.d.current("thermostatMode") == "heat"
        ctx.d.exec("off")
        assert ctx.api.lastCommand() == ["@MODE": 0]
        assert ctx.d.current("switch") == "off"
    }

    void testModeCommandOnEnabledPlusModeHeater() {
        def ctx = start([enabled: 1])
        ctx.d.exec("setWaterHeaterMode", "high demand")
        assert ctx.api.lastCommand() == ["@ENABLED": 1, "@MODE": 3]
        ctx.d.exec("off")
        assert ctx.api.lastCommand() == ["@ENABLED": 0], "off goes through @ENABLED alone"
    }

    void testModeTheHeaterLacksIsRefused() {
        def ctx = start([enabled: 1])
        ctx.d.exec("setWaterHeaterMode", "gas")
        assert ctx.api.published.isEmpty(), "@ENABLED alone would switch on in some other mode"
        assert ctx.d.log.at("error").any { it.contains("not found in device modes") }
    }

    void testVacationTurnsTheSwitchOff() {
        def ctx = start()
        ctx.d.exec("setWaterHeaterMode", "vacation")
        assert ctx.api.lastCommand() == ["@MODE": 5]
        assert ctx.d.current("switch") == "off"
        assert ctx.d.current("thermostatMode") == "off"
    }

    void testOnRestoresTheLastActiveMode() {
        def ctx = start([mode: "High Demand"])
        ctx.d.exec("off")
        ctx.d.exec("on")
        assert ctx.api.lastCommand() == ["@MODE": 3]
        assert ctx.d.current("waterHeaterMode") == "high demand"
    }

    void testLastActiveModeSurvivesAHubRestart() {
        def ctx = start([mode: "High Demand"])
        ctx.d.exec("off")
        ctx.d.exec("initialize")   // runs at every hub startup
        ctx.api.locations = Fixtures.account([Fixtures.waterHeater([mode: "Off"])])
        ctx.d.exec("fetchEquipment")
        ctx.d.exec("on")
        assert ctx.api.lastCommand() == ["@MODE": 3]
    }

    void testOnWithNothingRememberedUsesEnabledAlone() {
        def ctx = start([mode: "Off", enabled: 0])
        ctx.d.state.remove("lastActiveMode")
        ctx.d.exec("on")
        assert ctx.api.lastCommand() == ["@ENABLED": 1]
        assert ctx.d.current("switch") == "on"
    }

    void testOnWithNothingRememberedPicksTypeDefault() {
        def ctx = start([mode: "Off"])
        ctx.d.state.remove("lastActiveMode")
        ctx.d.exec("on")
        assert ctx.api.lastCommand() == ["@MODE": 1], "energy saving on a heat pump heater"
    }

    void testThermostatModesMapToBestHeaterMode() {
        def expected = [heat: 2, auto: 1, emergencyHeat: 3, off: 0]
        expected.each { cmd, idx ->
            def ctx = start()
            ctx.d.exec(cmd)
            assert ctx.api.lastCommand() == ["@MODE": idx], cmd
        }
        def ctx = start([modes: ["Off", "Electric Mode", "Vacation"], mode: "Off", type: "electricWaterHeater"])
        ctx.d.exec("heat")
        assert ctx.api.lastCommand() == ["@MODE": 1]
    }

    void testCoolIsIgnoredWithoutError() {
        def ctx = start()
        ctx.d.exec("cool")
        assert ctx.api.published.isEmpty()
        assert ctx.d.log.at("warn").any { it.contains("no cooling mode") }
    }

    void testEmergencyHeatWithoutHighDemandWarns() {
        def ctx = start([modes: ["Off", "Electric Mode"], mode: "Electric Mode"])
        ctx.d.exec("emergencyHeat")
        assert ctx.api.published.isEmpty()
    }

    void testSetpointCommands() {
        def ctx = start()
        ctx.d.exec("setHeatingSetpoint", 125)
        assert ctx.api.lastCommand() == ["@SETPOINT": 125]
        assert ctx.d.current("heatingSetpoint") == 125
        assert ctx.d.current("thermostatSetpoint") == 125
        ctx.d.exec("setHeatingSetpoint", 150)
        assert ctx.api.published.size() == 1
        assert ctx.d.log.at("error").any { it.contains("out of range") }
    }

    void testSetpointRoundsAndAcceptsText() {
        def ctx = start()
        ctx.d.exec("setHeatingSetpoint", 121.5d)
        assert ctx.api.lastCommand() == ["@SETPOINT": 122]
        ctx.d.exec("setHeatingSetpoint", "124")
        assert ctx.api.lastCommand() == ["@SETPOINT": 124]
    }

    void testCelsiusSetpoint() {
        def ctx = start([setpoint: 120], [tempUnit: "C"])
        assert ctx.d.current("heatingSetpoint") == 48.9
        ctx.d.exec("setHeatingSetpoint", 50)
        assert ctx.api.lastCommand() == ["@SETPOINT": 122]
        assert ctx.d.current("heatingSetpoint") == 50.0
    }

    // ---- a real Gen 5 unit ----------------------------------------------------------

    // Mode list, type and setpoint range as a Heat Pump Water Heater Gen 5 reported them
    // (from a user's device page, 2026-10-29). Note the trailing spaces in the enum text.
    static final List GEN5_MODES = ["Off ", "Energy Saver ", "Heat Pump ", "High Demand ", "Electric/Gas ", "Vacation "]

    void testGen5HeatPumpUnit() {
        def ctx = start([type: "heatpumpWaterHeaterGen5", modes: GEN5_MODES, mode: "Energy Saver ", enabled: 1])
        def d = ctx.d
        assert d.current("waterHeaterMode") == "energy saving"
        assert json(d.current("supportedWaterHeaterModes")) ==
               ["off", "energy saving", "heat pump", "high demand", "electric", "vacation"]
        assert json(d.current("supportedThermostatModes")) == ["heat", "auto", "emergency heat", "off"]
        d.exec("setWaterHeaterMode", "vacation")
        assert ctx.api.lastCommand() == ["@ENABLED": 1, "@MODE": 5]
        d.exec("setWaterHeaterMode", "electric")
        assert ctx.api.lastCommand() == ["@ENABLED": 1, "@MODE": 4], "Electric/Gas is electric on a heat pump"
        d.exec("heat")
        assert ctx.api.lastCommand() == ["@ENABLED": 1, "@MODE": 2]
    }

    // ---- Product Health ---------------------------------------------------------------

    void testProductHealth() {
        def d = start([leakInstalled: 1, valve: "Shut-OFF Valve - Open", tank: 85, combustion: 60]).d
        assert d.current("leakSensor") == "connected"
        assert d.current("shutoffValve") == "open"
        assert d.current("tankHealth") == 85
        assert d.current("compressorHealth") == 60
        assert d.eventsNamed("tankHealth")[-1].unit == "%"
    }

    void testClosedValveAndNoLeakSensor() {
        def d = start([leakInstalled: 0, valve: "Shut-OFF Valve - Closed"]).d
        assert d.current("leakSensor") == "not connected"
        assert d.current("shutoffValve") == "closed"
    }

    void testHealthAttributesOnlyWhenReported() {
        def d = start().d
        ["leakSensor", "shutoffValve", "tankHealth", "compressorHealth"].each { assert d.eventsNamed(it).isEmpty(), it }
    }

    void testUnrecognizedHealthValuesAreSkipped() {
        def d = start([valve: "Something else", tank: "n/a", combustion: null]).d
        assert d.eventsNamed("shutoffValve").isEmpty()
        assert d.eventsNamed("tankHealth").isEmpty()
        assert d.eventsNamed("compressorHealth").isEmpty()
        assert start([tank: "72.6"]).d.current("tankHealth") == 73
    }

    void testCompressorHealthOnlyOnHeatPumps() {
        def d = start([type: "gasWaterHeater", modes: null, tank: 90, combustion: 70]).d
        assert d.current("tankHealth") == 90
        assert d.eventsNamed("compressorHealth").isEmpty(), "@COMBUSTION on a gas heater is not a compressor"
        assert start([type: "heatpumpWaterHeaterGen5", combustion: 70]).d.current("compressorHealth") == 70
    }

    void testHealthStatusText() {
        // As a Gen 5 heat pump unit reported it (2026-10-29)
        def d = start([type: "heatpumpWaterHeaterGen5", tank: 100, tankStatus: "Element operating normally",
                       combustion: 100, combustionStatus: "Compressor life is normal"]).d
        assert d.current("tankHealthStatus") == "Element operating normally"
        assert d.current("compressorHealthStatus") == "Compressor life is normal"
        def plain = start([tank: 90]).d
        assert plain.eventsNamed("tankHealthStatus").isEmpty(), "no status, no attribute"
        def gas = start([type: "gasWaterHeater", modes: null, combustion: 70, combustionStatus: "Combustion OK"]).d
        assert gas.eventsNamed("compressorHealthStatus").isEmpty()
    }

    void testEveryFieldLoggedOncePerSessionWithoutIdentifiers() {
        def unit = Fixtures.waterHeater([leakInstalled: 1, tank: 85])
        unit["@CONFIG"] = [value: "contains 00-00-00-00-00-00-bb-01-01 and DEVICE-WH-0001"]
        def ctx = Fixtures.connected(FILE, Fixtures.account([unit]))
        ctx.d.exec("initialize")
        def debug = { ctx.d.log.at("debug") }
        assert debug().count { it.startsWith("EcoNet WH: Fields reported:") } == 1
        assert debug().any { it.contains('Field @TANK = {"value":85,"status":""}') }
        assert debug().any { it.contains("Field @LEAKINSTALLED = ") }
        assert !debug().any { it.contains("Field @NAME") }, "the unit's name stays out"
        def config = debug().find { it.contains("Field @CONFIG") }
        assert config.contains("…1-01") && config.contains("…0001")
        assert !config.contains("00-00-00-00-00-00-bb-01-01") && !config.contains("DEVICE-WH-0001")
        int lines = debug().size()
        ctx.d.exec("fetchEquipment")
        assert !debug().drop(lines).any { it.contains("Field ") }, "once a session"
    }

    // ---- energy and water use ---------------------------------------------------------

    // The test clock is 2027-01-15 08:00 UTC, and the hub's time zone UTC unless a
    // test sets one: 08:00 on 2027-01-15, so yesterday is 2027-01-14. Entries are named
    // for the hour they end, so the current hour (08:00–09:00) is entry 9, and entry 0
    // is 23:00–24:00.
    static final Map TODAY_KWH     = [1: 0.1, 5: 0.6, 9: 0.4]
    static final Map YESTERDAY_KWH = [3: 1.0, 9: 5.0, 10: 0.7, 0: 0.2]

    static List usageRequests(Map ctx, String type = null, String day = null) {
        ctx.api.usageRequests.findAll { r ->
            (!type || r.usage_type == type) && (!day || (r.start_date as String).startsWith(day))
        }
    }

    void testUsageIsScheduledAtFivePastEveryHour() {
        def d = start().d
        assert d.scheduled.any { it.kind == "cron" && it.cron == "0 5 * * * ?" && it.method == "fetchUsage" }
        assert d.runInsFor("fetchUsage").size() == 1, "and soon after connecting"
        d.exec("fetchEquipment")
        assert d.runInsFor("fetchUsage").size() == 1, "not rescheduled on every poll"
    }

    void testEnergyTodayAndLast24Hours() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.d.fire("fetchUsage")
        assert ctx.d.current("energyToday") == 1.1
        assert ctx.d.current("energy") == 1.1, "EnergyMeter's attribute carries the same value"
        assert ctx.d.eventsNamed("energy")[-1].unit == "kWh"
        assert ctx.d.eventsNamed("energyToday")[-1].unit == "kWh"
        // Today's entries 1–9, plus yesterday's 10–24, including its last hour ("0")
        assert ctx.d.current("energyLast24Hours") == 2.0
        assert ctx.d.eventsNamed("energyLast24Hours")[-1].unit == "kWh"

        def req = usageRequests(ctx, "energyUsage", "2027-01-15")[0]
        assert req.ACTION == "waterheaterUsageReportView"
        assert req.device_name == "DEVICE-WH-0001"
        assert req.serial_number == "00-00-00-00-00-00-bb-01-01"
        assert req.start_date == "2027-01-15T00:00:00.999Z"
        assert req.end_date == "2027-01-15T23:59:59.999Z"
        assert usageRequests(ctx, "energyUsage", "2027-01-14").size() == 1
    }

    void testDaysFollowTheHubTimeZone() {
        def ctx = start()
        ctx.d.location.timeZone = TimeZone.getTimeZone("America/Chicago")   // 02:00 on 2027-01-15
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.d.fire("fetchUsage")
        def req = usageRequests(ctx, "energyUsage", "2027-01-15")[0]
        assert req.start_date == "2027-01-15T00:00:00.999-06:00"
        assert req.end_date == "2027-01-15T23:59:59.999-06:00"
        // 02:00: today's entries 1–3, plus yesterday's 4–24 (9, 10 and 0)
        assert ctx.d.current("energy") == 1.1, "today's total counts every hour reported"
        assert ctx.d.current("energyLast24Hours") == 6.0
    }

    void testYesterdayIsKeptOnceItHasSettled() {
        def ctx = start()
        ctx.d.location.timeZone = TimeZone.getTimeZone("America/Chicago")   // 02:00
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.d.fire("fetchUsage")
        ctx.d.advance(3_600_000)   // 03:00: fetched before 3 am, so once more
        ctx.d.exec("fetchUsage")
        ctx.d.advance(3_600_000)   // 04:00: kept
        ctx.d.exec("fetchUsage")
        assert usageRequests(ctx, "energyUsage", "2027-01-14").size() == 2
        assert usageRequests(ctx, "energyUsage", "2027-01-15").size() == 3
        ctx.d.advance(24 * 3_600_000L)   // the next day: its yesterday is new
        ctx.d.exec("fetchUsage")
        assert usageRequests(ctx, "energyUsage", "2027-01-15").size() == 4
    }

    void testANewDayStartsTodaysTotalAgain() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH,
                                       "2027-01-16": [1: 0.3]]]
        ctx.d.fire("fetchUsage")
        ctx.d.advance(16 * 3_600_000L + 5 * 60_000L)   // 00:05 on 2027-01-16
        ctx.d.exec("fetchUsage")
        assert ctx.d.current("energy") == 0.3
        assert ctx.d.current("energyLast24Hours") == 1.3, "today's entry 1 (0.3) plus yesterday's 2–24 (0.6 + 0.4)"
    }

    void testWaterUsage() {
        def ctx = start()
        ctx.api.usage = [waterUsage: ["2027-01-15": [6: 12.5, 7: 3.0], "2027-01-14": [20: 30.0, 9: 99.0]]]
        ctx.d.fire("fetchUsage")
        assert ctx.d.current("waterUsageToday") == 15.5
        assert ctx.d.current("waterUsageLast24Hours") == 45.5
        assert ctx.d.eventsNamed("waterUsageToday")[-1].unit == "gal"
        assert ctx.d.current("energy") == null
        assert ctx.d.log.at("info").any { it.contains("doesn't report energy use") }

        def metric = start()
        metric.api.usage = [waterUsage: ["2027-01-15": [6: 40]]]
        metric.api.usageMessages.waterUsage = "Water Usage in liters"
        metric.d.fire("fetchUsage")
        assert metric.d.eventsNamed("waterUsageToday")[-1].unit == "L"
    }

    void testUnsupportedKindsStopBeingAsked() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.d.fire("fetchUsage")
        ctx.d.exec("fetchUsage")
        assert usageRequests(ctx, "waterUsage").size() == 1
        assert ctx.d.log.at("info").count { it.contains("doesn't report water use") } == 1
        assert ctx.d.scheduled.any { it.method == "fetchUsage" }, "energy still needs the hourly run"

        def none = start()
        none.d.fire("fetchUsage")
        assert !none.d.scheduled.any { it.method == "fetchUsage" }, "nothing left to ask for"
        none.d.exec("fetchEquipment")
        assert none.d.runInsFor("fetchUsage").isEmpty()
    }

    void testGasEnergyInKbtuIsNotReportedAsKwh() {
        def ctx = start([type: "gasWaterHeater", modes: null])
        ctx.api.usage = [energyUsage: ["2027-01-15": [1: 3]], waterUsage: ["2027-01-15": [1: 5]]]
        ctx.api.usageMessages.energyUsage = "Energy Usage in kBTU"
        ctx.d.fire("fetchUsage")
        assert ctx.d.current("energy") == null
        assert ctx.d.current("energyToday") == null
        assert ctx.d.current("energyLast24Hours") == null
        assert ctx.d.log.at("info").count { it.contains("kBTU") } == 1
        assert ctx.d.current("waterUsageToday") == 5

        def unnamed = start([type: "gasWaterHeater", modes: null])
        unnamed.api.usage = [energyUsage: ["2027-01-15": [1: 3]]]
        unnamed.api.usageMessages.energyUsage = "no unit named"
        unnamed.d.fire("fetchUsage")
        assert unnamed.d.current("energy") == null, "a gas heater with no unit named is kBTU"
    }

    void testUsageFailureWarnsOnceThenRecovers() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": [0: 0.5], "2027-01-14": [:]]]
        ctx.api.usageStatus = 500
        ctx.d.fire("fetchUsage")
        ctx.d.exec("fetchUsage")
        assert ctx.d.log.at("warn").count { it.contains("today's energy use") } == 1
        assert ctx.d.log.at("warn").count { it.contains("today's water use") } == 1
        assert ctx.d.scheduled.any { it.method == "fetchUsage" }, "keeps trying hourly"
        ctx.api.usageStatus = null
        ctx.d.exec("fetchUsage")
        assert ctx.d.current("energy") == 0.5
        ctx.api.usageStatus = 500
        ctx.d.exec("fetchUsage")
        assert ctx.d.log.at("warn").count { it.contains("today's energy use") } == 2, "warns again after recovering"
    }

    void testNoLast24HoursWithoutYesterday() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH]]
        def real = ctx.d.httpHandler
        ctx.d.httpHandler = { Map p ->
            if ((p.body as String)?.contains("2027-01-14")) throw new groovyx.net.http.HttpResponseException(500)
            real(p)
        }
        ctx.d.fire("fetchUsage")
        assert ctx.d.current("energy") == 1.1
        assert ctx.d.current("energyLast24Hours") == null, "a partial window would understate it"
    }

    void testUsageWaitsForASession() {
        def ctx = start()
        ctx.d.state.remove("userToken")
        ctx.d.exec("fetchUsage")
        assert ctx.api.usageRequests.isEmpty()
    }

    void testUsageLogsNoIdentifiers() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.d.fire("fetchUsage")
        ctx.api.usageStatus = 500
        ctx.d.exec("fetchUsage")
        def all = ctx.d.log.all().join("\n")
        ["DEVICE-WH-0001", "00-00-00-00-00-00-bb-01-01", "00000000000000bb0101"].each { assert !all.contains(it), it }
    }

    void testRollingWindowBoundaries() {
        // Real Gen 5 data shape: 24 entries arriving 1…23 then 0. At 18:24 CDT the app's
        // 24 Hrs. total was 2.2 and today's message said 1.6 kWh.
        def ctx = start()
        ctx.d.location.timeZone = TimeZone.getTimeZone("America/Chicago")
        ctx.d.clock = Date.parse("yyyy-MM-dd HH:mm Z", "2026-10-29 18:24 -0500").time
        def day = { Map v -> ((1..23) + [0]).collectEntries { h -> [(h): v[h] ?: 0] } }
        ctx.api.usage = [energyUsage: [
            "2026-10-29": day([3: 0.1, 9: 0.7, 11: 0.4, 15: 0.3, 17: 0.1]),
            "2026-10-28": day([7: 0.1, 9: 0.5, 11: 0.7, 17: 0.1, 21: 0.6])]]
        ctx.d.exec("fetchUsage")
        assert ctx.d.current("energy") == 1.6
        assert ctx.d.current("energyLast24Hours") == 2.2

        // The hour now in progress counts, and yesterday's matching hour doesn't
        ctx.api.usage.energyUsage["2026-10-29"][19] = 0.5
        ctx.api.usage.energyUsage["2026-10-28"][19] = 9.0
        ctx.api.usage.energyUsage["2026-10-28"][20] = 0.2
        ctx.api.usage.energyUsage["2026-10-28"][0] = 0.3
        ctx.d.state.remove("energyYesterday")
        ctx.d.exec("fetchUsage")
        assert ctx.d.current("energyLast24Hours") == 3.2   // 2.1 today + 0.2 + 0.6 + 0.3 yesterday
    }

    void testUsageMessageIsTidiedForTheLog() {
        def ctx = start()
        ctx.api.usage = [energyUsage: ["2027-01-15": TODAY_KWH, "2027-01-14": YESTERDAY_KWH]]
        ctx.api.usageMessages.energyUsage = "\nYou've used <b>1.1</b> kWh units of energy\n\n"
        ctx.d.fire("fetchUsage")
        assert ctx.d.log.at("debug").any { it.endsWith("(You've used 1.1 kWh units of energy)") }
        assert ctx.d.current("energy") == 1.1
    }
}
