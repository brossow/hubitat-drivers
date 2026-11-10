def getDriverVersion() { return "1.08" }	// **** DEVICE DRIVER VERSION.
/*
 * 	Yale Assure Lock 2
 *
 *   Version 1.08
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Original driver by Trunzoc/Sleuth (github.com/sleuth255/Yale_YRD4xx_Hubitat_driver).
 *  1.08 builds on kdb-jc's Z-Wave JS fixes (github.com/kdb-jc/Yale_YRD4xx_Hubitat_driver, branch "personal")
 *  with further fixes by brossow.
 *
 *  Features:
 *  - Z-Wave S2 Security (Supervision encapsulation)
 *  - DoorSense (Contact Sensor)
 *  - Lock Code Manager compatibility
 *  - Detailed Event Text Reporting
 *  - Works on both Z-Wave stacks (ZIP and Z-Wave JS)
 *
 *  Version History
 *  v1.00	Initial Driver Release
 *  v1.01   Update fingerprints for auto driver select
 *  v1.02   fix the ZW2 usercode delete issue
 *          ID module type and store in state
 *          add device fingerprints for all non-fingerprint unlock capable locks with both ZW2 and ZW3 modules.
 *  v1.03   work around ZW3 usercode maintenance bug in firmware v1.3.28
 *  v1.04   capture biometric fingerprint unlock and reflect in lock state
 *  v1.05   update zwave signatures to include biometric lock variants
 *  v1.06   capture digital autolock
 *  v1.07   adopt kdb-jc updates: jam detect, fingerprint unlock on keypad unlock event, ignore truncated lock codes
 *  v1.08   From kdb-jc (Z-Wave JS):
 *          - read the user slot from eventParameter[0] when there's no embedded User Code Report ("User null")
 *          - ignore "Opening state" updates (a phantom lock on every door open)
 *          - drop repeated door open/close events (Door state is re-sent 2-3 times per open/close)
 *          - fix the lockCodes typo that threw when a code changed, and define commandClassVersions
 *          - report doorLockMode 0xFE (bolt position unknown) as "unknown" rather than "unlocked"
 *          Also:
 *          - option to encrypt stored lock codes (on by default); PINs are masked in the driver's own logs
 *          - codes are recorded only once the lock confirms them; a code the lock rejects reports "failed"
 *          - several codes can be added or deleted at once (one pending entry per slot instead of state.slotId)
 *          - codeChanged uses the capability's values (added/changed/deleted/failed)
 *          - lock and battery events no longer forced as state changes, so repeats are dropped
 *            (keypad unlocks still always fire, for Rule Machine's "lock code entered")
 *          - an unlock from an unknown slot no longer reports "User null"
 *          - a keypad unlock fires one lock event, not a bare "unlocked" followed by the named one
 *          - a code add or delete fires one codeChanged event when Z-Wave JS delivers its report twice
 *          - setCodeLength no longer throws; idle (event 0) notifications are ignored quietly
 *          - maxCodes follows the lock's reported slot count (capped at 250)
 *          - debug logging defaults off and turns itself off after 30 minutes
 *          - removed the AlarmReport handler (unreachable, and it called a method that didn't exist)
*/

