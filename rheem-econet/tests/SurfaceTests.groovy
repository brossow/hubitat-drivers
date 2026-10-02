/**
 * The attribute, command and preference surface of each driver, compared against
 * a checked-in snapshot in tests/surface/.
 *
 * 1.0 is a commitment to this surface: rules, dashboards and Rule Machine refer to
 * these names. A change here is never incidental — when one is intended, regenerate
 * the snapshot with   UPDATE_SURFACE=1 tests/run.sh Surface   and review the diff.
 */
class SurfaceTests {
    static final File DIR = new File(Fixtures.DRIVER_DIR, "tests/surface")

    static String describe(DriverBase d) {
        def m = d.meta
        def out = []
        out << "definition: ${m.definition.name} (${m.definition.namespace})"
        m.capabilities.each { out << "capability ${it}" }
        m.attributes.each { a -> out << "attribute ${a.name} ${a.type}${a.values ? ' ' + a.values : ''}" }
        m.commands.each { c ->
            def args = c.args?.collect { "${it.name}:${it.type}${it.constraints ? it.constraints : ''}" }
            out << "command ${c.name}${args ? ' ' + args : ''}"
        }
        m.inputs.each { i ->
            out << "input ${i.name} ${i.type}${i.options ? ' ' + i.options : ''}" +
                   "${i.containsKey('defaultValue') ? ' default=' + i.defaultValue : ''}${i.required ? ' required' : ''}"
        }
        return out.join("\n") + "\n"
    }

    static void check(String file) {
        def actual = describe(Fixtures.load(file))
        def snap = new File(DIR, file.replace(".groovy", ".txt"))
        if (System.getenv("UPDATE_SURFACE")) {
            DIR.mkdirs()
            snap.text = actual
            return
        }
        assert snap.exists(), "no snapshot for ${file}: run UPDATE_SURFACE=1 tests/run.sh Surface"
        def expected = snap.text
        if (actual != expected) {
            def a = actual.readLines(), e = expected.readLines()
            def diff = (e - a).collect { "- ${it}" } + (a - e).collect { "+ ${it}" }
            assert false, "${file} surface changed (snapshot ${snap.name}):\n" + diff.join("\n") +
                          "\nIf this is intended, run UPDATE_SURFACE=1 tests/run.sh Surface and commit the snapshot."
        }
    }

    void testThermostatSurface()  { check("EcoNetThermostat.groovy") }
    void testWaterHeaterSurface() { check("EcoNetWaterHeater.groovy") }

    void testWaterHeaterDoesNotRedeclareCapabilityMembers() {
        def d = Fixtures.load("EcoNetWaterHeater.groovy")
        def names = d.meta.attributes*.name + d.meta.commands*.name
        // All provided by the ThermostatMode capability
        assert !names.any { it in ["supportedThermostatModes", "thermostatMode", "heat", "auto", "emergencyHeat", "cool", "off"] }
    }

    void testEveryDeclaredCommandIsImplemented() {
        ["EcoNetThermostat.groovy", "EcoNetWaterHeater.groovy"].each { f ->
            def d = Fixtures.load(f)
            d.meta.commands.each { c ->
                assert d.metaClass.respondsTo(d, c.name), "${f}: command ${c.name} has no method"
            }
        }
    }

    void testCapabilityCommandsAreImplemented() {
        // Commands Hubitat's capabilities define — a dashboard tile or rule can call any of them.
        def required = [
            "EcoNetThermostat.groovy" : ["auto", "cool", "emergencyHeat", "fanAuto", "fanCirculate", "fanOn", "heat", "off",
                                         "setCoolingSetpoint", "setHeatingSetpoint", "setThermostatFanMode",
                                         "setThermostatMode", "refresh", "initialize", "fan only"],
            "EcoNetWaterHeater.groovy": ["on", "off", "setHeatingSetpoint", "auto", "cool", "emergencyHeat", "heat",
                                         "setThermostatMode", "refresh", "initialize"],
        ]
        required.each { f, cmds ->
            def d = Fixtures.load(f)
            cmds.each { c -> assert d.metaClass.respondsTo(d, c), "${f}: ${c}() missing" }
        }
    }

    void testDriverVersionIsPublished() {
        ["EcoNetThermostat.groovy", "EcoNetWaterHeater.groovy"].each { f ->
            def ctx = Fixtures.connected(f, [])
            ctx.d.exec("initialize")
            assert ctx.d.device.data.driverVersion == ctx.d.DRIVER_VERSION
        }
    }

    void testNewDeviceFollowsHubTemperatureScale() {
        def ctx = Fixtures.connected("EcoNetThermostat.groovy", Fixtures.account([Fixtures.thermostat([temperature: 72])]))
        ctx.d.settings.remove("tempUnit")
        ctx.d.location.temperatureScale = "C"
        ctx.d.exec("installed")
        assert ctx.d.settings.tempUnit == "C"
        assert ctx.d.current("temperature") == 22.2
    }

    void testExistingTemperatureSettingIsKept() {
        def ctx = Fixtures.connected("EcoNetThermostat.groovy", [], [tempUnit: "F"])
        ctx.d.location.temperatureScale = "C"
        ctx.d.exec("installed")
        assert ctx.d.settings.tempUnit == "F"
    }

    void testOldSupportedModesAttributeIsCleared() {
        def ctx = Fixtures.connected("EcoNetWaterHeater.groovy", [])
        ctx.d.device.current.supportedModes = '["off"]'
        ctx.d.exec("initialize")
        assert !ctx.d.device.current.containsKey("supportedModes")
    }
}
