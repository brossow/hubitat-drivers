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

1. In Hubitat, go to **Drivers Code → New Driver**
2. Paste the contents of the desired `.groovy` file and click **Save**
3. Go to **Devices → Add Device → Virtual**, give it a name, and select the driver type
4. Enter your EcoNet email and password in preferences and click **Save Preferences**

---

## Thermostat

### Features
- Reads current temperature, heat/cool setpoints, HVAC mode, fan speed, and humidity
- Sets mode, setpoints, and fan speed/mode via the ClearBlade HTTP→MQTT bridge
- Enforces device deadband in auto mode (sends both setpoints in one command)
- Away mode (`awayMode` attribute + `setAwayMode()` command)
- Configurable poll interval; automatic token re-auth on expiry
- Supports multiple thermostats on one account (select by index)

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
| Thermostat Index | Which thermostat to control if you have multiple (0 = first) |
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
| Water Heater Index | Which water heater to control if you have multiple (0 = first) |
| Temperature Unit | `F` (default) or `C` |
| Poll Interval | How often to refresh state from the cloud (default: 5 minutes) |
| Enable Debug Logging | Logs detailed info to the Hubitat log (auto-disables after 30 minutes) |

---

## Notes

- **Cloud-dependent**: All communication goes through Rheem's ClearBlade cloud API. Local control is not possible.
- **Commands**: Sent via the ClearBlade REST messaging endpoint (`POST /api/v/1/message/{systemKey}/publish`), which proxies to the underlying MQTT broker — the same mechanism used by the Rheem mobile app.
- **Multiple devices**: If you have more than one thermostat or water heater on your account, create a separate Hubitat device for each and set the index preference (0, 1, 2, …) accordingly.

## Credits

Inspired by the [Home Assistant EcoNet integration](https://github.com/home-assistant/core/tree/dev/homeassistant/components/econet) and the [pyeconet library](https://github.com/w1ll1am23/pyeconet) by [@w1ll1am23](https://github.com/w1ll1am23).