metadata {
    definition (name: "Yale Assure Lock 2", namespace: "Sleuth255", author: "Trunzoc/Sleuth") {
        capability "Actuator"
        capability "Lock"
        capability "LockCodes"
        capability "ContactSensor"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"

        fingerprint mfr: "0129", prod: "8104", deviceId: "05D3", deviceJoinName: "Yale Assure Lock 2 push button ZW2 module (YRD430)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "05D5", deviceJoinName: "Yale Assure Lock 2 Touchscreen ZW2 module (YRD450)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "05D1", deviceJoinName: "Yale Assure Lock 2 push button Key ZW2 module(YRD10)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "05D2", deviceJoinName: "Yale Assure Lock 2 Touchscreen Key ZW2 module(YRD420)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "09D3", deviceJoinName: "Yale Assure Lock 2 Touch push button ZW2 (YRD430-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "09D5", deviceJoinName: "Yale Assure Lock 2 Touch Touchscreen ZW2 module (YRD450-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "09D1", deviceJoinName: "Yale Assure Lock 2 Touch push button Key ZW2 module(YRD10-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "09D2", deviceJoinName: "Yale Assure Lock 2 Touch Touchscreen Key ZW2 module(YRD420-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "45D3", deviceJoinName: "Yale Assure Lock 2 push button ZW3 module (YRD430)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "45D5", deviceJoinName: "Yale Assure Lock 2 Touchscreen ZW3 module (YRD450)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "45D1", deviceJoinName: "Yale Assure Lock 2 push button Key ZW3 module(YRD410)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "45D2", deviceJoinName: "Yale Assure Lock 2 Touchscreen Key ZW3 module(YRD420)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "49D3", deviceJoinName: "Yale Assure Lock 2 Touch push button ZW3 (YRD430-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "49D5", deviceJoinName: "Yale Assure Lock 2 Touch Touchscreen ZW3 module (YRD450-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "49D1", deviceJoinName: "Yale Assure Lock 2 Touch push button Key ZW3 module(YRD410-F)"
        fingerprint mfr: "0129", prod: "8104", deviceId: "49D2", deviceJoinName: "Yale Assure Lock 2 Touch Touchscreen Key ZW3 module(YRD420-F)"

        command "initialize", [[name:"Clear state variables, refresh current states"]]
    }

    preferences {
        input name: "optEncrypt", type: "bool", title: "Encrypt stored lock codes", description: "Keeps PINs out of plain view on the device page and from apps that read device attributes. Lock Code Manager works either way.", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes.", defaultValue: false
        input name: "traceEnable", type: "bool", title: "Enable trace logging", description: "Shows raw Z-Wave messages, which can include PINs. Turns itself off after 30 minutes.", defaultValue: false
    }
}


import hubitat.zwave.commands.doorlockv1.*
import hubitat.zwave.commands.usercodev1.*
import hubitat.zwave.commands.notificationv3.*
import hubitat.zwave.commands.supervisionv1.*
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Z-Wave JS can deliver the same report twice in quick succession, and the hub runs the two
// parse calls at the same time. Code bookkeeping runs under a per-device lock (and keeps its
// pending list in atomicState) so the second copy sees what the first did.
@Field static final ConcurrentHashMap<String, Semaphore> codeLocks = new ConcurrentHashMap<String, Semaphore>()
// A lock-state report that would change the lock is held briefly, in case the Access Control
// notification for the same operation (which says who or what did it) is right behind it.
@Field static final ConcurrentHashMap<String, Map> heldLockReports = new ConcurrentHashMap<String, Map>()
@Field static final int lockReportHoldMillis = 2000

// Pending code changes wait this long for the lock before being read back once, then reported as failed.
private static int getPendingTimeoutSeconds() { 20 }

// Command class versions, used for top-level parsing and for unwrapping the
// Supervision / Security encapsulated commands.
private Map getCommandClassVersions() {
    return [0x98: 1, 0x62: 1, 0x71: 3, 0x80: 1, 0x85: 2, 0x86: 1, 0x63: 1, 0x6C: 1]
}

// --- Device Identification
def getZWaveDeviceId() {
    def decimalId = device.getDataValue("deviceId")
    if (!decimalId) return null

    try {
        // Convert the decimal string to an integer
        int idInt = Integer.parseInt(decimalId.toString())

        // Format to a 4-character, upper-case, padded Hex string
        return String.format("%04X", idInt)
    } catch (NumberFormatException e) {
        // Fallback in case it's already a non-numeric/hex string
        return decimalId
    }
}

// --- Core Z-Wave Parsing ---
def parse(String description) {
	if (traceEnable) log.trace "parse(String description): ${description}"
    // Z-Wave JS reports each Access Control state variable as its own value update, and the hub
    // rebuilds a NotificationReport with event = the state's value. "Door state" values are real
    // event codes (22/23), but "Opening state" is 0/1 (closed/open), so every door open arrived as
    // event 1 (Manual Lock). Door state already covers open/close, so drop Opening state entirely.
    if (description.startsWith("{")) {
        def json = parseJson(description)
        if (json.cc == 113 && json.values?.any { it.propertyKey == "Opening state" }) {
            if (debugEnable) log.debug "Ignoring Z-Wave JS 'Opening state' update (duplicates Door state)"
            return null
        }
    }
    def result = null
    def cmd = zwave.parse(description, commandClassVersions)
    if (cmd) {
        result = zwaveEvent(cmd)
        if (debugEnable) log.debug "Parsed ${cmd instanceof UserCodeReport ? 'UserCodeReport' : cmd} to ${result.inspect()}"
    }
    return result
}

// --- S2 Security Supervision Handler ---
def zwaveEvent(SupervisionGet cmd) {
	if (traceEnable) log.trace "zwaveEvent(SupervisionGet cmd): ${cmd}"
    def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0).format()), hubitat.device.Protocol.ZWAVE))
    return null
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	if (traceEnable) log.trace "zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd): ${cmd}"
    def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
    if (encapsulatedCommand) {
        return zwaveEvent(encapsulatedCommand)
    }
}

