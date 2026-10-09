# Changelog

All notable changes to this project will be documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] — unreleased

Released briefly on 2026-10-09 and withdrawn the same day; not in HPM. Based in part on jonnyborbs's [Bambu Lab 3D Printer driver and app](https://github.com/jonnyborbs/hubitat-bambu-printers) (Apache-2.0).

Initial release.

### Added
- Native MQTT driver — connects directly to the printer's on-board MQTT broker (TLS, port 8883) using Hubitat's built-in `interfaces.mqtt` client. No external bridge device, Docker container, or Python script required.
- Full AMS support: all units and trays, individual color swatches, material types, and remaining percentages; active tray highlighted in tile and tracked via `amsTrayNow` attribute. Partial updates from P1 and A1 printers are merged rather than replacing the AMS picture
- Live dashboard tiles served from the companion app via OAuth-protected local HTTP endpoints — combined status + AMS tile (`html`) and standalone AMS tile (`htmlAms`)
- Elapsed print time (`printElapsed`, H:MM) computed locally from print start; updated every minute. A finished print keeps its time, so the finish notification includes it
- Active filament type and color (`filamentType`, `filamentColor`) from the tray in use, including the external spool, with colored swatch displayed in the tile during active prints
- Chamber light state (`chamberLight`, read-only)
- Connection status attribute and visual indicator in dashboard tile
- Push notifications: print finished, started, paused, error, filament change
- Switch and dimmer automations: on print start, pause, finish, and error; optional hub mode restriction
- Back-off reconnect (20 s → 60 s → 180 s → 360 s cap) with stale connection detection
- Full status requested every 5 minutes by default (300 s minimum); live updates arrive in between
- `lastUpdate` is refreshed once a minute and on state changes, rather than with every message
- Optional MQTT relay support for hubs where the direct TLS connection does not work
- Dark and light tile themes; AMS column layout control
- `refresh()` / `connect()` / `disconnect()` commands
- Dashboard tile timestamps display in the viewer's local timezone
- Print error codes formatted as hex (e.g. `0x0700010B`) to match Bambu documentation
- Favicon suppression on tile pages (prevents spurious 404s on every load)
- WCAG AA contrast compliance for both tile themes (4.5:1 normal text, 3:1 large text)
- Off-hub tests (`tests/run.sh`), run by GitHub Actions
