# Changelog

All notable changes to this project will be documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added
- macOS launchd setup guide in `docs/platform-setup.md`
- Debug logging preference in the Bambu Bridge app (`logEnable`)
- Docker healthcheck — reports unhealthy if no successful Hubitat push in 5 minutes
- `LICENSE` file (MIT)

### Changed
- Dashboard tile timestamps now display in the viewer's local timezone (falls back to UTC if JS unavailable)
- Print error codes now formatted as hex (e.g. `0x0700010B`) to match Bambu documentation; includes note to check printer display
- Chamber temperature now shows any non-zero reading (previously suppressed readings ≤ 5°C)
- Light theme heating temperature color (`#BA4A00`) now meets WCAG AA for all text sizes
- MQTT keepalive reduced from 120s to 60s for faster stale-connection detection
- Dockerfile updated to Python 3.12
- AMS CSS extracted to shared helper — no longer duplicated between combined and AMS-only page builders
- Favicon suppression added to tile pages (prevents spurious 404s on every tile load)
- App description no longer implies Docker is the only deployment option

### Fixed
- Chamber temperature not reported on recent firmware — newer Bambu firmware moved the chamber temp field from `chamber_temper` (flat float) to `device.ctc.info.temp` (packed integer). Bridge now checks both locations, so all firmware versions are handled correctly.
- Duplicate em-dash entry in AMS tray `isEmpty` check (harmless but now cleaned up)
- `docs/platform-setup.md` platform table referenced "see main README" for macOS with no corresponding section in either file
