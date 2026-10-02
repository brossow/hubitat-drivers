import groovy.json.JsonSlurper

/**
 * Water heater attributes and commands.
 *
 * No real water heater has run this driver yet, so these tests are its main check.
 * The record shapes follow pyeconet; the three control styles are @MODE only,
 * @ENABLED + @MODE, and @ENABLED only.
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

    void testCelsiusSetpoint() {
        def ctx = start([setpoint: 120], [tempUnit: "C"])
        assert ctx.d.current("heatingSetpoint") == 48.9
        ctx.d.exec("setHeatingSetpoint", 50)
        assert ctx.api.lastCommand() == ["@SETPOINT": 122]
    }
}
