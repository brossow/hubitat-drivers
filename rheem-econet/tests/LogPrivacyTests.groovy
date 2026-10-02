/**
 * Logs must never contain a full identifier. People paste debug logs into public
 * forum threads when they ask for help, so serials, device ids, account ids, email
 * addresses, tokens and passwords appear only masked (last four characters).
 *
 * Each scenario drives a driver through the paths that log identifiers, with debug
 * logging on, and then scans every line.
 */
class LogPrivacyTests {
    static final String SERIAL_A = "00-00-00-00-00-00-aa-01-01"
    static final String SERIAL_B = "00-00-00-00-00-00-aa-02-02"
    static final String DEVICE_A = "DEVICE-ID-PRIVATE-AAAA"
    static final String DEVICE_B = "DEVICE-ID-PRIVATE-BBBB"

    static final List<Map> KINDS = SelectionTests.KINDS

    static List<String> secrets(FakeEcoNet api, DriverBase d) {
        def raw = [SERIAL_A, SERIAL_B, DEVICE_A, DEVICE_B, api.accountId, api.token,
                   d.settings.email, d.settings.password]
        return raw + [SERIAL_A, SERIAL_B].collect { it.replaceAll(/[^A-Za-z0-9]/, "") }
    }

    static void assertClean(FakeEcoNet api, DriverBase d, String scenario) {
        def leaks = []
        d.log.lines.each { line ->
            secrets(api, d).each { s -> if (line.msg.contains(s)) leaks << "[${line.level}] ${line.msg}  ← contains ${s}" }
        }
        assert leaks.isEmpty(), "${scenario}:\n" + leaks.join("\n")
    }

    static Map units(Map kind) {
        [a: kind.make([serial: SERIAL_A, deviceName: DEVICE_A, name: "Upstairs"]),
         b: kind.make([serial: SERIAL_B, deviceName: DEVICE_B, name: "Downstairs"])]
    }

    void testNormalStartupAndCommands() {
        KINDS.each { k ->
            def u = units(k)
            def ctx = Fixtures.connected(k.file, Fixtures.account([u.a]))
            ctx.d.exec("installed")
            ctx.d.exec("setAwayMode", "away")
            ctx.d.exec("refresh")
            assert ctx.d.log.at("debug"), "debug logging should be on in this scenario"
            assertClean(ctx.api, ctx.d, "startup ${k.key}")
        }
    }

    void testSeveralUnitsAndEverySelectionError() {
        KINDS.each { k ->
            def u = units(k)
            [null, SERIAL_B, "99-99-99-99-99-99", "Downstairs", "${SERIAL_A} ${SERIAL_B}".toString()].each { serial ->
                def ctx = Fixtures.connected(k.file, Fixtures.account([u.a, u.b]), [deviceSerial: serial])
                ctx.d.exec("initialize")
                assertClean(ctx.api, ctx.d, "selection '${serial}' ${k.key}")
            }
        }
    }

    void testLegacyUpgradeOfflineAndRecovery() {
        KINDS.each { k ->
            def u = units(k)
            def ctx = Fixtures.connected(k.file, Fixtures.account([u.a, u.b]), [deviceIndex: 1])
            ctx.d.exec("initialize")
            ctx.api.locations = Fixtures.account([u.a, u.b + [error: "offline"]])
            ctx.d.exec("fetchEquipment")
            ctx.api.locations = Fixtures.account([u.a, u.b])
            ctx.d.exec("fetchEquipment")
            assertClean(ctx.api, ctx.d, "upgrade/offline ${k.key}")
        }
    }

    void testLoginFailures() {
        KINDS.each { k ->
            def u = units(k)
            def ctx = Fixtures.connected(k.file, Fixtures.account([u.a]))
            ctx.api.rejectCredentials = true
            ctx.d.exec("initialize")
            ctx.d.exec("refresh")
            ctx.api.rejectCredentials = false
            ctx.api.authStatus = 500
            ctx.d.exec("updated")
            assertClean(ctx.api, ctx.d, "login failures ${k.key}")
        }
    }

    void testMaskingHelpers() {
        def d = Fixtures.load("EcoNetThermostat.groovy")
        assert d.maskId("00-00-00-00-00-00-aa-01-01") == "…1-01"
        assert d.maskId("abc") == "…abc"
        assert d.maskId(null) == "(none)"
        assert d.maskEmail("someone@example.com") == "s…@example.com"
        assert d.maskEmail("not-an-email") == "…"
    }

    void testRosterStillHoldsTheFullSerialForCopying() {
        def ctx = Fixtures.connected("EcoNetThermostat.groovy",
                                     Fixtures.account([Fixtures.thermostat([serial: SERIAL_A])]))
        ctx.d.exec("initialize")
        assert ctx.d.state.thermostat0.toString().endsWith(SERIAL_A)
        assert ctx.d.settings.deviceSerial == SERIAL_A
    }
}
