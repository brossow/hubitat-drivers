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

    void testRawHealthFieldsLoggedOncePerSession() {
        def ctx = start([leakInstalled: 1, tank: 85])
        def lines = { ctx.d.log.at("debug").findAll { it.contains("Product Health fields") } }
        assert lines().size() == 1
        assert lines()[0].contains('@TANK={"value":85}')
        assert lines()[0].contains("@COMBUSTION=absent")
        assert ctx.d.log.at("debug").any { it.contains("Fields reported:") && it.contains("@LEAKINSTALLED") }
        ctx.d.exec("fetchEquipment")
        assert lines().size() == 1
    }

    // ---- energy used today ------------------------------------------------------------

    static Map energyData(List values, String message = "Energy Usage in kWh") {
        def data = []
        values.eachWithIndex { v, i -> data << [name: "${i}".toString(), value: v] }
        [data: data, historyData: [], message: message]
    }

    void testEnergyIsScheduledHourlyAndSoonAfterConnecting() {
        def d = start().d
        assert d.scheduled.any { it.kind == "every" && it.minutes == 60 && it.method == "fetchEnergyUsage" }
        assert d.runInsFor("fetchEnergyUsage").size() == 1
        d.exec("fetchEquipment")
        assert d.runInsFor("fetchEnergyUsage").size() == 1, "not rescheduled on every poll"
    }

    void testEnergyUsedToday() {
        def ctx = start()
        ctx.d.location.timeZone = TimeZone.getTimeZone("America/Chicago")
        ctx.api.energyUsage = energyData([0, 0.1, 0, 0.6, 0, 0.1, 0.7, 0.4])
        ctx.d.fire("fetchEnergyUsage")
        assert ctx.d.current("energy") == 1.9
        assert ctx.d.eventsNamed("energy")[-1].unit == "kWh"

        def req = ctx.api.energyRequests[-1]
        assert req.ACTION == "waterheaterUsageReportView"
        assert req.usage_type == "energyUsage"
        assert req.device_name == "DEVICE-WH-0001"
        assert req.serial_number == "00-00-00-00-00-00-bb-01-01"
        // The test clock is 2027-01-15 08:00 UTC: 02:00 that morning in Chicago
        assert req.start_date == "2027-01-15T00:00:00.999-06:00"
        assert req.end_date == "2027-01-15T23:59:59.999-06:00"
    }

    void testEnergyNotSupportedStopsAsking() {
        def ctx = start()
        ctx.api.energySupported = false
        ctx.d.fire("fetchEnergyUsage")
        assert ctx.d.current("energy") == null
        assert ctx.d.log.at("info").any { it.contains("doesn't report energy use") }
        assert !ctx.d.scheduled.any { it.method == "fetchEnergyUsage" }
        ctx.d.exec("fetchEnergyUsage")
        ctx.d.exec("fetchEquipment")
        assert ctx.api.energyRequests.size() == 1
        assert ctx.d.runInsFor("fetchEnergyUsage").isEmpty()
    }

    void testGasEnergyInKbtuIsNotReportedAsKwh() {
        def ctx = start([type: "gasWaterHeater", modes: null])
        ctx.api.energyUsage = energyData([3, 4], "Energy Usage in kBTU")
        ctx.d.fire("fetchEnergyUsage")
        assert ctx.d.current("energy") == null
        assert ctx.d.log.at("info").count { it.contains("kBTU") } == 1
        ctx.api.energyUsage = energyData([3, 4], "no unit named")
        ctx.d.exec("fetchEnergyUsage")
        assert ctx.d.current("energy") == null, "a gas heater with no unit named is kBTU"
    }

    void testEnergyFailureWarnsOnceThenRecovers() {
        def ctx = start()
        ctx.api.energyStatus = 500
        ctx.d.fire("fetchEnergyUsage")
        ctx.d.exec("fetchEnergyUsage")
        assert ctx.d.log.at("warn").count { it.contains("today's energy use") } == 1
        assert ctx.d.scheduled.any { it.kind == "every" && it.method == "fetchEnergyUsage" }, "keeps trying hourly"
        ctx.api.energyStatus = null
        ctx.api.energyUsage = energyData([0.5])
        ctx.d.exec("fetchEnergyUsage")
        assert ctx.d.current("energy") == 0.5
        ctx.api.energyStatus = 500
        ctx.d.exec("fetchEnergyUsage")
        assert ctx.d.log.at("warn").count { it.contains("today's energy use") } == 2, "warns again after recovering"
    }

    void testEnergyWaitsForASession() {
        def ctx = start()
        ctx.d.state.remove("userToken")
        ctx.d.exec("fetchEnergyUsage")
        assert ctx.api.energyRequests.isEmpty()
    }

    void testEnergyLogsNoIdentifiers() {
        def ctx = start()
        ctx.api.energyUsage = energyData([0.2])
        ctx.d.fire("fetchEnergyUsage")
        ctx.api.energyStatus = 500
        ctx.d.exec("fetchEnergyUsage")
        def all = ctx.d.log.all().join("\n")
        ["DEVICE-WH-0001", "00-00-00-00-00-00-bb-01-01", "00000000000000bb0101"].each { assert !all.contains(it), it }
    }
}