// --- Lock Operation & State ---
def zwaveEvent(DoorLockOperationReport cmd) {
	if (traceEnable) log.trace "zwaveEvent(DoorLockOperationReport cmd): ${cmd}"
    def map = [name: "lock"]
    if (cmd.doorLockMode == 0xFF) {
        map.value = "locked"
        map.descriptionText = "${device.displayName} was locked"
    } else if (cmd.doorLockMode == 0xFE) {
        // 0xFE means the lock doesn't know the bolt position (e.g. jammed or mid-travel), so it must
        // not be reported as unlocked.
        map.value = "unknown"
        map.descriptionText = "${device.displayName} reports an unknown lock state"
    } else {
        map.value = "unlocked"
        map.descriptionText = "${device.displayName} was unlocked"
    }
    // The Access Control notification that precedes this report already logged the operation in
    // detail, and Z-Wave JS also tends to deliver this report twice. So only log at info when this
    // report is what actually changes the lock state (e.g. the notification was lost in transit).
    // On Z-Wave JS this report can arrive before the notification instead. Sending it straight away
    // would fire a bare "unlocked" and then the notification's "unlocked by Alex via keypad",
    // so a report that changes the lock is held for a moment and dropped if the notification comes.
    if (device.currentValue("lock", true) != map.value) {
        heldLockReports[deviceKey()] = map
        runInMillis(lockReportHoldMillis, "releaseLockReport", [overwrite: true])
    } else {
        if (debugEnable) log.debug map.descriptionText
        sendEvent(map)
    }
}

// The held lock-state report: no notification covered it (lost in transit, a refresh, or a stack
// that doesn't send one), so report it now.
def releaseLockReport() {
    def map = heldLockReports.remove(deviceKey())
    if (map == null) return
    if (device.currentValue("lock", true) != map.value && txtEnable) log.info map.descriptionText
    sendEvent(map)
}

private String deviceKey() {
    return (device?.id ?: device?.deviceNetworkId ?: "0").toString()
}

private void withCodeLock(Closure body) {
    def key = deviceKey()
    codeLocks.putIfAbsent(key, new Semaphore(1))
    Semaphore lock = codeLocks[key]
    // Don't stall a parse thread forever if something went badly wrong: after 10 s, carry on unlocked.
    boolean held = lock.tryAcquire(10, TimeUnit.SECONDS)
    try {
        body()
    } finally {
        if (held) lock.release()
    }
}

// The user slot in an Access Control notification.
// Old stack: embedded User Code Report [0x63, 0x03, slot, status, code...] -> slot at [2].
// Z-Wave JS: the hub passes parameters.userId through as a single byte -> slot at [0].
// (eventParametersLength is not kept consistent with eventParameter, so don't use it.)
private Integer slotFromParams(List params) {
    if (!params) return null
    if (params.size() >= 3 && params[0] == 0x63 && params[1] == 0x03) return params[2] as Integer
    return params[0] as Integer
}

