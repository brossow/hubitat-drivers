# Bambu Lab → Hubitat Integration

Monitor your Bambu Lab 3D printer from a Hubitat Elevation dashboard. Live status tiles show print progress, temperatures, filament state, and AMS contents — all updated in real time from the printer's local MQTT broker.

![Dark theme status + AMS tile](screenshot-dark.png)

---

## How it works

```
Printer (LAN MQTT) → bambu_bridge (Docker) → Hubitat app (HTTP) → Dashboard tiles
```

1. **`bambu_bridge`** — a Python script that connects to the printer's built-in MQTT broker over your local network, parses status messages, and POSTs the data to Hubitat every time the printer reports a change.
2. **`BambuBridgeApp`** — a Hubitat app that receives those POSTs, throttles device event updates, and serves live HTML pages for the dashboard tiles.
3. **`BambuPrinterDriver`** — a Hubitat virtual device driver that stores all printer state as attributes. The dashboard tiles read from this device.

The bridge uses the printer's **local LAN MQTT broker** (port 8883). This works regardless of whether "LAN Only Mode" is enabled on the printer — LAN Only Mode only affects the Bambu cloud connection. Your Bambu app and remote access continue to work normally.

---

## Components

### `bridge/` — Python MQTT bridge

| File | Purpose |
|---|---|
| `bambu_bridge.py` | Main bridge script |
| `Dockerfile` | Container image definition |
| `docker-compose.yml` | Service definition for Docker Compose |
| `requirements.txt` | Python dependencies |
| `.env.example` | Environment variable template — copy to `.env` and fill in |

### Hubitat code

| File | Install via |
|---|---|
| `bambu-printer.groovy` | Hubitat → Drivers Code |
| `bambu-bridge-app.groovy` | Hubitat → Apps Code |

---

## Setup

### 1. Hubitat: install the driver and app

**Via Hubitat Package Manager (recommended):** search for **Bambu Lab Printer** in HPM and install. Both the driver and app are installed in one step.

**Manual install:**

1. In Hubitat, go to **Drivers Code** → **+ New Driver** → **Import**
   Paste this URL: `https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-printer.groovy`
2. Go to **Apps Code** → **+ New App** → **Import**
   Paste this URL: `https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-bridge-app.groovy`
3. **Important:** click **OAuth** → **Enable OAuth** on the Apps Code page before continuing — the dashboard tiles won't work without it
4. Go to **Devices** → **Add Device** → **Virtual**, name it (e.g. "Bambu Lab X1C"), select **Bambu Lab Printer** as the driver, and save
5. Go to **Apps** → **Add User App** → **Bambu Bridge**
6. Select the virtual device you created, configure preferences (see below), and save
7. Copy the **Bridge POST URL** shown on the app page — you'll need it in the next step

### 2. Bridge: configure and run

Copy `.env.example` to `.env` in the `bridge/` directory and fill in your values:

```env
BAMBU_IP=10.0.1.x           # Printer's local IP address
BAMBU_ACCESS_CODE=xxxxxxxx   # 8-character code: printer touchscreen → Settings → WLAN
BAMBU_SERIAL=01P00C000000000 # Serial number: Bambu Studio → Device tab, or printer touchscreen
HUBITAT_URL=http://...       # Paste the Bridge POST URL from the Bambu Bridge app page
```

#### Running with Docker (recommended)

```bash
cd bridge
docker compose up -d
```

The container restarts automatically unless stopped. To apply `.env` changes:

```bash
docker compose down && docker compose up -d
```

To view logs:

```bash
docker compose logs -f
```

#### Running without Docker

See **[docs/platform-setup.md](docs/platform-setup.md)** for step-by-step guides covering macOS, Raspberry Pi, Windows (NSSM and Task Scheduler), QNAP, Unraid, and general Linux. All guides include virtual environment setup and persistent service configuration.

### 3. Hubitat: set up the dashboard

1. Create a new Hubitat dashboard (or open an existing one)
2. Add an **Attribute** tile:
   - Device: your Bambu Lab Printer virtual device
   - Attribute: **`html`** — this is the combined status + AMS tile
