# Changelog

## [0.2.0] - 2026-04-25

Added:

- Normalized and exposed Netatmo daily minimum/maximum temperature values and timestamps for base, outdoor, and indoor modules.
- Added knots as a wind speed unit preference.
- Added wind direction display preference for numeric angle, text direction, or both.
- Added wind direction string attributes for wind, gust, and maximum wind readings.

## [0.1.1] - 2026-04-25

Changed:

- Added **Run poll now** action to verify the parent polling path without waiting for the next scheduled interval.
- Improved first-poll status text so a newly scheduled poll does not look inactive before its first run.
- Added **Clear field diagnostics** action to remove persisted field inspection output from the app page.

## [0.1.0] - 2026-04-25

Initial development/public preview release.

Added:

- Netatmo OAuth authentication using Hubitat-compatible callback handling
- Token refresh and shared Netatmo API request wrapper
- Station and module discovery
- Stable device network ID model
- Child devices for:
  - Netatmo Weather Base Station
  - Netatmo Weather Outdoor Module
  - Netatmo Weather Indoor Module
  - Netatmo Weather Rain Gauge
  - Netatmo Weather Wind Gauge
- Manual child creation/update for selected supported devices
- Child refresh through the parent app
- Scheduled polling for existing selected child devices
- Field diagnostics for raw and normalized data availability
- App-level unit preferences for temperature, pressure, rain, and wind speed
- `measurementTime` support from Netatmo dashboard readings
- Public-ready integration naming:
  - Netatmo Weather Station Connect
  - Netatmo Weather device drivers
- Hubitat Package Manager package metadata
- Apache-2.0 license metadata

Notes:

- This is not a stable 1.0 release.
- Base station and outdoor module behavior has had live Hubitat testing.
- Indoor module, rain gauge, and wind gauge support is implemented using the same normalized parent-to-child pattern and may need broader hardware validation.