// --- Consolidated Event Reporting ---
def zwaveEvent(NotificationReport cmd) {
	if (traceEnable) log.trace "zwaveEvent(NotificationReport cmd): ${cmd}"
  	if (debugEnable) log.debug "Notification type ${cmd.notificationType} event ${cmd.event}"

    // Notification Type 0x06: Access Control
    if (cmd.notificationType == 0x06) {
        def map = [name: "lock"]
        switch (cmd.event) {
            case 0x01: // Manual Lock
                map.value = "locked"
                map.type = "physical"
                map.descriptionText = "${device.displayName} was locked manually"
                break
            case 0x02: // Manual Unlock
                map.value = "unlocked"
                map.type = "physical"
                map.descriptionText = "${device.displayName} was unlocked manually"
                break
            case 0x03: // RF/App Lock
                map.value = "locked"
                map.type = "digital"
                map.descriptionText = "${device.displayName} was locked digitally"
                break
            case 0x04: // RF/App Unlock
                map.value = "unlocked"
                map.type = "digital"
                map.descriptionText = "${device.displayName} was unlocked digitally"
                break
            case 0x05: // Keypad Lock
                map.value = "locked"
                map.type = "physical"
                map.descriptionText = "${device.displayName} was locked via keypad"
                break
            case 0x06: // Keypad or Fingerprint Unlock (With User Data)
                def slotId = slotFromParams(cmd.eventParameter)
                def known = slotId != null && loadLockCodes().containsKey(slotId.toString())
                def codeName = slotId == null ? "an unknown code" : getCodeName(slotId)
                map.value = "unlocked"
                map.type = "physical"
                // Every keypad unlock fires, even if Hubitat already thought the lock was unlocked,
                // so Rule Machine's "lock code entered" trigger sees each one.
                map.isStateChange = true
                // On at least some ZW3 variants, Yale reports both keypad and fingerprint unlocks
                // as event 0x06 with identical event parameters, only distinguishing them in the
                // legacy v1 alarm type. Z-Wave JS doesn't pass the v1 alarm type through (it's always 0),
                // so there both read as keypad.
                if (cmd?.v1AlarmType == 0x91) {
                    // A fingerprint's slot only matches a code's name if they were enrolled in the same
                    // order, so don't guess a name: report "Fingerprint", as 1.07 does.
                    map.descriptionText = "${device.displayName} unlocked by Fingerprint match"
                    sendEvent(name: "lastCodeName", value: "Fingerprint", descriptionText: map.descriptionText)
                    break
                }
                def label = known || slotId == null ? codeName : "unknown code ${slotId}"
                map.descriptionText = "${device.displayName} unlocked by ${label} via keypad"
                if (slotId != null) map.data = [(slotId.toString()): [name: codeName]]
                sendEvent(name: "lastCodeName", value: label, descriptionText: map.descriptionText)
                break
            case 0x07: // Manual Not Fully Locked
            case 0x08: // RF/App Not Fully Locked
            case 0x0A: // Auto Lock Not Fully Locked
                map.value = "unknown"
                map.descriptionText = "${device.displayName} did not fully lock"
                log.warn map.descriptionText
                break
            case 0x0B: // Lock Jammed
                map.value = "unknown"
                map.descriptionText = "${device.displayName} is jammed"
                log.warn map.descriptionText
                break
            case 0x09: // Auto Lock
                map.value = "locked"
                map.type = "physical"
                map.descriptionText = "${device.displayName} was auto-locked"
                break
            case 0x0C: // All user codes deleted
                if (txtEnable) log.info "${device.displayName} reports all user codes deleted"
                withCodeLock {
                    def codes = loadLockCodes()
                    codes.keySet().collect().each { slot -> codeChangedEvent("deleted", slot, codes[slot].name) }
                    if (codes) updateLockCodes([:])
                    savePendingCodes([:])
                }
                return
            case 0x0D: // Single user code deleted
                withCodeLock { confirmPending("delete", slotFromParams(cmd.eventParameter)) }
                return
            case 0x0E: // New user code added
                withCodeLock { confirmPending("set", slotFromParams(cmd.eventParameter)) }
                return
            case 0x0F: // New user code not added due to duplicate code
                withCodeLock { rejectPending(slotFromParams(cmd.eventParameter), "duplicates a code already in the lock") }
                return
            case 0x16: // Door Opened
            case 0x17: // Door Closed
                // Z-Wave JS re-sends the current Door state whenever other Notification values change
                // (e.g. alarmType/alarmLevel on a lock operation), and sometimes delivers it twice, so the
                // same door state arrives 2-3 times per open/close. Contact is a state, so let Hubitat drop
                // repeats (no isStateChange) and only log when the value actually changes.
                map.name = "contact"
                map.value = cmd.event == 0x16 ? "open" : "closed"
                map.descriptionText = "${device.displayName} was ${cmd.event == 0x16 ? 'opened' : 'closed'}"
                if (txtEnable && device.currentValue("contact") != map.value) log.info map.descriptionText
                break
            case 0x00: // Idle: carries no state (e.g. the answer to a notification get), so nothing to report
                if (debugEnable) log.debug "Access Control idle notification"
                return
            case 0xFE: // Keypad Unlock with Fingerprint
                map.value = "unlocked"
                map.type = "physical"
                map.descriptionText = "${device.displayName} unlocked by Fingerprint match"
                break
            default:
                def p = cmd.eventParameter ?: []
                log.info "Unknown event ${cmd.event} param0: ${p[0]} param1: ${p[1]}  param2: ${p[2]}"
                break
        }
        if (map.value) {
            // Lock events log here; contact events log in their own cases, and jams already warned.
            if (map.name == "lock") {
                heldLockReports.remove(deviceKey())   // this notification covers any held report
                if (map.value != "unknown" && txtEnable) log.info map.descriptionText
            }
            sendEvent(map)
        }
    }
}

