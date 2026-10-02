import groovy.json.JsonSlurper

/** Thermostat attributes and commands. */
class ThermostatTests {
    static final String FILE = "EcoNetThermostat.groovy"

    static Map start(Map unit = [:], Map settings = [:]) {
        def ctx = Fixtures.connected(FILE, Fixtures.account([Fixtures.thermostat(unit)]), settings)
        ctx.d.exec("initialize")
        return ctx
    }

    static List json(def v) { new JsonSlurper().parseText(v as String) as List }

    // ---- reading --------------------------------------------------------------------

    void testAttributesFromAPoll() {
        def d = start([mode: "Cooling", temperature: 72, cool: 75, heat: 68]).d
        assert d.current("temperature") == 72
        assert d.current("coolingSetpoint") == 75
        assert d.current("heatingSetpoint") == 68
        assert d.current("thermostatMode") == "cool"
        assert d.current("thermostatSetpoint") == 75
        assert json(d.current("supportedThermostatModes")) == ["heat", "cool", "auto", "fan only", "off"]
        assert d.current("humidity") == 45
        assert d.current("online") == "true"
        assert d.current("awayMode") == "home"
        assert d.current("fanSpeed") == "auto"
        assert d.current("thermostatFanMode") == "auto"
        assert json(d.current("supportedThermostatFanModes")) == ["auto", "circulate"]
    }

    void testOperatingStateFromObservedRunningStatus() {
        // Strings seen on real hardware, with the mode the unit was in at the time.
        def cases = [
            [mode: "Cooling", running: "",                 expect: "idle"],
            [mode: "Cooling", running: "Cooling to 69 °",  expect: "cooling"],
            [mode: "Heating", running: "",                 expect: "idle"],
            [mode: "Heating", running: "Heating to 73 °",  expect: "heating"],
            [mode: "Auto",    running: "",                 expect: "idle"],
            [mode: "Auto",    running: "Heating to 75 °",  expect: "heating"],
            [mode: "Auto",    running: "Cooling to 74 °",  expect: "cooling"],
            [mode: "Fan Only", running: "Fan Running",     expect: "fan only"],
        ]
        cases.each { c ->
            def d = start([mode: c.mode, running: c.running]).d
            assert d.current("thermostatOperatingState") == c.expect, "${c}"
            assert d.current("runningState") == (c.running ?: "idle")
        }
    }

    void testUnrecognizedRunningStatusFallsBackToModeOnlyWhereItImpliesDirection() {
        def cases = ["Cooling": "cooling", "Heating": "heating", "Fan Only": "fan only", "Auto": "idle", "Off": "idle"]
        cases.each { mode, expect ->
            def d = start([mode: mode, running: "Stage 2 something new"]).d
            assert d.current("thermostatOperatingState") == expect, mode
        }
    }

    void testUnknownModeLeavesLastModeInPlaceAndWarnsOnce() {
        def ctx = start([mode: "Cooling"])
        def unit = Fixtures.thermostat([modes: ["Heating", "Cooling", "Dehumidify"], mode: "Dehumidify"])
        ctx.api.locations = Fixtures.account([unit])
        ctx.d.exec("fetchEquipment")
        ctx.d.exec("fetchEquipment")
        assert ctx.d.current("thermostatMode") == "cool"
        assert ctx.d.log.at("warn").count { it.contains("unrecognized mode 'Dehumidify'") } == 1
    }

    void testThermostatSetpointFollowsMode() {
        assert start([mode: "Heating", heat: 66]).d.current("thermostatSetpoint") == 66
        assert start([mode: "Cooling", cool: 77]).d.current("thermostatSetpoint") == 77
        // Auto reports the midpoint, half rounded up — as seen on hardware 2026-10-01
        assert start([mode: "Auto", heat: 73, cool: 78]).d.current("thermostatSetpoint") == 76
        assert start([mode: "Auto", heat: 75, cool: 78]).d.current("thermostatSetpoint") == 77
        assert start([mode: "Auto", heat: 68, cool: 74]).d.current("thermostatSetpoint") == 71
        assert start([mode: "Off"]).d.current("thermostatSetpoint") == null
    }

    void testCelsiusDisplay() {
        def d = start([temperature: 72, cool: 75, heat: 68], [tempUnit: "C"]).d
        assert d.current("temperature") == 22.2
        assert d.current("coolingSetpoint") == 23.9
        assert d.current("heatingSetpoint") == 20.0
        assert d.events.find { it.name == "temperature" }.unit == "°C"
    }

