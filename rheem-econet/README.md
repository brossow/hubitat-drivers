# Rheem EcoNet — Hubitat Drivers

Hubitat Elevation drivers for Rheem EcoNet thermostats and water heaters, inspired by the [Home Assistant pyeconet integration](https://github.com/home-assistant/core/tree/dev/homeassistant/components/econet). Uses the ClearBlade cloud REST API for polling and MQTT command publishing.

## Drivers

| Driver | File |
|---|---|
| EcoNet Thermostat | [`EcoNetThermostat.groovy`](EcoNetThermostat.groovy) |
| EcoNet Water Heater | [`EcoNetWaterHeater.groovy`](EcoNetWaterHeater.groovy) |

Each driver is self-contained — no parent app required.

---

## Installation

There is **no app to install** — this package is two standalone drivers. You create the Hubitat device yourself and point it at the driver.

1. In Hubitat, go to **Drivers Code → New Driver**
2. Paste the contents of the desired `.groovy` file and click **Save**
3. Go to **Devices → Add Device → Virtual**, give it a name, and select the driver type (`Rheem EcoNet Thermostat` or `Rheem EcoNet Water Heater`)
4. Enter your EcoNet email and password in preferences and click **Save Preferences**

That gives you one Hubitat device controlling one physical unit. If your EcoNet account has more than one, read the next section.

---

## If you have more than one thermostat or water heater

**One Hubitat device controls one physical unit.** Two thermostats on your account means two Hubitat devices — there's no discovery step that creates them all at once, and the first device you create doesn't get "all of them."

Once a device has polled successfully, it lists everything on your account under **State Variables** — on the device's **Commands** tab, not Preferences — one unit per row:

```
thermostat0    Upstairs — aa-bb-cc-dd-ee-ff-11-22-33
thermostat1    Downstairs — aa-bb-cc-dd-ee-ff-44-55-66
```

For each additional unit:

1. Repeat installation steps 3–4 to create another Hubitat device using the **same driver**.
2. Copy that unit's **serial number** out of its row above.
3. Paste it into the new device's **Thermostat serial number** (or **Water heater serial number**) preference and click **Save Preferences**.

The format doesn't matter — `aa-bb-cc-dd-ee-ff-11-22-33`, `AA:BB:CC:DD:EE:FF:11:22:33`, and `aabbccddeeff112233` are all accepted, as is the whole row pasted in with the name still attached.

You never have to type a serial for the *first* device. Leave the field blank and the driver fills it in the first time it connects, so every device ends up pinned whether or not you did anything.

### Why the serial and not the name

Names change, and EcoNet's default name for every unit is often just `Thermostat`, so they're frequently not unique to begin with. A name that stops matching can't fail safely — it just quietly stops selecting, and something else takes over. Serials don't move.

The driver only selects by serial. If you enter a name, it tells you so in the log and points you at the right serial.

### When selection fails, nothing gets controlled

If the serial you entered doesn't match anything, or matches more than one unit, the driver **logs an error and controls nothing** rather than falling back to a guess. A device that shows no data is a device you'll go look at; a device quietly running the wrong thermostat is not. Check the **Logs** tab — the error says exactly what went wrong.

### Zoned systems

Zones are discovered along with their parent thermostat and get their own rows. A zone that doesn't report a serial number of its own is identified by its internal device id instead, which works the same way — copy the row's identifier into the preference. This path is implemented but has never been tested on real hardware; reports welcome.

### Upgrading from 0.1.x

Earlier versions selected a unit with a **Thermostat index** preference. That setting is gone. The first time the new driver connects it reads whatever index you had, pins the device to that unit's serial number, and deletes the old setting — so a device carries on controlling exactly what it did before, and there is nothing for you to do. You'll see a line in the log confirming it.

---

## Thermostat

### Features
- Reads current temperature, heat/cool setpoints, HVAC mode, fan speed, and humidity
- Sets mode, setpoints, and fan speed/mode via the ClearBlade HTTP→MQTT bridge
- Enforces device deadband in auto mode (sends both setpoints in one command)
- Away mode (`awayMode` attribute + `setAwayMode()` command)
- Configurable poll interval; automatic token re-auth on expiry
- Supports multiple thermostats on one account, selected by serial number

### Supported HVAC Modes
`heat` · `cool` · `auto` · `fan only` · `emergency heat` · `off`

### Supported Fan Speeds
`auto` · `low` · `medium` · `high` · `max`

### Capabilities
`Thermostat` · `Refresh` · `Initialize`

### Preferences

| Setting | Description |
|---|---|
| EcoNet Email | Your Rheem EcoNet account email |
| EcoNet Password | Your Rheem EcoNet account password |
| Thermostat Serial Number | Which thermostat this device controls. Filled in automatically on first connect; set it yourself to choose a different one, copying from the `thermostat0` / `thermostat1` / … state variables on the Commands tab. |
| Poll Interval | How often to refresh state from the cloud (default: 5 minutes) |
| Enable Debug Logging | Logs detailed info to the Hubitat log (auto-disables after 30 minutes) |

---

## Water Heater

### Features
- Reads setpoint, operating mode, running state, and hot water tank level
- Sets temperature, mode, and away mode
- `Switch` capability maps to water heater on/off (restores last active mode when turned on)
- `ThermostatMode` capability exposes `heat` / `auto` / `emergency heat` / `off` for Rule Machine compatibility
- Handles all three EcoNet control styles: `@MODE` only, `@ENABLED` only, or both
- Mode list is read dynamically from the device — never hardcoded
- Correctly resolves the firmware's dual-mode `ELECTRICGAS` entry based on device type (gas vs. electric)
- Celsius/Fahrenheit selectable in preferences
- Configurable poll interval; automatic token re-auth on expiry

### Supported Modes
`off` · `electric` · `energy saving` · `heat pump` · `high demand` · `gas` · `performance` · `vacation`

Not all modes are available on every device — `supportedModes` attribute reflects what the device actually reports.

### Capabilities
`Switch` · `ThermostatHeatingSetpoint` · `ThermostatOperatingState` · `ThermostatMode` · `Refresh` · `Initialize`

### Custom Attributes

| Attribute | Values | Description |
|---|---|---|
| `waterHeaterMode` | string | Current operating mode |
| `supportedModes` | JSON array | Modes supported by this device |
| `thermostatMode` | `heat` / `auto` / `emergency heat` / `off` | RM-compatible mode derived from water heater mode |
| `supportedThermostatModes` | JSON array | RM thermostat modes available on this device |
| `hotWaterLevel` | 0 / 33 / 66 / 100 | Tank hot water availability |
| `awayMode` | `away` / `home` | Away mode state |
| `online` | `true` / `false` | Device connectivity |

### Preferences

| Setting | Description |
|---|---|
| EcoNet Email | Your Rheem EcoNet account email |
| EcoNet Password | Your Rheem EcoNet account password |
| Water Heater Serial Number | Which water heater this device controls. Filled in automatically on first connect; set it yourself to choose a different one, copying from the `waterHeater0` / `waterHeater1` / … state variables on the Commands tab. |
| Temperature Unit | `F` (default) or `C` |
| Poll Interval | How often to refresh state from the cloud (default: 5 minutes) |
| Enable Debug Logging | Logs detailed info to the Hubitat log (auto-disables after 30 minutes) |

---

## Notes

- **Cloud-dependent**: All communication goes through Rheem's ClearBlade cloud API. Local control is not possible.
- **Commands**: Sent via the ClearBlade REST messaging endpoint (`POST /api/v/1/message/{systemKey}/publish`), which proxies to the underlying MQTT broker — the same mechanism used by the Rheem mobile app.
- **Multiple devices**: One Hubitat device controls one physical unit — see [If you have more than one thermostat or water heater](#if-you-have-more-than-one-thermostat-or-water-heater) above.

## Credits

Inspired by the [Home Assistant EcoNet integration](https://github.com/home-assistant/core/tree/dev/homeassistant/components/econet) and the [pyeconet library](https://github.com/w1ll1am23/pyeconet) by [@w1ll1am23](https://github.com/w1ll1am23).
