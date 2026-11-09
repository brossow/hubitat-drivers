# Changelog

Versions before 1.08 are Sleuth255/Trunzoc's; see the [upstream repo](https://github.com/sleuth255/Yale_YRD4xx_Hubitat_driver).

## 1.08 (in development)

### From kdb-jc's fork (Z-Wave JS)
- Read the user slot from `eventParameter[0]` when there's no embedded User Code Report. On Z-Wave JS, 1.07 reported every keypad unlock as "User null".
- Ignore "Opening state" updates, which arrived as a Manual Lock on every door open.
- Stop forcing repeated door open/close reports through as state changes.
- Fix the `LockCodes` typo that threw when a code changed in an occupied slot, and define `commandClassVersions`, which the Supervision handler used.
- Report `doorLockMode` 0xFE (bolt position unknown) as `unknown`, not `unlocked`.

### Added
- **Encrypt stored lock codes** preference, on by default. Initialize re-saves existing codes in the chosen format.
- Handling for "all user codes deleted" (event 0x0C).
- Debug and trace logging turn themselves off after 30 minutes.

### Changed
- Lock codes are recorded once the lock confirms them (code added/deleted notification or a read-back), not when `setCode` is called. Each pending change is read back once if the lock doesn't answer within 20 seconds, then reported as failed.
- Pending changes are tracked per slot, so several codes can be added or deleted at once. This replaces `state.slotId`. Pending PINs are stored encrypted.
- `codeChanged` uses the LockCodes capability's values (`added`, `changed`, `deleted`, `failed`), with the slot and name in the event data. 1.07 sent values like "2 updated".
- A code rejected as a duplicate, a slot found empty after `setCode`, or a slot still occupied after `deleteCode` reports `codeChanged: failed`.
- Lock and battery events are no longer forced as state changes, so repeats are dropped. Keypad unlocks still always fire, for Rule Machine's "lock code entered".
- An unlock from a slot Hubitat doesn't know reports "unknown code N", not "User null".
- Fingerprint unlocks keep the matched user's name ("unlocked by Alex via fingerprint").
- "Not fully locked" events are reported as that, not as a jam.
- `maxCodes` follows the lock's reported slot count, capped at 250.
- Debug logging defaults to off. The driver's own logs mask PINs.

### Fixed
- `setCodeLength` threw (it's part of the LockCodes capability). It now logs that length is set in the Yale Access app.
- Initialize threw if the device had no `deviceId` data.
- Idle notifications (event 0) are ignored instead of logged as "Unknown event 0".

### Removed
- The AlarmReport handler. It was unreachable (notifications are parsed as v3) and called a method that didn't exist.
