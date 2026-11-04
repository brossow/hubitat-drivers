# Changelog

## [0.6.1] - 2026-11-04

Fixed:

- **Turning on webhooks paused scheduled polling for about 35 minutes.** The event Netatmo sends to confirm registration counted as webhooks arriving, so polls stood down and devices stopped updating until the 30-minute fallback ran. Only device events stand polls down now.

Testing: installed and checked on a real hub. Registering, removing and signature checks all work, and scheduled polls keep reading after turning webhooks on. No device event had arrived from Netatmo during that test, even though the station was reporting, so webhooks remain a trial and polling may still be doing the work.

## [0.6.0] - 2026-11-04

Netatmo plans to reduce API rate limits for polling-based integrations from 1 March 2027 and is encouraging a move to webhooks. It hasn't published the new numbers yet, and existing integrations keep working until then.

Fixed:

- **Reaching Netatmo's API limit was treated as a bad token.** Netatmo reports "maximum usage reached" with HTTP 403 and error code 26, and the integration answered every 403 by renewing the token and retrying, which only used more of the allowance. Now that answer, and HTTP 429, make it stop calling Netatmo for as long as `Retry-After` says (at most an hour), or 15 minutes if it doesn't say, and carry on by itself. You stay authorized, and the Polling section shows when requests resume. Other 403 answers that a new token can't fix no longer renew it either.

Changed:

- A rule refreshing several devices together now makes one Netatmo request rather than one per device. A device refresh within 60 seconds of the last read uses that read. Scheduled polls and **Run poll now** always read fresh data.
- The Polling section now says that Netatmo updates this data about every 10 minutes, so polling faster doesn't get newer values.

Added:

- **Webhooks (beta), off by default.** With **Receive webhooks from Netatmo** turned on, the integration registers with Netatmo's new webhook service. Each webhook's signature is checked, and a valid one prompts a read of your station data, at most once every 5 minutes. Scheduled polls stand down while webhooks are arriving and resume if none has arrived for 30 minutes. Netatmo doesn't document what a weather webhook contains, so the readings still come from the same place as polling, and the page shows the latest webhook with names and IDs hidden.

Testing: this release was checked with the off-hub tests, not on a hub. Webhooks haven't yet run against Netatmo's live service, so whether Netatmo accepts the hub's address and how often events arrive are still unconfirmed. Polling is unaffected if they don't work.

## [0.5.0] - 2026-10-10

Renamed:

- The integration is now **Netatmo Weather Station** (it was Netatmo Weather Station Connect), in Hubitat Package Manager, on the integration page and in its logs. "Connect" was a holdover from SmartThings naming. Child devices, settings and authorization are unchanged. An integration you've already added keeps the label it was given when you installed it; to update it, open the integration and edit its name.

Fixed:

- **A temporary failure renewing the Netatmo access token stopped polling until you reauthorized.** Netatmo access tokens last a few hours. If renewing one failed for any reason (a network blip, a timeout, a Netatmo outage), the integration marked itself not authenticated, and every later poll was skipped without trying again. Now only a definite rejection from Netatmo signs the hub out; anything temporary is logged as a warning and retried on the next poll.
- **An expired or revoked access token wasn't renewed and retried.** Netatmo reports these with HTTP 403, but the integration only renewed and retried on 401. Both now renew the token and retry the request once.
- When Netatmo does reject the token renewal, the log now says so and tells you to reauthorize.
- **Two things happening at once could sign the hub out.** Netatmo's refresh token can be used only once. If two parts of the integration renewed the access token at the same moment (for example a scheduled poll and a rule refreshing several devices), both sent the same refresh token, Netatmo refused the second, and the hub was signed out. An older execution could also save its out-of-date copy of the tokens over newer ones. Tokens are now shared by every part of the integration and saved immediately, and only one renewal runs at a time; anything waiting uses the new token instead of renewing again.

Added:

- `healthStatus` on every device: `online` while Netatmo reports the device reachable, `offline` when it doesn't. This is the attribute Hubitat uses for device health. Previously the base station and modules declared a `healthStatus` attribute meant for Netatmo's air-quality index, a Healthy Home Coach field that weather stations don't report, so it was never set. The rain and wind gauges now have it too.
- Off-hub tests (`tests/run.sh`), run by GitHub Actions.


## [0.4.0] - 2026-08-12

This release is focused on setup and first-run experience. Existing working
installations are unaffected and need no changes.

Changed:

- The setup page now shows only **Netatmo API Credentials**, **Authorization**, and **Logging** until authorization succeeds. Diagnostics, Discovery, Child Devices, Units, and Polling appear once you are authorized, so a first-time setup page is no longer a wall of unusable controls. Logging stays visible throughout so debug logging can be enabled while troubleshooting authorization.
- Renamed the **Test getstationsdata** button to **Test Netatmo connection**, and reworded its status messages in plain language.
- The connection test now runs automatically right after a successful authorization, so the page reports **Netatmo connection OK** with station and module counts without any extra clicks.
- The authorization callback page now explains how to fix the specific failure it received, instead of only printing Netatmo's raw error code. Covers `redirect_uri_mismatch`, `invalid_client`, and `access_denied`.
- The callback page now refers to the *integration page* rather than the *app page*, matching Hubitat's current navigation.
- **Sync child labels from Netatmo names** is hidden until at least one child device exists. Previously it could be toggled on with no children present and would silently do nothing.

- Station discovery now runs automatically right after authorization, alongside the connection test, so your Netatmo devices are already listed when you return to the settings page.
- The **Child Devices** section is hidden until at least one device is selected, so **Create/update selected supported devices** can no longer be clicked in a state where it does nothing.
- The Discovery section now shows which devices are selected. Selecting nothing produces a highlighted warning that nothing will be created; selecting devices produces a confirmation listing them by name.
- Moved **Refresh station discovery** below the device picker, since discovery now runs on its own and the picker is what most people need first.
- **Child Devices** now reports live state — how many of your selected devices actually exist in Hubitat right now — instead of only replaying the result of the last sync. The previous behavior could claim devices had been created after they were deleted, or immediately on opening the section before any sync had run in that session. The last-run result is still shown, now clearly labelled as history.
- Added a prominent reminder that settings are not saved until **Done** is clicked. Hubitat places that button at the bottom right, past every other section, and an integration abandoned before clicking it is never actually added even though child devices may already exist. The reminder appears at the top and bottom of the page until the app has been installed once, then shrinks to a single quiet line.
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