// --- Lock Code Manager Integration ---
//
// setCode and deleteCode record what they asked for in atomicState.pendingCodes, one entry per slot,
// and lockCodes only changes once the lock confirms it: by notification (code added / deleted)
// or by a User Code Report read back from the lock. A change the lock never confirms is read back
// once, then reported as codeChanged "failed". Pending PINs are stored encrypted.

private Map pendingCodes() {
    return (atomicState.pendingCodes ?: [:]) as Map
}

private void savePendingCodes(Map pending) {
    if (pending) atomicState.pendingCodes = pending
    else atomicState.remove("pendingCodes")
}

private void addPending(slot, Map entry) {
    withCodeLock {
        def pending = pendingCodes()
        pending[slot.toString()] = entry + [at: now(), retried: false]
        savePendingCodes(pending)
    }
    runIn(pendingTimeoutSeconds, "checkPendingCodes", [overwrite: true])
}

// A code notification names its slot on the old stack. On Z-Wave JS it doesn't, so a single
// pending change of that kind is taken as the one confirmed. With several pending, or none,
// the pending slots are read back instead.
private void confirmPending(String op, Integer slot) {
    def pending = pendingCodes()
    def ofKind = pending.findAll { it.value.op == op }
    def key = slot != null ? slot.toString() : (ofKind.size() == 1 ? ofKind.keySet().first() : null)
    if (key != null && ofKind.containsKey(key)) {
        if (op == "delete") {
            applyDelete(key)
        } else {
            def entry = ofKind[key]
            applySet(key, entry.name, decryptPin(entry.code))
        }
        pending.remove(key)
        savePendingCodes(pending)
        return
    }
    if (key != null) {
        // A change we didn't ask for (e.g. made at the lock): read it back.
        requestCode(key as Integer)
        return
    }
    ofKind.keySet().each { requestCode(it as Integer) }
}

