# Netatmo Weather Station Connect for Hubitat

## Overview

Netatmo Weather Station Connect is a Hubitat integration for Netatmo Weather Station devices. It supports Netatmo weather station modules and gauges, not the full Netatmo product line.

The integration uses Netatmo's cloud API with OAuth authentication. The parent Hubitat app handles authentication, API requests, discovery, polling, normalization, diagnostics, and child-device updates. Child drivers consume normalized data from the parent app and do not call the Netatmo API directly.

## Supported Devices

- Base Station
- Outdoor Module
- Additional Indoor Module
- Rain Gauge
- Wind Gauge

## Features

- OAuth-based Netatmo authentication
- Station and module discovery
- User selection of discovered devices
- Child devices for supported weather station modules
- Scheduled polling
- Manual child refresh
- Field diagnostics for raw and normalized data availability
- App-level unit preferences
- Measurement timestamp support
- Daily minimum/maximum temperature values where Netatmo provides them
- Additional Netatmo metadata such as firmware, last message time, battery voltage, and non-sensitive station place details
- Standard Hubitat sound pressure level support for base station noise readings

## Requirements

- Hubitat Elevation hub
- Netatmo Weather Station account and supported devices
- Netatmo developer application with client ID and client secret
- Internet access from the Hubitat hub

## Installation

### Hubitat Package Manager

After this package metadata is published, install **Netatmo Weather Station Connect** from Hubitat Package Manager using this repository's package list.

### Manual Installation

Manual installation:

1. In Hubitat, open **Drivers code**.
2. Add and save each driver:
   - `NetatmoWeatherBaseStation.groovy`
   - `NetatmoWeatherOutdoorModule.groovy`
   - `NetatmoWeatherIndoorModule.groovy`
   - `NetatmoWeatherRainGauge.groovy`
   - `NetatmoWeatherWindGauge.groovy`
3. In Hubitat, open **Apps code**.
4. Add and save `NetatmoWeatherStationConnect.groovy`.
5. **Confirm OAuth is enabled for the app — this is required.** While still in **Apps code** with `NetatmoWeatherStationConnect.groovy` open, click **OAuth** and make sure it is enabled. Without it, authorization cannot start. If you later see *"Hubitat app OAuth is not enabled yet"* on the settings page, come back and do this.
6. In the left-hand menu, click **Integrations** (not **Apps** — this integration installs itself under **Integrations**). Click **Add user integration**, then choose **Netatmo Weather Station Connect** from the list.

This last step is required. Adding the code under **Apps code** only makes the integration *available to install*; it does not create a usable instance. You must add the user integration before you can enter any credentials.

## Setup

Setup has two stages: create an application on Netatmo's developer website to get a Client ID and Client Secret, then paste those into the integration on your hub. Everything after that happens in Hubitat.

Read this terminology note first — most setup problems come from confusing these three things:

| Term | What it actually is | Where it lives |
|---|---|---|
| **Netatmo developer application** | A registration you create so Hubitat can talk to your Netatmo account. It is not software you install or open. Its only purpose is to generate a Client ID and Client Secret. | https://dev.netatmo.com/ |
| **The Hubitat integration** | Netatmo Weather Station Connect running on your hub. It has its own settings page with text fields. | Your Hubitat hub's web interface |
| **The Netatmo mobile app** | The normal Netatmo phone app you use to read your weather station. | Your phone |

**You never enter the Client ID or Client Secret into the Netatmo mobile app.** They are *generated* on the Netatmo developer website and *entered* on your Hubitat hub. The Netatmo mobile app plays no part in setup.

> The Netatmo portal steps below were accurate when this was written. Netatmo can change their screens, wording, and options at any time. If what you see no longer matches, the general shape of the process should still apply — and please open an issue or post in the Hubitat community thread so this can be updated.

### Step 1: Create the Netatmo developer application

This entire step happens in a web browser on Netatmo's website. You are not touching Hubitat yet.