    void testDottedFanSpeedsReadAsMedium() {
        ["Med.Lo", "Medium", "Med.Hi"].each { s ->
            def d = start([fanSpeed: s]).d
            assert d.current("fanSpeed") == "medium"
            assert d.current("thermostatFanMode") == "circulate"
        }
        assert start([fanSpeed: "High"]).d.current("fanSpeed") == "high"
    }

    void testFanModeIsReadFromFanModeWhenTheUnitHasOne() {
        def d = start([fanModes: ["Auto", "On Continuous"], fanMode: "On Continuous", fanSpeed: "Auto"]).d
        assert d.current("thermostatFanMode") == "circulate"
        def d2 = start([fanModes: ["Auto", "On Continuous"], fanMode: "Auto", fanSpeed: "Low"]).d
        assert d2.current("thermostatFanMode") == "auto"
    }

    // ---- commands ---------------------------------------------------------------------

    void testModeCommandsSendTheUnitsEnumIndex() {
        def expected = [heat: 0, cool: 1, auto: 2, fanOnly: 3, off: 4]
        expected.each { cmd, idx ->
            def ctx = start([mode: "Off"])
            ctx.d.exec(cmd)
            assert ctx.api.lastCommand() == ["@MODE": idx], cmd
        }
        def ctx = start([mode: "Off"])
        ctx.d.exec("fan only")   // what the device page's mode dropdown actually calls
        assert ctx.api.lastCommand() == ["@MODE": 3]
        assert ctx.d.current("thermostatMode") == "fan only"
    }

    void testUnsupportedModeIsRefused() {
        def ctx = start([mode: "Off"])
        ctx.d.exec("emergencyHeat")
        assert ctx.api.published.isEmpty()
        assert ctx.d.log.at("error").any { it.contains("EMERGENCYHEAT") }
    }

    void testHeatingSetpointOutsideLimitsIsRefused() {
        def ctx = start([mode: "Heating"])
        ctx.d.exec("setHeatingSetpoint", 95.0)
        assert ctx.api.published.isEmpty()
        assert ctx.d.log.at("error").any { it.contains("out of range") }
    }

