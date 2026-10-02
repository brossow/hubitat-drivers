/**
 * Unit selection — the design invariants. Each exists because a multi-unit user's
 * bug report showed a way for a device to silently control the wrong unit. Every
 * test runs against both drivers, which share this logic.
 */
class SelectionTests {
    static final List<Map> KINDS = [
        [file: "EcoNetThermostat.groovy",  key: "thermostat",  make: { Map o -> Fixtures.thermostat(o) }],
        [file: "EcoNetWaterHeater.groovy", key: "waterHeater", make: { Map o -> Fixtures.waterHeater(o) }],
    ]

    static final String SERIAL_A = "00-00-00-00-00-00-aa-01-01"
    static final String SERIAL_B = "00-00-00-00-00-00-aa-02-02"

    static Map unitA(Map kind) { kind.make([serial: SERIAL_A, deviceName: "DEVICE-A", name: "Upstairs"]) }
    static Map unitB(Map kind) { kind.make([serial: SERIAL_B, deviceName: "DEVICE-B", name: "Downstairs"]) }

    static Map start(Map kind, List units, Map settings = [:]) {
        def ctx = Fixtures.connected(kind.file, Fixtures.account(units), settings)
        ctx.d.exec("initialize")
        return ctx
    }

    void testSingleUnitIsPinnedBySerial() {
        KINDS.each { k ->
            def d = start(k, [unitA(k)]).d
            assert d.settings.deviceSerial == SERIAL_A
            assert d.state.serialNumber == SERIAL_A
            assert d.state.deviceId == "DEVICE-A"
        }
    }

    void testSeveralUnitsWithoutSerialUseFirstButPersistNothing() {
        KINDS.each { k ->
            def d = start(k, [unitA(k), unitB(k)]).d
            assert d.state.serialNumber == SERIAL_A
            assert !d.settings.deviceSerial, "a guess must never be written into the user's settings"
            assert d.log.at("warn").any { it.contains("no serial number set") }
        }
    }

    void testConfiguredSerialSelectsThatUnit() {
        KINDS.each { k ->
            def d = start(k, [unitA(k), unitB(k)], [deviceSerial: SERIAL_B]).d
            assert d.state.serialNumber == SERIAL_B
            assert d.state.deviceId == "DEVICE-B"
        }
    }

    void testSerialMatchIgnoresPunctuationAndCase() {
        KINDS.each { k ->
            def d = start(k, [unitA(k), unitB(k)], [deviceSerial: "000000000000AA0202"]).d
            assert d.state.serialNumber == SERIAL_B
        }
    }

    void testWholeRosterRowPastedStillSelects() {
        KINDS.each { k ->
            def row = "Downstairs @ Home — ${SERIAL_B}".toString()
            def d = start(k, [unitA(k), unitB(k)], [deviceSerial: row]).d
            assert d.state.serialNumber == SERIAL_B
        }
    }