private void rejectPending(Integer slot, String reason) {
    def pending = pendingCodes()
    def sets = pending.findAll { it.value.op == "set" }
    def key = slot != null ? slot.toString() : (sets.size() == 1 ? sets.keySet().first() : null)
    if (key == null || !pending.containsKey(key)) {
        log.warn "${device.displayName}: the lock rejected a new code (${reason})"
        return
    }
    def entry = pending.remove(key)
    savePendingCodes(pending)
    log.warn "${device.displayName}: code ${entry.name} (slot ${key}) was not added: ${reason}"
    codeChangedEvent("failed", key, entry.name)
}

def checkPendingCodes() {
	if (traceEnable) log.trace "checkPendingCodes()"
    withCodeLock { checkPendingCodesLocked() }
}

private void checkPendingCodesLocked() {
    def pending = pendingCodes()
    if (!pending) return
    long cutoff = now() - pendingTimeoutSeconds * 1000L
    boolean waiting = false
    pending.keySet().collect().each { key ->
        def entry = pending[key]
        if ((entry.at as Long) > cutoff) { waiting = true; return }
        if (!entry.retried) {
            entry.retried = true
            entry.at = now()
            waiting = true
            requestCode(key as Integer)
        } else {
            pending.remove(key)
            log.warn "${device.displayName}: the lock never confirmed ${entry.op == 'delete' ? 'deleting' : 'setting'} slot ${key} (${entry.name})"
            codeChangedEvent("failed", key, entry.name)
        }
    }
    savePendingCodes(pending)
    if (waiting) runIn(pendingTimeoutSeconds, "checkPendingCodes", [overwrite: true])
}

private void applySet(String slot, String name, String pin) {
    def codes = loadLockCodes()
    def existing = codes[slot]
    def finalName = name ?: existing?.name ?: "User ${slot}"
    if (existing && existing.code == pin && existing.name == finalName) {
        // No change, but Lock Code Manager is waiting for confirmation of what it sent.
        codeChangedEvent("changed", slot, finalName)
        return
    }
    codes[slot] = [name: finalName, code: pin, status: "active"]
    updateLockCodes(codes)
    codeChangedEvent(existing ? "changed" : "added", slot, finalName)
}

private void applyDelete(String slot) {
    def codes = loadLockCodes()
    def existing = codes.remove(slot)
    if (existing) updateLockCodes(codes)
    codeChangedEvent("deleted", slot, existing?.name ?: "User ${slot}")
}

private void codeChangedEvent(String value, slot, name) {
    def text = "${device.displayName} code ${name} (slot ${slot}) ${value}"
    if (txtEnable) log.info text
    sendEvent(name: "codeChanged", value: value, descriptionText: text, data: [(slot.toString()): [name: name]], isStateChange: true)
}

private void requestCode(Integer slot) {
    if (slot == null) return
    executeCommand(zwaveSecureEncap(zwave.userCodeV1.userCodeGet(userIdentifier: slot)))
}

def zwaveEvent(UserCodeReport cmd) {
	if (traceEnable) log.trace "zwaveEvent(UserCodeReport cmd): slot ${cmd.userIdentifier} status ${cmd.userIdStatus}"
    withCodeLock { userCodeReportLocked(cmd) }
    return null
}

private void userCodeReportLocked(UserCodeReport cmd) {
    def slot = cmd.userIdentifier.toString()
    def pending = pendingCodes()
    def entry = pending[slot]

    if (cmd.userIdStatus == UserCodeReport.USER_ID_STATUS_OCCUPIED) {
        def pin = cmd.userCode?.toString()
        // When adding a code, ZW3 has been observed to send 3 UCRs: full code, first digit, then full code again.
        // Usually this is fine since the last one takes, but sometimes it's dropped and we end up with the truncated version.
        // This ignores any truncated code -- safe since Yale lock codes are min 4 digits anyway.
        if (!pin || pin.length() == 1) return
        if (entry?.op == "delete") {
            pending.remove(slot)
            savePendingCodes(pending)
            log.warn "${device.displayName}: slot ${slot} (${entry.name}) is still occupied after a delete"
            codeChangedEvent("failed", slot, entry.name)
            return
        }
        if (entry?.op == "set") {
            pending.remove(slot)
            savePendingCodes(pending)
            if (pin != decryptPin(entry.code)) {
                // The lock kept a different code in that slot, so ours didn't take.
                log.warn "${device.displayName}: slot ${slot} holds a different code than the one sent for ${entry.name}"
                codeChangedEvent("failed", slot, entry.name)
                applyReadBack(slot, pin)
                return
            }
            applySet(slot, entry.name, pin)
            return
        }
        applyReadBack(slot, pin)
    } else {
        if (entry) {
            pending.remove(slot)
            savePendingCodes(pending)
            if (entry.op == "set") {
                log.warn "${device.displayName}: slot ${slot} is empty, so the code for ${entry.name} didn't take"
                codeChangedEvent("failed", slot, entry.name)
                return
            }
        }
        if (loadLockCodes().containsKey(slot) || entry?.op == "delete") applyDelete(slot)
    }
    return
}

