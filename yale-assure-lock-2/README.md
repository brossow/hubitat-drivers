# Yale Assure Lock 2 (Z-Wave)

> **Hub-tested, offered upstream.** This is 1.08 of Sleuth255/Trunzoc's [Yale Assure Lock 2 driver](https://github.com/sleuth255/Yale_YRD4xx_Hubitat_driver) ([community thread](https://community.hubitat.com/t/release-yale-assure-lock-2-zwave-driver/165918)), with fixes intended to go back upstream as a pull request. It isn't in Hubitat Package Manager. If you use the HPM version, stay with it unless you want to test this one.

A Hubitat driver for Yale Assure Lock 2 deadbolts (YRD4xx) with the green ZW2 or black ZW3 Z-Wave module. It reports DoorSense (door open/closed), works with Lock Code Manager, and names the user for keypad unlocks.

## What 1.08 changes

Most of the fixes are for Hubitat's **Z-Wave JS** stack, which passes some lock messages to drivers differently from the older ZIP stack. Several come from [kdb-jc's fork](https://github.com/kdb-jc/Yale_YRD4xx_Hubitat_driver/tree/personal).

- **Keypad unlocks name the user on Z-Wave JS.** 1.07 reported them as "User null", and Rule Machine's "lock code entered" trigger couldn't match anyone.
- **Door open/close no longer fires 2–4 times.** Opening the door no longer reports a phantom lock either.
- **A keypad unlock fires one event**, named with the user, not a bare "unlocked" followed by the named one. A code add or delete fires `codeChanged` once.
- **Lock codes can be stored encrypted** (on by default), so PINs aren't on view on the device page or to apps that read device attributes. The driver's own logs mask PINs.
- **Codes are confirmed by the lock.** A code appears in Hubitat only once the lock confirms it. A code the lock rejects, for example a duplicate, reports `codeChanged: failed` instead of showing as active. Lock Code Manager can add or delete several codes at once.
- **Fixed crashes:** changing the code in an occupied slot, `setCodeLength`, and Initialize on a device with no `deviceId`.
- **Lock state is accurate.** A bolt in an unknown position reads `unknown`, not unlocked. "Did not fully lock" is reported as that, not as a jam.
- **Logging:** debug logging is off by default, and debug and trace both turn themselves off after 30 minutes.

The full list is in [CHANGELOG.md](CHANGELOG.md).

## Installing for testing

1. **Drivers code → Yale Assure Lock 2** (the HPM copy), or **New driver** if you don't have it.
2. Replace the code with the raw file from this folder and **Save**. The name and namespace match upstream, so devices already using the driver pick it up. HPM will put 1.07 back if it updates the package.
3. On each lock's device page, click **Initialize**. This clears 1.07's state and re-saves the stored codes in the chosen format.

If you're moving a lock from another driver (such as Generic Z-Wave Lock), change **Type** on the Device Info tab and save, then click **Configure**. Check the **Preferences** tab afterwards: switching drivers can save "Encrypt stored lock codes" as off. Turn it on and click **Save**, which also runs Initialize. To switch back to another driver later, turn encryption off and save first, since other drivers can't read encrypted codes.

Import URL:

```
https://raw.githubusercontent.com/brossow/hubitat-drivers/main/yale-assure-lock-2/YaleAssure2.groovy
```

## Preferences

| Preference | Default | Notes |
|---|---|---|
| Encrypt stored lock codes | On | Lock Code Manager works either way. Turning it off stores codes as plain JSON, as 1.07 did. |
| descriptionText logging | On | |
| Debug logging | Off | Turns itself off after 30 minutes. |
| Trace logging | Off | Raw Z-Wave messages, which can include PINs. Turns itself off after 30 minutes. |

## Known limits

- **On Z-Wave JS a fingerprint unlock reads as a keypad unlock.** The hub doesn't pass on the value that tells them apart, so it's named after the code in the same slot. On the old stack it's reported as "Fingerprint", as in 1.07.
- **Code length is set in the Yale Access app.** `setCodeLength` only logs a warning.
- **The door state updates only when the door moves.** Asked for it, the lock answers with an empty notification (tested on a ZW3 lock on Z-Wave JS), so Refresh can't fill in `contact`.
- **Hubitat logs command arguments.** Calling `setCode` from the device page records the PIN in the device's event history. That's the platform, not this driver.

## Tests

`tests/run.sh` runs the off-hub tests (Java 8+, no hub needed). `DRIVER=path/to/other.groovy tests/run.sh` runs them against another copy, such as upstream 1.07, to show which bugs each test catches.

## Credits and license

The original driver is by Trunzoc/Sleuth (sleuth255), with Z-Wave JS fixes by kdb-jc and further changes by brossow. Licensed under the [Apache License 2.0](LICENSE), as the original is.