3. Give the tile plenty of vertical space — portrait orientation (taller than wide) works well. Experiment with row and column span settings for your screen size.
4. **Recommended: set the tile background to transparent.** In dashboard settings → **Templates** → **Attribute** → **default** state → set **Background Color** transparency to 0. This lets the tile's own background show through cleanly. Note: this affects all Attribute tiles on that dashboard. The tile works without this step, but the dashboard's default tile color will appear as a border around the content.

Optionally, add a second **Attribute** tile using attribute **`htmlAms`** for a standalone AMS filament tile — useful for wide dashboard layouts where you want status and AMS side by side.

---

## App settings

Open **Apps** → **Bambu Bridge** to adjust these at any time. Changes take effect immediately without restarting the bridge.

### Update Frequency

How often the Hubitat device attributes are updated. The bridge receives data from the printer continuously; this controls how often those updates are forwarded to Hubitat device events.

| Option | Notes |
|---|---|
| 1 second ⚠️ | Very high event volume — may affect hub performance |
| 5 seconds ⚠️ | High event volume |
| 10 seconds | |
| **30 seconds** | **Default** |
| 1 minute | |
| 5 minutes | |

Printer state changes (e.g. IDLE → RUNNING) always trigger an immediate update regardless of this setting. The dashboard tiles refresh independently of device events, so even at 5-minute intervals the tile display is always current.

### Tile Appearance

