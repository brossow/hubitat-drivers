# ReBLLED LED Controller — Hubitat Driver

Live status and control for a [ReBLLED](https://github.com/brossow/ReBLLED) controller: the alternative firmware for the BLLED LED controller that lights a Bambu Lab printer's chamber from the printer's local MQTT. The driver shows what the strip is showing and why, the printer status the controller knows (printing, paused, finished, door, chamber light, errors), and switches the strip and sets its brightness.

It talks only to the controller on your network, never to the printer or the cloud. For full printer details (temperatures, AMS, progress), use a printer driver alongside it.

Needs ReBLLED 1.0.0 or later. The original BLLED firmware has no API, so it won't work with this driver.

## Installation

1. In Hubitat, go to **Drivers Code → New Driver**, click **Import**, paste
   `https://raw.githubusercontent.com/brossow/hubitat-drivers/main/reblled/reblled-controller.groovy` and click **Save**
2. Go to **Devices → Add device → Virtual**, pick **ReBLLED LED Controller** and name it (e.g. "Printer lights")
3. Open the device, enter the controller's **address** in Preferences and click **Save**

Status should appear within a few seconds. Give the controller a fixed address (a DHCP reservation in your router), so the driver can always find it.

### Login (only for control)

Status comes over the controller's web socket, which needs no login. Switching the strip and changing brightness go through its JSON API, which asks for the WebUI login if you set one under **Update → WebUI login** on the controller. Enter the same user name and password in the driver's Preferences. Without a login on the controller, leave them blank.

## How it works

The controller pushes its status every second over `ws://<address>/ws`, and the driver keeps that socket open. It sends an event only when a value changes, so a quiet printer makes no events at all; countdowns that tick every second are left out of the attributes for that reason. If the socket drops, the driver reconnects after 5 seconds, doubling the wait up to 5 minutes, and marks the device `offline` until pushes return. While the socket is down and a login is entered, it also reads `/api/status` once a minute.

Commands use `POST /api/settings`: on/off and **Set Mode** change the controller's mode, **Set Level** its brightness. The reply carries the settings now in effect, so `switch`, `mode` and `level` update at once.

## Attributes

| Attribute | Values |
|-----------|--------|
| `switch` | `on` when the mode is Follow or Always on, `off` when it's Off |
| `level` | Strip brightness, 0–100 % |
| `mode` | `follow` (show the printer's status), `on` (always on), `off` |
| `strip` | `on` while the strip is actually lit. In Follow mode the strip can be dark with `switch` on, e.g. idle or following the printer's light |
| `stripColor` | What the strip shows: `neutral white`, `#FF0000, blink`, `color cycle`, `off`, … |
| `status` | What the strip shows and why, in words: *Printing*, *Preparing: leveling the bed*, *Off: following the printer's light, which is off* |
| `reason` | The same as the controller's code: `printing`, `idle`, `idle_off`, `light_off`, `finished`, `error`, `attention`, `mode_off`, … |
| `printerStatus` | `idle`, `preparing`, `printing`, `paused`, `finished`, `failed`, `offline` |
| `attention` | `none`, or what needs doing at the printer: `paused`, `first-layer problem`, `nozzle clog`, `filament runout`, `front cover off`, `nozzle temperature`, `bed temperature`, `serious HMS error`, `fatal HMS error` |
| `lastPrintFinished` | Local time the last print finished (`2026-10-19 14:05`). Sent only when a print the driver saw running finishes, so reconnecting to a printer still showing an old finish doesn't count |
| `door` | `open` / `closed` (printers with a door sensor) |
| `printerLight` | The printer's chamber light: `on` / `off` |
| `printerConnection` | The controller's link to the printer: `connected`, `connecting`, `unreachable`, `access code rejected`, `not verified` |
| `printerModel` | `X1C`, `P1S`, … |
| `healthStatus` | `online` while the controller is reachable from the hub, `offline` when it isn't |
| `firmware` | ReBLLED version on the controller |
| `rssi` | Controller Wi-Fi signal (dBm), sent when it moves 5 dBm or more |

The HMS errors come from the controller's own decision, which it only makes while following the printer (mode Follow). In the other modes `attention` is read from the printer's stage, which covers everything except HMS errors.

## Commands

| Command | What it does |
|---------|--------------|
| **On** | Mode Follow, or Always on if you chose that under *Switching on means* |
| **Off** | Mode Off |
| **Set Level** | Brightness. 0 switches off and keeps the brightness for next time; any other level also switches on |
| **Set Mode** | `follow`, `on` or `off` |
| **Refresh** | Reconnects the socket if it's down, and reads `/api/status` if it can |
| **Initialize** | Reconnects (the hub runs it at startup) |

## Automation ideas

- **Announce a finished print:** trigger on `lastPrintFinished` *changed*; speak or send *Print finished on the X1C*.
- **Something needs attention:** trigger on `attention` *changed*, condition `attention` is not `none`; send `%value%` as the message.
- **Lights off at bedtime:** turn the device off; turn it on in the morning. Switching on returns to Follow mode, so the strip goes back to showing the printer's status.
- **Dim at night:** Set Level 30 at sunset, 100 at sunrise.

## Preferences

| Setting | Description |
|---------|-------------|
| **Controller address** | IP address or host name of the controller (a pasted `http://…` link works too) |
| **WebUI user name / password** | Only for control, and only if the controller has a WebUI login |
| **Switching on means** | *Follow the printer* (default) or *Always on* |
| **Debug logging** | Turns itself off after 30 minutes |
| **Description text logging** | Logs each change to the attributes above |

## Tests

`tests/run.sh` runs the off-hub tests (Java 8 or later; Groovy is fetched on first use). They feed the driver pushes in the controller's format, answer its HTTP requests, and check the events it sends, its requests and its reconnect timing.

## License

Apache License 2.0. See [LICENSE](LICENSE).