// A code the lock reports that we didn't ask for (set at the lock, or a read-back with nothing pending).
private void applyReadBack(String slot, String pin) {
    def codes = loadLockCodes()
    def existing = codes[slot]
    if (existing?.code == pin) return
    codes[slot] = [name: existing?.name ?: "User ${slot}", code: pin, status: "active"]
    updateLockCodes(codes)
    codeChangedEvent(existing ? "changed" : "added", slot, codes[slot].name)
}

def setCode(codePosition, pin, codeName = null) {
	if (traceEnable) log.trace "setCode(${codePosition}, ****, ${codeName})"
    def slot = codePosition as Integer
    if (!codeName) codeName = getCodeName(slot)
    addPending(slot, [op: "set", name: codeName.toString(), code: encryptPin(pin.toString())])
    executeCommand(zwaveSecureEncap(zwave.userCodeV1.userCodeSet(userIdentifier: slot, userIdStatus: 1, userCode: pin.toString())))
}

def deleteCode(codePosition) {
	if (traceEnable) log.trace "deleteCode(codePosition): ${codePosition}"
    def slot = codePosition as Integer
    if (txtEnable) log.info "${device.displayName} deleting code ${getCodeName(slot)} (slot ${slot})"
    addPending(slot, [op: "delete", name: getCodeName(slot)])
    executeCommand(zwaveSecureEncap(zwave.userCodeV1.userCodeSet(userIdentifier: slot, userIdStatus: 0)))
}

def getCodes() {
	if (traceEnable) log.trace "getCodes()"
    executeCommand(zwaveSecureEncap(zwave.userCodeV1.usersNumberGet()))
}

def setCodeLength(length) {
    // Yale codes are 4-8 digits, set in the Yale Access app; the lock doesn't take a length over Z-Wave.
    log.warn "${device.displayName}: code length is set in the Yale Access app, not from Hubitat"
}

private getCodeName(slotId) {
    def codes = loadLockCodes()
    def entry = codes["${slotId}"]
    return entry?.name ?: "User ${slotId}"
}

private String encryptPin(String pin) {
    return encrypt(pin) ?: pin
}

private String decryptPin(String stored) {
    if (stored == null) return null
    return decrypt(stored) ?: stored
}

private Map loadLockCodes() {
    def lockCodes = device.currentValue("lockCodes", true)
    if (!lockCodes) return [:]
    def raw = lockCodes.toString()
    def json = raw.startsWith("{") ? raw : (decrypt(raw) ?: raw)
    try {
        return (parseJson(json) ?: [:]) as Map
    } catch (e) {
        log.warn "${device.displayName}: couldn't read the stored lock codes"
        return [:]
    }
}

private void updateLockCodes(Map lockCodes) {
    def json = groovy.json.JsonOutput.toJson(lockCodes)
    def value = (optEncrypt == false) ? json : (encrypt(json) ?: json)
    sendEvent(name: "lockCodes", value: value, descriptionText: "Lock codes updated")
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
	if (traceEnable) log.trace "zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd): ${cmd}"
    // 0xFF (255) is Z-Wave's standard report for a low battery warning
    def percent = (cmd.batteryLevel == 0xFF) ? 1 : cmd.batteryLevel

    def descText = "${device.displayName} battery is ${percent}%"

    if (txtEnable && device.currentValue("battery") != percent) log.info descText

    sendEvent(name: "battery", value: percent, unit: "%", descriptionText: descText)
}