**Color theme** — choose based on your dashboard background:
- **Dark** (default) — calibrated for dark dashboard backgrounds (~#1C1C1C)
- **Light** — calibrated for light dashboard backgrounds (~#F8F8F8)

Both themes are designed to meet WCAG AA contrast standards (4.5:1 for normal text, 3:1 for large text).

**Show AMS filament section in combined tile** — when enabled (default), the `html` tile shows printer status on top and AMS filament below. Disable this if you have no AMS or prefer status-only. The AMS section auto-hides if no AMS data is reported by the printer regardless of this setting.

**AMS tile columns** — controls how many columns the AMS unit grid uses:
- **Auto** (default) — the browser fits as many columns as the tile width allows, with a minimum unit width of 160px. A portrait tile typically shows 2 columns; a wide tile shows more.
- **1–4, 6, 8, 12** — fixed column count. Useful when Auto doesn't give the layout you want. The column count is automatically capped at the number of AMS units present, so no empty slots are shown.

---

## Dashboard tile details

### Combined tile (`html` attribute)

The primary tile. Shows:
- **Printer state** — Idle / Preparing / Printing / Paused / Finished / Failed, color-coded
- **File name** — the print job name
- **Progress bar** — percentage, layer count, estimated time remaining
- **Temperatures** — nozzle, bed, and chamber. Temperature text is color-coded:
  - Neutral (gray) — no active target (printer idle)
  - Amber — heating toward target
  - Green — at target temperature (within ±3°C)
  - Target temperatures are always shown; displays `—` when idle
- **Speed** — current speed profile and magnitude (shown during active prints)
- **AMS section** — filament units, swatch colors, material types, and remaining percentages (when enabled and AMS data is available)
- **Timestamp** — last data received from the printer, displayed in your local timezone
- **Auto-refresh** — tile content reloads automatically every 30 seconds; no manual page reload needed

### AMS-only tile (`htmlAms` attribute)

Same AMS content as the combined tile, served independently. Useful for wide layouts where you want status and filament on separate tiles side by side.

---

## Device attributes

All attributes are updated by the bridge and available for use in Hubitat automations, rules, and additional dashboard tiles.

| Attribute | Type | Description |
|---|---|---|
| `printerState` | string | `IDLE` · `PREPARE` · `RUNNING` · `PAUSE` · `FINISH` · `FAILED` |
| `printFile` | string | Current print job filename |
| `printProgress` | number | Progress 0–100% |
| `remainingTime` | number | Estimated minutes remaining |
| `currentLayer` | number | Current layer number |
| `totalLayers` | number | Total layer count |
| `nozzleTemp` | number | Nozzle temperature (°C) |
| `nozzleTargetTemp` | number | Nozzle target temperature (°C) |
| `bedTemp` | number | Bed temperature (°C) |
| `bedTargetTemp` | number | Bed target temperature (°C) |
| `chamberTemp` | number | Chamber temperature (°C) |
| `speedLevel` | string | Speed profile: Quiet · Standard · Sport · Ludicrous |
| `speedMagnitude` | number | Speed percentage |
| `printError` | string | Error code (`"0"` = no error) |
| `wifiSignal` | string | WiFi signal strength |
| `amsSummary` | string | Compact AMS summary (e.g. `A0T0:PLA/FF0000/75%`) |
| `amsTrayNow` | number | Active tray global index (255 = none/external spool) |
| `cameraUrl` | string | RTSP stream URL (`rtsps://bblp:<access_code>@<printer_ip>:322/streaming/live/1`) |
| `lastUpdate` | string | ISO-8601 UTC timestamp of last data |
| `html` | string | iframe stub for the combined dashboard tile |
| `htmlAms` | string | iframe stub for the standalone AMS dashboard tile |
| `driverVersion` | string | Installed driver version |

---

## Bridge environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `BAMBU_IP` | ✅ | — | Printer's local IP address |
| `BAMBU_ACCESS_CODE` | ✅ | — | 8-character WLAN access code from printer touchscreen |
| `BAMBU_SERIAL` | ✅ | — | Printer serial number |
| `HUBITAT_URL` | ✅ | — | Full endpoint URL from the Bambu Bridge app page |
| `RECONNECT_DELAY` | | `30` | Seconds between MQTT reconnect attempts |
| `LOG_LEVEL` | | `INFO` | `DEBUG` · `INFO` · `WARNING` · `ERROR` |

---

## Compatibility

- **Tested on:** Bambu Lab X1C
- **Should work on:** X1, P1S, P1P, A1, A1 Mini (same MQTT protocol)
- **Firmware:** Recent firmware is recommended for full feature compatibility. The MQTT payload format has evolved across firmware versions — running current or near-current firmware avoids known field location changes.
- **Multi-nozzle printers** (X2D, H2D, etc.): single-nozzle display only in v1. Multi-nozzle support requires knowing those models' MQTT payload format for additional nozzles — contributions welcome once those formats are documented. *(If you'd like to donate a unit to support development, please get in touch.)*
- **AMS Lite:** untested but expected to work; reports through the same MQTT fields
- **Multiple AMS units:** supported, up to 12 (hardware maximum)

---

## Troubleshooting

**Dashboard tiles are blank when viewing remotely**
- The tile `src` URLs point to your Hubitat hub's local IP address. They only load when your browser can reach that IP directly — i.e., on the same local network or over a VPN. Remote access via Hubitat's cloud relay (remote dashboard) will show a blank iframe because the browser can't reach a LAN address. This is a known limitation with no workaround in the current architecture.

**Bridge connects but Hubitat isn't receiving data**
- Confirm `HUBITAT_URL` in `.env` matches exactly what the Bambu Bridge app page shows
- After changing `.env`, restart with `docker compose down && docker compose up -d` (not `docker compose restart`)
- Check bridge logs: `docker compose logs -f`

**Hubitat app page shows no endpoint URL**
- OAuth must be enabled: Apps Code → Bambu Bridge → OAuth → Enable OAuth → Save

**Printer device not appearing in app setup**
- The device selector shows capability.sensor devices. Confirm the virtual device was created with the Bambu Lab Printer driver.

**Dashboard tile shows "Please select an attribute"**
- The tile attribute must be set to `html` (not a state or other attribute)

**AMS section not appearing**
- Confirm "Show AMS filament section" is enabled in app settings
- AMS data must be present in the printer's MQTT payload — this is automatic when an AMS is connected

---

## Support

If this integration saves you some time or makes your workflow better, a small donation via [PayPal](https://paypal.me/brossow) is always appreciated — though never expected.

---

## License

MIT