1. Go to https://dev.netatmo.com/
2. If you are not already signed in, click **Log in** in the upper-right corner and sign in with the Netatmo account that owns your Weather Station.
3. Click your username in the upper-right corner and choose **My apps** (direct link: https://dev.netatmo.com/apps/).
4. Click the orange **Create** button.
5. Fill in the required fields. **Name** and **Description** are yours to choose — something like `Hubitat Weather Station` and `Hubitat integration` is fine. **Your full name** and **email address** are also required, and are easy to miss.
6. Tick the box to accept the terms and conditions, then click **Save**.
7. The page reloads and now shows an **App Technical Parameters** section containing your **client id** and **client secret**. Keep this tab open — you will copy both in the next step.

**Leave the Redirect URI field empty.** This integration does not need one. If you are reusing an application you created earlier and that field already has a URL in it, clear it and save — otherwise authorization will fail with `redirect_uri_mismatch`.

The Netatmo token generator on that page is not needed. Hubitat handles the whole authorization exchange itself.

### Step 2: Enter the credentials in Hubitat

1. Open your Hubitat hub's web interface in a browser.
2. In the left-hand menu, click **Integrations**. This is a different menu item from **Apps** — this integration appears under **Integrations**, and you will not find it under **Apps**.
3. In the list, click **Netatmo Weather Station Connect** — the instance you added at the end of [Installation](#installation).

   Clicking it opens its settings page. **This page on your own hub is where the credentials go** — not a page on Netatmo's website, and not the Netatmo phone app. If you do not see **Netatmo Weather Station Connect** in the list, you have not yet completed step 6 of [Installation](#installation); adding the code under **Apps code** is not enough by itself.
4. The first section is **Netatmo API Credentials**, with two fields: **Client ID** and **Client Secret**.
5. Copy the **client id** from the Netatmo tab into **Client ID**, and the **client secret** into **Client Secret**. The secret is masked as you type, which is normal.
6. **After typing or pasting the secret, click somewhere else on the page or press Enter.** Hubitat does not register the value until the field loses focus. The page then refreshes and an **Authorize Netatmo** link appears in the **Authorization** section.

If **Authorization** still asks you to enter your credentials, one of the two fields has not registered — click into it and back out again.

If it says *"Hubitat app OAuth is not enabled yet,"* you skipped step 5 of [Installation](#installation). Go to **Apps code**, open `NetatmoWeatherStationConnect`, click **OAuth**, enable it, then come back.

### Step 3: Authorize, then create your devices

1. Click **Authorize Netatmo**. A Netatmo page opens asking you to allow access — click **YES, I ACCEPT**.
2. You should see *"Netatmo authorization succeeded."* Close that tab and return to the integration page in Hubitat, then refresh it.

   If you see *"Netatmo authorization failed"* instead, the page now tells you what to do about it. `redirect_uri_mismatch` is the most common one — see [Troubleshooting](#netatmo-returned-redirect_uri_mismatch).
3. The **Diagnostics** section should now report **Netatmo connection OK** with a count of stations and modules — the integration tests the connection for you right after authorizing. You can click **Test Netatmo connection** at any time to re-check.
4. Click **Refresh station discovery**.
5. Under **Select Netatmo devices**, click **Click to set** and tick the devices you want Hubitat to manage.
6. Click **Create/update selected supported devices**. Your child devices are created now.
7. Choose your **Poll Interval**.
8. Choose your unit preferences under **Units**.
9. Click **Done** to save everything and start scheduled polling.

**Sync child labels from Netatmo names** appears once at least one child device exists. Turning it on makes the next **Create/update selected supported devices** rename your Hubitat devices to match their current Netatmo names — useful if you rename things in the Netatmo app and want Hubitat to follow.

Until you authorize, the settings page shows only credentials, authorization, and logging. The remaining sections appear once authorization succeeds.

Polling updates existing selected child devices. It does not create child devices automatically; use the manual create/update action for child creation.

Installing the package does not create devices by itself. Child devices are created only after authorization, discovery, selecting modules, and running **Create/update selected supported devices**.

Use **Run poll now** to verify the data path immediately after changing the poll interval or saving the app. Use **Reschedule polling** if the app reports that scheduled polling appears stale.

## Unit Preferences

Unit preferences are configured in the parent app and apply to all child devices.

- Temperature: Hubitat location default, Celsius, or Fahrenheit
- Pressure: hPa/mbar or inHg
- Rain: mm or inches
- Wind speed: km/h, mph, m/s, or knots
- Wind direction display: numeric angle, text direction, or both

Netatmo source values are normalized and converted in the parent app before values are sent to child devices. Drivers receive display-ready values plus unit labels.

## Timestamps

The integration exposes four different timestamps:

- `lastSeen`: when Netatmo last heard from the module, from Netatmo `last_seen`
- `measurementTime`: timestamp of the latest Netatmo dashboard reading, from `dashboard_data.time_utc`
- `lastMessage`: timestamp of the latest Netatmo message from the module, from Netatmo `last_message`
- `lastUpdated`: when the Hubitat child device was updated by this integration

Stale or unreachable modules may have `lastSeen` or `lastMessage` metadata while missing current dashboard fields and `measurementTime`.
For base stations, Netatmo may not provide useful `last_seen` or `last_message` data; the base station driver reports those attributes as `Not provided` when the fields are absent.

## Exposed Fields

The parent app normalizes Netatmo API data before sending values to child devices. Drivers skip missing values, so stale or unreachable modules keep their last known Hubitat values instead of being cleared by an incomplete API response.

Base station devices expose temperature, humidity, CO2, pressure, absolute pressure, noise, sound pressure level, Wi-Fi status, daily minimum/maximum temperature values, measurement timestamps, firmware, data types, and non-sensitive station place details where Netatmo provides them.

Outdoor and additional indoor modules expose their measurement values, RF status, battery percentage, battery voltage, firmware, data types, daily minimum/maximum temperature values, and timestamps where Netatmo provides them.

Rain and wind gauges expose their measurement values, RF status, battery percentage, battery voltage, firmware, data types, and timestamps where Netatmo provides them. Rain and wind values depend on what Netatmo returns for active and reachable modules.

The base station uses Hubitat's standard `SoundPressureLevel` capability for Netatmo's numeric `Noise` value. It does not create a threshold-based sound detected/not detected event.

## Diagnostics

The **Inspect available fields** tool helps determine what Netatmo returned for each discovered device and how the app normalized it.

Diagnostics show:

- Device name, type, class, and DNI
- Reachability and timestamp information
- Selected raw device and metadata keys
- Raw `dashboard_data` keys present in Netatmo's response
- Normalized dashboard values after unit conversion
- Normalized metadata fields
- Expected fields for the device class
- Missing or null expected fields

This is useful when troubleshooting stale or unreachable modules, especially rain and wind gauges where Netatmo may omit dashboard fields if the device has not reported recently.

Field diagnostics remain visible until cleared with **Clear field diagnostics**.

## Known Limitations

- Supports Netatmo Weather Station devices only.
- Does not support Netatmo cameras, thermostats, smoke alarms, doorbells, or other non-weather product lines.
- Depends on Netatmo API availability and account access.
- Some module types may need broader real-hardware validation.
- Polling does not create devices automatically.
- Child devices are not deleted automatically.
- Unit conversion assumes Netatmo API source units are Celsius, hPa/mbar, mm, and km/h.

## Troubleshooting

### Netatmo returned `redirect_uri_mismatch`

Your Netatmo application has a **Redirect URI** saved that does not match this hub. This integration does not need a Redirect URI at all.

1. Sign in at https://dev.netatmo.com/apps/ and open the application whose Client ID you entered in Hubitat.
2. Clear the **Redirect URI** field in **App Technical Parameters**, then click **Save**.
3. Return to Hubitat and click **Authorize Netatmo** again.

This most often happens when you reuse one Netatmo application across two hubs, since the saved URI points at whichever hub authorized first. **If that application is in use on another Hubitat hub, create a separate Netatmo application for this hub instead** of clearing the field.

Clearing stored Netatmo tokens does not fix this — the mismatch is on Netatmo's side, not in Hubitat's saved tokens.

### Netatmo returned `invalid_client`

Netatmo did not recognize the Client ID or Client Secret. Recopy both from **App Technical Parameters** at https://dev.netatmo.com/apps/ and paste them into Hubitat again, checking for stray leading or trailing spaces.

### Other authorization problems

- Confirm OAuth is enabled for the app in **Apps code** — the settings page says *"Hubitat app OAuth is not enabled yet"* when it is not.
- Refresh the integration page after completing Netatmo authorization; the status does not update on its own.
- Check Hubitat **Logs** while clicking **Authorize Netatmo** for the specific error.
- Enable **debug logging** at the bottom of the settings page for more detail. It stays available even when you are not authorized.

### No Devices Found

- Click **Test Netatmo connection** first.
- Confirm the Netatmo account has Weather Station devices.
- Confirm the Netatmo developer app is authorized for station read access.
- Check Hubitat logs for API or token errors.

### Selected Device Does Not Create a Child

- Confirm the device is selected in the discovery list.
- Click **Create/update selected supported devices**.
- Confirm the matching driver is installed and saved before running sync.
- Check Hubitat logs for child creation warnings.

### Devices Do Not Appear After Installation

- Open **Integrations** and create/open **Netatmo Weather Station Connect**.
- Enter the Netatmo client ID and client secret, then click **Done**.
- Reopen the app and authorize Netatmo.
- Click **Refresh station discovery**.
- Select the modules you want.
- Click **Create/update selected supported devices**.
- Child devices appear only after the selected-device sync succeeds.

### Scheduled Polling Stops Updating Devices

- Open **Netatmo Weather Station Connect** from **Integrations** and check the Polling section.
- Click **Run poll now** to confirm the API and child update path still works.
- Click **Reschedule polling** to refresh Hubitat's scheduled job.
- Click **Done** after changing the poll interval or after package updates.
- Check Hubitat logs if the app reports a polling error.

### Missing Rain or Wind Fields

- Click **Inspect available fields**.
- Check whether Netatmo returned the raw dashboard fields.
- If raw fields are missing, the driver cannot expose current values for that reading.
- Stale or unreachable modules may return metadata but no current rain or wind dashboard values.

### Stale or Unreachable Modules

- Check `reachable`, `lastSeen`, `lastMessage`, and `measurementTime`.
- `lastSeen` may show the last module communication time even when current dashboard values are absent.
- `measurementTime` is present only when Netatmo returns a dashboard reading timestamp.

### Unit Changes Not Reflected

- Unit preference changes apply when new normalized data is sent to child devices.
- Refresh the child device, run manual sync, click **Run poll now**, or wait for the next scheduled poll.

### Token or Authentication Failures

- Reauthorize Netatmo from the parent app.
- Confirm the Netatmo developer app still exists and credentials are unchanged.
- Check Hubitat logs for refresh-token or API errors.

## Privacy and Security Notes

- The Netatmo client secret is stored in Hubitat app settings.
- Netatmo OAuth tokens are stored in Hubitat app state.
- Logs should not expose the client secret, access token, or refresh token.
- The integration communicates with Netatmo's cloud API.

## License and Credits

Licensed under the Apache License, Version 2.0.

SPDX-License-Identifier: Apache-2.0

Copyright 2026 Brent Rossow

Netatmo is a trademark of Legrand Netatmo. This project is an independent Hubitat integration and is not affiliated with or endorsed by Netatmo.