// Catch-all for any other unhandled Z-Wave commands to prevent future MissingMethodExceptions
def zwaveEvent(hubitat.zwave.Command cmd) {
	if (traceEnable) log.trace "zwaveEvent(hubitat.zwave.Command cmd): ${cmd}"
    if (debugEnable) log.debug "Unhandled Z-Wave Command: $cmd"
}

def zwaveEvent(hubitat.zwave.commands.usercodev1.UsersNumberReport cmd) {
	if (traceEnable) log.trace "zwaveEvent(hubitat.zwave.commands.usercodev1.UsersNumberReport cmd): ${cmd}"
    if (debugEnable) log.debug "UsersNumberReport: lock supports ${cmd.supportedUsers} codes"
    // The lock counts slots it keeps for itself (programming code etc.) above 250, so cap there.
    def max = cmd.supportedUsers ? Math.min(cmd.supportedUsers as Integer, 250) : 250
    sendEvent(name: "maxCodes", value: max)
}

// --- Actuator Commands ---
def lock() {
	if (traceEnable) log.trace "lock()"
    executeCommand(zwaveSecureEncap(zwave.doorLockV1.doorLockOperationSet(doorLockMode: DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_SECURED)))
}

def unlock() {
	if (traceEnable) log.trace "unlock()"
    executeCommand(zwaveSecureEncap(zwave.doorLockV1.doorLockOperationSet(doorLockMode: DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED)))
}

def refresh() {
	if (traceEnable) log.trace "refresh()"
    def cmds = []
    cmds << zwaveSecureEncap(zwave.doorLockV1.doorLockOperationGet())
    cmds << zwaveSecureEncap(zwave.batteryV1.batteryGet())
    // No DoorSense request here: asked for the door state, the lock answers with an empty "idle"
    // notification (event 0) on Z-Wave JS, so contact only updates when the door moves.
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZWAVE))
}

// General App Events
def configure() {
	if (traceEnable) log.trace "configure()"
    log.info "Configuring Association Groups..."
    // Group 1 is the Lifeline for Z-Wave locks, required to report events to the Hub
    def hubNode = zwaveHubNodeId
    def cmds = []
    cmds << zwaveSecureEncap(zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: hubNode))
    cmds << zwaveSecureEncap(zwave.associationV1.associationGet(groupingIdentifier: 1))
    cmds << zwaveSecureEncap(zwave.userCodeV1.usersNumberGet())
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZWAVE))
    refresh()
}

def initialize() {
    state.DriverVersion = getDriverVersion()
    state.remove("slotId")      // replaced by pendingCodes in 1.08
    state.remove("pendingDelete")
    state.remove("pendingCodes")    // kept in state by early 1.08 builds
    atomicState.remove("pendingCodes")
    unschedule("checkPendingCodes")
    heldLockReports.remove(deviceKey())

    //detect zwave module that's installed
    def firstChar = getZWaveDeviceId()?.take(1)
    if (firstChar == "0") {
        log.info "ZW2 module detected"
        state.module = "ZW2"
    } else if (firstChar == "4") {
        log.info "ZW3 module detected"
        state.module = "ZW3"
    } else {
        log.info "Unknown module detected"
    }

    if (device.currentValue("maxCodes") == null) sendEvent(name: "maxCodes", value: 250)
    // Drop the lock's own slots (programming code etc.) and re-save in the chosen format (encrypted or not).
    def lockCodes = loadLockCodes()
    for (int i = 251; i < 256; i++) lockCodes.remove(i.toString())
    updateLockCodes(lockCodes)
}

def installed(){
	initialize()
}

def updated(){
	initialize()
    unschedule("logsOff")
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug and trace logging turned off"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

private executeCommand(command) {
	if (traceEnable) log.trace "executeCommand(command): ${command}"
    sendHubCommand(new hubitat.device.HubAction(command, hubitat.device.Protocol.ZWAVE))
}