    void testHeatingSetpointInAutoPushesCoolingUpInOneCommand() {
        def ctx = start([mode: "Auto", heat: 68, cool: 75])
        ctx.d.exec("setHeatingSetpoint", 74.0)
        assert ctx.api.published.size() == 1
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 74, "@COOLSETPOINT": 76]
        assert ctx.d.current("heatingSetpoint") == 74
        assert ctx.d.current("coolingSetpoint") == 76
    }

    void testHeatingSetpointThatFitsTheDeadbandSendsOnlyItself() {
        def ctx = start([mode: "Auto", heat: 68, cool: 75])
        ctx.d.exec("setHeatingSetpoint", 73.0)
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 73]
    }

    void testHeatingSetpointOutsideAutoNeverMovesCooling() {
        def ctx = start([mode: "Heating", heat: 68, cool: 72])
        ctx.d.exec("setHeatingSetpoint", 74.0)
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 74]
    }

    void testHeatingSetpointRefusedWhenCoolingWouldExceedItsLimit() {
        def unit = Fixtures.thermostat([mode: "Auto", heat: 68, cool: 75])
        unit["@COOLSETPOINT"].constraints.upperLimit = 76
        def ctx = Fixtures.connected(FILE, Fixtures.account([unit]))
        ctx.d.exec("initialize")
        ctx.d.exec("setHeatingSetpoint", 75.0)
        assert ctx.api.published.isEmpty()
        assert ctx.d.log.at("error").any { it.contains("too high for auto mode") }
    }

    void testCoolingSetpointInAutoPushesHeatingDown() {
        def ctx = start([mode: "Auto", heat: 70, cool: 75])
        ctx.d.exec("setCoolingSetpoint", 71.0)
        assert ctx.api.lastCommand() == ["@COOLSETPOINT": 71, "@HEATSETPOINT": 69]
    }

    void testCoolingSetpointRefusedWhenHeatingWouldGoBelowItsLimit() {
        def unit = Fixtures.thermostat([mode: "Auto", heat: 70, cool: 75])
        unit["@HEATSETPOINT"].constraints.lowerLimit = 65
        def ctx = Fixtures.connected(FILE, Fixtures.account([unit]))
        ctx.d.exec("initialize")
        ctx.d.exec("setCoolingSetpoint", 66.0)
        assert ctx.api.published.isEmpty()
        assert ctx.d.log.at("error").any { it.contains("too low for auto mode") }
    }

    void testCelsiusSetpointsAreSentInFahrenheit() {
        def ctx = start([mode: "Heating", heat: 68], [tempUnit: "C"])
        ctx.d.exec("setHeatingSetpoint", 21.5)
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 71]
        assert ctx.d.current("heatingSetpoint") == 21.7, "reports the whole °F actually sent"
    }

    void testSetpointsRoundToWholeFahrenheit() {
        def ctx = start([mode: "Heating", heat: 68])
        ctx.d.exec("setHeatingSetpoint", 70.5d)
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 71]
        assert ctx.d.current("heatingSetpoint") == 71
        ctx.d.exec("setHeatingSetpoint", 70.4f)
        assert ctx.api.lastCommand() == ["@HEATSETPOINT": 70]
    }

    void testSetpointsAcceptAnyNumberTypeOrText() {
        def ctx = start([mode: "Auto", heat: 66, cool: 78])
        [70, 70L, 70.0d, new BigDecimal("70"), "70"].each { v ->
            ctx.d.exec("setHeatingSetpoint", v)
            assert ctx.api.lastCommand() == ["@HEATSETPOINT": 70], v.getClass().simpleName
        }
        ctx.d.exec("setCoolingSetpoint", "76")
        assert ctx.api.lastCommand() == ["@COOLSETPOINT": 76]
    }

    void testFanSpeedCommands() {
        def ctx = start()
        ctx.d.exec("setFanSpeed", "high")
        assert ctx.api.lastCommand() == ["@FANSPEED": 5]
        ctx.d.exec("setFanSpeed", "medium")
        assert ctx.api.lastCommand() == ["@FANSPEED": 3]
        assert ctx.d.current("fanSpeed") == "medium"
    }

    void testMediumFallsBackToMedLoWithoutPlainMedium() {
        def ctx = start([fanSpeeds: ["Auto", "Low", "Med.Lo", "Med.Hi", "High"]])
        ctx.d.exec("setFanSpeed", "medium")
        assert ctx.api.lastCommand() == ["@FANSPEED": 2]
    }

    void testUnknownFanSpeedIsRefused() {
        def ctx = start([fanSpeeds: ["Auto", "Low", "High"]])
        ctx.d.exec("setFanSpeed", "max")
        assert ctx.api.published.isEmpty()
    }

    void testFanOnWithoutFanModeUsesFirstRealSpeed() {
        def ctx = start()
        ctx.d.exec("fanOn")
        assert ctx.api.lastCommand() == ["@FANSPEED": 1]
        assert ctx.d.current("thermostatFanMode") == "circulate"
        assert ctx.d.current("fanSpeed") == "low"
        ctx.d.exec("fanAuto")
        assert ctx.api.lastCommand() == ["@FANSPEED": 0]
        assert ctx.d.current("thermostatFanMode") == "auto"
    }

    void testFanModeCommandsUseFanModeWhenPresent() {
        def ctx = start([fanModes: ["Auto", "On Continuous"]])
        ctx.d.exec("fanOn")
        assert ctx.api.lastCommand() == ["@FANMODE": 1]
        assert ctx.d.current("thermostatFanMode") == "circulate"
        ctx.d.exec("fanCirculate")
        assert ctx.api.lastCommand() == ["@FANMODE": 1]
        ctx.d.exec("fanAuto")
        assert ctx.api.lastCommand() == ["@FANMODE": 0]
    }

    void testAwayMode() {
        def ctx = start()
        ctx.d.exec("setAwayMode", "away")
        assert ctx.api.lastCommand() == ["@AWAY": true]
        ctx.d.exec("setAwayMode", "home")
        assert ctx.api.lastCommand() == ["@AWAY": false]
        assert ctx.d.current("awayMode") == "home"
    }

    void testCommandsBeforeFirstPollAreRefused() {
        def d = Fixtures.load(FILE)
        d.exec("heat")
        assert d.requests.isEmpty()
        assert d.log.at("error")
    }

    void testCommandIdsAreUniqueWithinASecond() {
        def ctx = start()
        ctx.d.exec("setAwayMode", "away")
        ctx.d.exec("setAwayMode", "home")
        def ids = ctx.api.published*.payload*.transactionId
        assert ids.every { it.startsWith("HUBITAT_") }
        assert ids[0] ==~ /HUBITAT_\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}/
    }
}
