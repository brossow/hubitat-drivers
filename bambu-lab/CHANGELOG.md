# Changelog

All notable changes to this project will be documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] — 2026-05-01

Initial release.

### Added
- Native MQTT driver — connects directly to the printer's on-board MQTT broker (TLS, port 8883) using Hubitat's built-in `interfaces.mqtt` client. No external bridge device, Docker container, or Python script required.
- Full AMS support: all units and trays, individual color swatches, material types, and remaining percentages; active tray highlighted in tile and tracked via `amsTrayNow` attribute
- Live dashboard tiles served from the companion app via OAuth-protected local HTTP endpoints — combined status + AMS tile (`html`) and standalone AMS tile (`htmlAms`)
- Elapsed print time (`printElapsed`) computed locally from print start; updated every minute
- Active filament type and color (`filamentType`, `filamentColor`) from the currently loaded tray, with colored swatch displayed in the tile during active prints
- Chamber light state (`chamberLight`, read-only)
- Connection status attribute and visual indicator in dashboard tile
- Push notifications: print finished, started, paused, error, filament change, progress milestones (25 / 50 / 75 / 90 %)
- Switch and dimmer automations: on print start, finish, and error; optional hub mode restriction
- Exponential back-off reconnect (30 s → 60 s → 120 s → 300 s cap) with stale connection detection
- Optional MQTT relay support for hubs where the direct TLS connection does not work
- Dark and light tile themes; AMS column layout control
- `refresh()` / `connect()` / `disconnect()` commands
- Dashboard tile timestamps display in the viewer's local timezone
- Print error codes formatted as hex (e.g. `0x0700010B`) to match Bambu documentation
- Favicon suppression on tile pages (prevents spurious 404s on every load)
- WCAG AA contrast compliance for both tile themes (4.5:1 normal text, 3:1 large text)
