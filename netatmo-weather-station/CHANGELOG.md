# Changelog

## [0.4.0] - 2026-08-12

This release is focused on setup and first-run experience. Existing working
installations are unaffected and need no changes.

Changed:

- The setup page now shows only **Netatmo API Credentials**, **Authorization**, and **Logging** until authorization succeeds. Diagnostics, Discovery, Child Devices, Units, and Polling appear once you are authorized, so a first-time setup page is no longer a wall of unusable controls. Logging stays visible throughout so debug logging can be enabled while troubleshooting authorization.
- Renamed the **Test getstationsdata** button to **Test Netatmo connection**, and reworded its status messages in plain language.
- The connection test now runs automatically right after a successful authorization, so the page reports **Netatmo connection OK** with station and module counts without any extra clicks.
- The authorization callback page now explains how to fix the specific failure it received, instead of only printing Netatmo's raw error code. Covers `redirect_uri_mismatch`, `invalid_client`, and `access_denied`.
- The callback page now refers to the *integration page* rather than the *app page*, matching Hubitat's current navigation.
- **Sync child labels from Netatmo names** is hidden until at least one child device exists, replaced by a note explaining when it becomes available. Previously it could be toggled on with no children present and would silently do nothing.
- **Clear stored Netatmo tokens** now appears only when authorized, and explains what it does and does not affect.
- The Authorization section now explains that a newly typed Client Secret is not registered until the field loses focus, which previously looked like the authorization link failing to appear.

Removed:

- The Netatmo callback URL is no longer displayed, and setup no longer asks you to paste it into a **Redirect URI** field on Netatmo. The integration never required one, and a stored Redirect URI was the cause of `redirect_uri_mismatch` failures when a Netatmo application was reused across hubs or an integration instance was recreated. Existing installations that already have a Redirect URI saved continue to work.

Documentation:

- Rewrote the setup walkthrough: explicit Netatmo developer portal navigation, a terminology table distinguishing the Netatmo developer application, the Hubitat integration, and the Netatmo mobile app, and corrected Hubitat navigation (**Integrations** → **Add user integration**).
- Added troubleshooting entries for `redirect_uri_mismatch` and `invalid_client`.

## [0.3.0] - 2026-06-18

Added:

- Exposed Netatmo `AbsolutePressure` as `absolutePressure` on the base station.
- Exposed Netatmo `Noise` through Hubitat's standard `soundPressureLevel` event on the base station, while keeping the existing `noise` event.
- Added `lastMessage` timestamp support from Netatmo `last_message`.
- Added battery voltage, firmware, and Netatmo data type metadata where Netatmo provides it.
- Added non-sensitive station place metadata for the base station: city, altitude, and timezone.
- Added optional Netatmo health index/status fields where Netatmo provides `health_idx`.
- Expanded field diagnostics to show selected raw metadata values and normalized metadata values.
- Base stations now report `lastSeen` and `lastMessage` as `Not provided` when Netatmo omits those communication timestamps.

## [0.2.1] - 2026-06-18

Changed:

- Added scheduled-poll stale detection in the parent app UI.
- Added **Reschedule polling** action to refresh Hubitat's scheduled poll job.
- Wrapped scheduled polling in top-level error handling so unexpected failures update app status instead of failing silently.
- Clarified setup documentation, including that package installation alone does not create child devices.

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
