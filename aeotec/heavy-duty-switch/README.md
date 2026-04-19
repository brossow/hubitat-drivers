# Aeotec Heavy Duty Smart Switch — Hubitat Driver

By [Brent Rossow](https://github.com/brossow). A maintained fork of [Sebastian YEPES' original driver](https://github.com/syepes/Hubitat/tree/master/Drivers/Aeotec), updated to fix several bugs and improve reliability with modern Hubitat versions.

The Aeotec Heavy Duty Smart Switch (ZW078) is a Z-Wave inline switch rated for up to 40A / 10,000W — designed for high-current loads like electric vehicle chargers, electric dryers, water heaters, and HVAC equipment. It reports power (W), energy (kWh), voltage (V), current (A), and internal board temperature.

## What's Fixed in v1.2.0

The original driver worked but had several bugs that have been corrected:

- **Firmware version not saved** — a variable name typo in the v1 VersionReport handler caused firmware info to silently fail to store for devices that respond with that command class version
- **State-check schedule off by 2x** — the "1h" interval option had value `2`, causing it to schedule every 2 hours instead of 1; a true 1h option is now present and a 2h option added
- **SwitchBinaryReport ignored** — the handler returned an empty list, dropping the event; switch state now updates correctly from binary reports
- **Hail command ignored** — when param80 is set to "Send HAIL", the hub received the notification but did nothing; it now triggers a state refresh
- **param80 default was "disabled"** — meant the hub got no proactive notifications when the switch changed state (e.g., from overload protection tripping); default changed to "Send BASIC Report"
- **Spurious powerlevel polling** — poll() and checkState() were querying Z-Wave RF signal level, which is unrelated to device state and added unnecessary radio traffic
- **on()/off() used Basic commands** — modernized to use SwitchBinary, which is the correct command class for switch control
- **Deprecated sendEvent parameter** — removed `displayed: true` from reset() calls

## Installation

### Via Hubitat Package Manager (recommended)

1. In HPM, click **Install** and search for **Aeotec Heavy Duty Smart Switch**
2. Follow the prompts — the driver installs automatically

### Manual

1. In Hubitat, go to **Drivers Code → New Driver**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/brossow/hubitat-drivers/main/aeotec/heavy-duty-switch/aeotec-heavy-duty-switch.groovy
   ```
3. Click **Import → Save**
4. Open your Aeotec Heavy Duty Smart Switch device and change the **Type** to **Aeotec Heavy Duty Smart Switch** (namespace: community)
5. Click **Save Device**, then **Configure**, then **Save Preferences**

> **Migrating from the original syepes driver?** You'll need to reassign the driver type on the device page (the namespace changed from `syepes` to `community`). All settings and history are preserved.

## Preferences

| Setting | Description |
|---------|-------------|
| **Log Level** | Verbosity of hub logging (warn by default) |
| **Log Description Text** | Log human-readable state change descriptions |
| **State Check** | How often to poll current switch state (independent of meter reporting) |
| **Respond to switch all** | Behavior when a Z-Wave Switch All command is broadcast |
| **Default Load state** | What state the switch restores to after a power outage |
| **Current Overload Protection** | Disconnect load after 5s if current exceeds 39.5A (recommended: Enabled) |
| **Report interval** | How often the device sends meter readings (watts, volts, amps, kWh) |
| **Report Instantaneous Voltage/Current/Watts/kWh** | Which meter values to include in each report |
| **Report Temperature** | Poll internal board temperature on the state check schedule |
| **Load change notifications** | How the device notifies the hub of switch state changes — **Basic Report** is recommended |
| **Minimum change in wattage/percentage** | Threshold for unsolicited wattage change reports |

## Capabilities

| Capability | Attributes |
|------------|------------|
| Switch | `switch` (on/off) |
| Power Meter | `power` (W) |
| Energy Meter | `energy` (kWh) |
| Current Meter | `amperage` (A) |
| Voltage Measurement | `voltage` (V) |
| Temperature Measurement | `temperature` (°F/°C — internal board temp) |

## Commands

- **on() / off()** — Control the switch
- **refresh()** — Request current state and all meter readings from the device
- **poll()** — Request meter readings only
- **configure()** — Push all preference settings to the device
- **reset()** — Zero out all accumulated energy/power readings and re-poll
- **clearState()** — Clear all driver state and device data (use if switching driver versions)

## Changelog

### v1.2.0 — 2026-04-18
- Fixed undefined variable `firmwareVersion` in versionv1 VersionReport handler (firmware0 branch)
- Fixed stateCheckInterval: value `2` was labeled "1h" but ran every 2 hours; corrected to true 1h (value `1`) and added 2h option
- Fixed SwitchBinaryReport handler (was silently returning empty list; now calls setSwitchEvent)
- Fixed Hail handler (was silently ignored; now triggers basicGet to refresh switch state)
- Changed param80 default from 0 (disabled) to 2 (Basic Report)
- Removed powerlevelGet() from poll() and checkState() — Z-Wave signal level is not device state
- Changed on()/off() from basicV1.basicSet to switchBinaryV1.switchBinarySet
- Removed deprecated `displayed: true` from sendEvent() calls in reset()

### v1.1.6 — 2022-08-18 (original by Sebastian YEPES)
- Original release

## Attribution

This driver is a fork of [Aeotec Heavy Duty Smart Switch](https://github.com/syepes/Hubitat/tree/master/Drivers/Aeotec) by [Sebastian YEPES](https://github.com/syepes), licensed under the Apache License 2.0. Original copyright retained per license terms.