    void testUnknownSerialControlsNothing() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k), unitB(k)], [deviceSerial: "99-99-99-99-99-99"])
            def d = ctx.d
            assert d.state.deviceId == null
            assert d.state.serialNumber == null
            assert d.log.at("error").any { it.contains("Not controlling any") }
            assert d.exec("publishCommand", ["@AWAY": true]) == false
            assert ctx.api.published.isEmpty()
        }
    }

    void testSelectionLostLaterDropsCachedIdentity() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k), unitB(k)], [deviceSerial: SERIAL_B])
            def d = ctx.d
            assert d.state.deviceId == "DEVICE-B"
            ctx.api.locations = Fixtures.account([unitA(k)])   // unit B removed from the account
            d.exec("fetchEquipment")
            assert d.state.deviceId == null, "queued commands must not reach the previously selected unit"
            d.exec("setAwayMode", "away")
            assert ctx.api.published.isEmpty()
        }
    }

    void testSeveralRowsPastedIsAmbiguousAndControlsNothing() {
        KINDS.each { k ->
            def pasted = "${SERIAL_A}\n${SERIAL_B}".toString()
            def d = start(k, [unitA(k), unitB(k)], [deviceSerial: pasted]).d
            assert d.state.deviceId == null
            assert d.log.at("error").any { it.contains("matches 2") }
        }
    }

    void testExactMatchBeatsContainedMatch() {
        // A zone whose id is its parent's serial plus a suffix "contains" the parent's id.
        KINDS.each { k ->
            def parent = k.make([serial: "00-00-00-00-00-00-cc-01", deviceName: "DEVICE-P"])
            def zone   = k.make([serial: "00-00-00-00-00-00-cc-01-z2", deviceName: "DEVICE-Z"])
            def d = start(k, [parent, zone], [deviceSerial: "00-00-00-00-00-00-cc-01-z2"]).d
            assert d.state.deviceId == "DEVICE-Z"
            def d2 = start(k, [parent, zone], [deviceSerial: "00-00-00-00-00-00-cc-01"]).d
            assert d2.state.deviceId == "DEVICE-P"
        }
    }

    void testShortIdsDoNotMatchInsideLongerInput() {
        KINDS.each { k ->
            def tiny = k.make([serial: "a1", deviceName: "DEVICE-T"])
            def d = start(k, [tiny], [deviceSerial: "zzzza1zzzz"]).d
            assert d.state.deviceId == null
        }
    }

    void testUnitWithoutSerialIsPinnedByDeviceName() {
        KINDS.each { k ->
            def noSerial = k.make([serial: null, deviceName: "DEVICE-NOSERIAL-01"])
            def d = start(k, [noSerial]).d
            assert d.settings.deviceSerial == "DEVICE-NOSERIAL-01"
            assert d.state.deviceId == "DEVICE-NOSERIAL-01"
        }
    }

    void testNameInsteadOfSerialIsExplained() {
        KINDS.each { k ->
            def d = start(k, [unitA(k), unitB(k)], [deviceSerial: "downstairs"]).d
            assert d.state.deviceId == null
            assert d.log.at("error").any { it.contains("name, not its serial number") }
        }
    }

    void testLegacyIndexIsMigratedToSerialAndRemoved() {
        KINDS.each { k ->
            def d = start(k, [unitA(k), unitB(k)], [deviceIndex: 1]).d
            assert d.settings.deviceSerial == SERIAL_B
            assert !d.settings.containsKey("deviceIndex")
            assert d.state.serialNumber == SERIAL_B
        }
    }

    void testLegacyIndexOutOfRangeFallsBackToFirst() {
        KINDS.each { k ->
            def d = start(k, [unitA(k)], [deviceIndex: 4]).d
            assert d.settings.deviceSerial == SERIAL_A
            assert !d.settings.containsKey("deviceIndex")
        }
    }

    void testRosterIsOneStateVariablePerUnit() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k), unitB(k)], [deviceSerial: SERIAL_A])
            def d = ctx.d
            assert d.state["${k.key}0"].toString().contains(SERIAL_A)
            assert d.state["${k.key}1"].toString().contains(SERIAL_B)
            assert d.state["${k.key}0"] instanceof String, "a list-valued variable renders as one row"
            ctx.api.locations = Fixtures.account([unitA(k)])
            d.exec("fetchEquipment")
            assert d.state["${k.key}1"] == null, "rows for departed units are removed"
        }
    }

    void testRosterIncludesLocationName() {
        KINDS.each { k ->
            def d = start(k, [unitA(k)]).d
            assert d.state["${k.key}0"].toString().startsWith("Upstairs @ Home")
        }
    }

    void testOfflinePinnedUnitKeepsIdentityAndRecovers() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k), unitB(k)], [deviceSerial: SERIAL_B])
            def d = ctx.d
            def downB = unitB(k) + [error: "Device is offline"]
            ctx.api.locations = Fixtures.account([unitA(k), downB])
            d.exec("fetchEquipment")
            d.exec("fetchEquipment")
            assert d.current("online") == "false"
            assert d.state.deviceId == "DEVICE-B", "an offline unit is still the right unit"
            assert d.log.at("warn").count { it.contains("reporting an error") } == 1, "once per outage"
            assert !d.log.at("error").any { it.contains("Not controlling") }

            ctx.api.locations = Fixtures.account([unitA(k), unitB(k)])
            d.exec("fetchEquipment")
            assert d.current("online") == "true"
            assert d.log.at("info").any { it.contains("reporting normally again") }
        }
    }

    void testOfflineOtherUnitDoesNotAffectThisOne() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k) + [error: "offline"], unitB(k)], [deviceSerial: SERIAL_B])
            assert ctx.d.state.deviceId == "DEVICE-B"
            assert ctx.d.current("online") == "true"
        }
    }

    void testCommandsCarryTheSelectedUnitsIdentity() {
        KINDS.each { k ->
            def ctx = start(k, [unitA(k), unitB(k)], [deviceSerial: SERIAL_B])
            ctx.d.exec("setAwayMode", "away")
            def p = ctx.api.published[-1]
            assert p.payload.device_name == "DEVICE-B"
            assert p.payload.serial_number == SERIAL_B
            assert p.topic == "user/ACCOUNT-PLACEHOLDER-0001/device/desired"
            assert p.payload["@AWAY"] == true
        }
    }

    void testOtherDeviceTypesAreIgnored() {
        def t = Fixtures.thermostat([serial: SERIAL_A])
        def w = Fixtures.waterHeater([serial: SERIAL_B])
        def dt = start(KINDS[0], [w, t]).d
        assert dt.state.serialNumber == SERIAL_A
        def dw = start(KINDS[1], [t, w]).d
        assert dw.state.serialNumber == SERIAL_B
    }

    void testZoningDevicesAreSelectableOnTheThermostat() {
        def zone = Fixtures.thermostat([serial: null, deviceName: "DEVICE-ZONE-2", name: "Zone 2"])
        def main = Fixtures.thermostat([serial: SERIAL_A, deviceName: "DEVICE-A", zones: [zone]])
        def d = start(KINDS[0], [main], [deviceSerial: "DEVICE-ZONE-2"]).d
        assert d.state.deviceId == "DEVICE-ZONE-2"
        assert d.state.thermostat1.toString().contains("DEVICE-ZONE-2")
    }
}
