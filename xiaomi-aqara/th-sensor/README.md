# Xiaomi/Aqara Temperature & Humidity Sensor — Hubitat Driver

By [Brent Rossow](https://github.com/brossow). A maintained fork of [Markus Liljergren's original driver](https://github.com/markus-li/Hubitat/blob/release/drivers/expanded/zigbee-xiaomi-aqara-temperature-humidity-expanded.groovy) from the archived oh-lalabs.com project, updated to fix several bugs.

Supports Xiaomi and Aqara temperature/humidity sensors including models with barometric pressure (WSDCGQ11LM / lumi.weather) and the T1 sensor (lumi.sensor_ht.agl02).

## Supported Devices

| Model | Description |
|-------|-------------|
| WSDCGQ01LM (lumi.sens / lumi.sensor_ht) | Xiaomi T&H sensor — temperature and humidity only |
| WSDCGQ11LM (lumi.weather) | Aqara T&H sensor — temperature, humidity, and pressure |
| lumi.sensor_ht.agl02 | Aqara T1 T&H sensor — temperature, humidity, and pressure |
| RS-THP-MP-1.0 | Keen Home T&H sensor — temperature, humidity, and pressure |

## What Changed from the Original

The original driver worked for many users but had several bugs that have been corrected, and the codebase has been substantially cleaned up:

**Bug fixes (v1.1.0):**
- **Critical: pressure UI appeared on all models** — a logic error (`||` on a string literal is always truthy) caused `hasPressure` to be set `True` for every device regardless of model, surfacing the pressure unit/offset preferences and attempting pressure attribute reads on sensors that have no barometer
- **Pressure Resolution preference mislabeled** — the `pressureRes` input was titled "Humidity Resolution" instead of "Pressure Resolution"
- **lumi.sens not normalized on refresh** — the legacy `"lumi.sens"` model string (used by early WSDCGQ01LM firmware) was renamed in `parse()` but not during `refresh()` / initial join, leaving the device data with an unrecognized model name until the first hourly checkin
- **Fragile model matching** — `setCleanModelName` used `startsWith()` matching, which caused order-dependent bugs (e.g., `"lumi.sensor_ht.agl02"` matched `"lumi.sensor_ht"` if listed second); changed to exact matching
- **Keen fingerprint had wrong manufacturer** — `manufacturer: "LUMI"` on the Keen Home device would prevent correct join matching
- **`sensor_data_getAdjustedTemp` referenced non-existent setting** — used `tempUnitConversion` instead of `tempUnitDisplayed`; this function is superseded by the `Alternative` variant but was dead-wrong if ever called; also added missing Kelvin support to match the `Alternative` path
- **Temperature bounds ignored parameters** — `zigbee_sensor_parseSendTemperatureEvent` accepted `minAllowed`/`maxAllowed` parameters but hardcoded `-50`/`100` in the bounds check

**Improvements (v1.2.0):**
- **hasPressure not updated via parse()** — model string received during hourly checkin could leave `hasPressure` stale until next `refresh()`
- **Battery % could go below 0** — a very drained battery would produce a negative percentage; now clamped to [0, 100]
- **No pressure sanity check** — corrupt readings (e.g., from a failing battery) could produce implausible values; readings outside 500–1100 hPa are now rejected with a log warning
- **Added `batteryVoltage` attribute** — raw voltage is now reported alongside battery %
- **Added configurable reporting thresholds** — temperature and humidity events can be suppressed when the change is below a user-set threshold (defaults: 0.2° and 0.5%)
- **Removed ~1100 lines of dead code** — unused functions, broken helpers, and dev scaffolding that accumulated in the original

## Installation

### Manual

1. In Hubitat, go to **Drivers Code → New Driver**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/brossow/hubitat-drivers/main/xiaomi-aqara/th-sensor/zigbee-xiaomi-aqara-temperature-humidity.groovy
   ```
3. Click **Import → Save**
4. Open your sensor device and change the **Type** to **Zigbee - Xiaomi/Aqara Temperature & Humidity Sensor** (namespace: community)
5. Click **Save Device**, then **Save Preferences**

> **Migrating from the original oh-lalabs driver?** You'll need to reassign the driver type on the device page (namespace changed from `oh-lalabs.com` to `community`). All sensor history and settings are preserved.

### Via Hubitat Package Manager

HPM listing coming soon after initial testing.

## Preferences

| Setting | Description |
|---------|-------------|
| **Enable debug / info logging** | Controls log verbosity; debug auto-disables after 30 minutes |
| **Enable Last Checkin Date / Epoch** | Records when the device last reported |
| **Enable Presence** | Marks device not-present if no checkin for 3+ hours (requires checkin enabled) |
| **Enable Presence Warning** | Logs a warning when presence transitions |
| **Recovery Mode** | How aggressively to attempt recovery when the device stops checking in |
| **Battery Min / Max Voltage** | Calibrate battery % to your actual battery chemistry |
| **Displayed Temperature Unit** | System default, Celsius, Fahrenheit, or Kelvin |
| **Temperature Offset** | Calibration offset added to every reading |
| **Temperature Resolution** | Decimal places (0–2) |
| **Temperature Reporting Threshold** | Minimum change (°) required to fire a new event (default: 0.2) |
| **Humidity Offset** | Calibration offset added to every reading |
| **Humidity Resolution** | Decimal places (0–1) |
| **Humidity Reporting Threshold** | Minimum change (%) required to fire a new event (default: 0.5) |
| **Report Absolute Humidity** | Also report g/m³ alongside relative humidity |
| **Displayed Pressure Unit** | mbar, kPa, inHg, mmHg, or atm *(pressure models only)* |
| **Pressure Resolution** | Decimal places *(pressure models only)* |
| **Pressure Offset** | Calibration offset *(pressure models only)* |

## Capabilities & Attributes

| Capability | Attribute | Unit |
|------------|-----------|------|
| TemperatureMeasurement | `temperature` | °C / °F / K |
| RelativeHumidityMeasurement | `humidity` | % |
| PressureMeasurement | `pressure` | mbar / kPa / inHg / mmHg / atm *(pressure models)* |
| Battery | `battery` | % |
| PresenceSensor | `presence` | present / not present |
| — | `absoluteHumidity` | g/m³ *(optional)* |
| — | `batteryVoltage` | V |
| — | `batteryLastReplaced` | date string |
| — | `lastCheckin` | date |
| — | `driver` | version string |

## Commands

- **refresh()** — Re-read current values and re-configure presence/recovery schedules
- **resetBatteryReplacedDate** — Reset the battery replacement timestamp to now
- **resetRestoredCounter** — Reset the presence-restore event counter to 0
- **forceRecoveryMode(minutes)** — Manually trigger recovery polling for N minutes

## Changelog

### v1.2.0 — 2026-04-18
- Added configurable temperature and humidity reporting thresholds (`tempVariance`, `humidityVariance`)
- Added `batteryVoltage` attribute — now reports raw voltage alongside battery %
- Clamped battery % to [0, 100] — negative readings from a deeply-drained battery no longer produce invalid values
- Fixed `hasPressure` not updated when model string arrives via `parse()` (only was set during `refresh()`)
- Fixed `hasPressure` not set to `False` in the fallback branch of `refresh()`
- Added pressure sanity bounds (500–1100 hPa) — corrupt readings from low batteries are rejected with a log warning
- Removed ~1100 lines of dead code, unused helper functions, and dev-only scaffolding

### v1.1.0 — 2026-04-18
- Fixed critical logic bug: `hasPressure` was always `True` due to `|| "string"` being truthy
- Fixed `pressureRes` preference label (was "Humidity Resolution")
- Fixed `lumi.sens` not normalized during `refresh()` — added pre-normalization before `setCleanModelName`
- Changed `setCleanModelName` model matching from `startsWith()` to exact `==` to eliminate order-dependent fragility
- Fixed Keen Home fingerprint manufacturer (was incorrectly set to `"LUMI"`)
- Fixed `sensor_data_getAdjustedTemp` to use `tempUnitDisplayed` preference (not non-existent `tempUnitConversion`); added missing Kelvin branch
- Fixed `zigbee_sensor_parseSendTemperatureEvent` to use `minAllowed`/`maxAllowed` parameters instead of hardcoded literals
- Fixed `deviceCommand` undeclared variable (`def r =`)

### v1.0.1.1123 — original by Markus Liljergren (oh-lalabs.com)
- Original release

## Attribution

This driver is a community fork of the [Zigbee - Xiaomi/Aqara Temperature & Humidity Sensor](https://github.com/markus-li/Hubitat/blob/release/drivers/expanded/zigbee-xiaomi-aqara-temperature-humidity-expanded.groovy) driver by [Markus Liljergren](https://oh-lalabs.com), licensed under the GNU General Public License v3. Original copyright retained per license terms. The upstream repository is archived and has not been updated since 2020.
