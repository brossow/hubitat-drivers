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

1. In Hubitat, open **Drivers Code**.
2. Add and save each driver:
   - `NetatmoWeatherBaseStation.groovy`
   - `NetatmoWeatherOutdoorModule.groovy`
   - `NetatmoWeatherIndoorModule.groovy`
   - `NetatmoWeatherRainGauge.groovy`
   - `NetatmoWeatherWindGauge.groovy`
3. In Hubitat, open **Apps Code**.
4. Add and save `NetatmoWeatherStationConnect.groovy`.
5. Enable OAuth for the app in Hubitat Apps Code if Hubitat does not enable it automatically.
6. Open **Apps**, choose **Add User App**, and add **Netatmo Weather Station Connect**.

## Netatmo Developer App Setup

You need a Netatmo developer app so Hubitat can authenticate with Netatmo.

1. Go to the Netatmo developer portal: https://dev.netatmo.com/
2. Sign in with the Netatmo account that owns or has access to your Weather Station.
3. Open the developer app/application management area.
4. Create a new application, or open an existing application you want to use for Hubitat.
5. Give the application a recognizable name, such as `Hubitat Netatmo Weather Station`.
6. Save the application so Netatmo generates a client ID and client secret.
7. Copy the Netatmo client ID and client secret.
8. Enter those credentials in the Hubitat app and click **Done**.
9. Reopen the Hubitat app. It will display a Netatmo callback URL.
10. Copy that callback URL into the Netatmo developer app settings where redirect or callback URLs are configured.
11. Save the Netatmo developer app settings.
12. Return to Hubitat and use the authorization link to authorize Netatmo access.

Netatmo's developer UI may change over time, so use the field that controls allowed OAuth redirect/callback URLs. The URL shown by Hubitat must match the redirect URI sent during authorization.

The Netatmo token generator is not needed for this integration. Hubitat handles OAuth through the authorization link and callback URL.

## Setup In Hubitat

1. Enter the Netatmo client ID and client secret.
2. Click **Done** to save the credentials.
3. Reopen the parent app.
4. Use the authorization link to authorize with Netatmo.
5. Return to the Hubitat app page after authorization and refresh the page if needed.
6. Run **Test getstationsdata** to confirm Netatmo API access.
7. Run **Refresh station discovery**.
8. Select the devices you want Hubitat to manage.
9. Run **Create/update selected supported devices**.
10. Choose the poll interval.
11. Choose app-level unit preferences.
12. Click **Done** after setup changes so Hubitat saves the settings and schedules polling.

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

The integration exposes three different timestamps:

- `lastSeen`: when Netatmo last heard from the module, from Netatmo `last_seen`
- `measurementTime`: timestamp of the latest Netatmo dashboard reading, from `dashboard_data.time_utc`
- `lastUpdated`: when the Hubitat child device was updated by this integration

Stale or unreachable modules may have `lastSeen` metadata while missing current dashboard fields and `measurementTime`.

## Diagnostics

The **Inspect available fields** tool helps determine what Netatmo returned for each discovered device and how the app normalized it.

Diagnostics show:

- Device name, type, class, and DNI
- Reachability and timestamp information
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

### OAuth or Callback Problems

- Confirm OAuth is enabled for the Hubitat app in Apps Code.
- Confirm the callback URL shown in Hubitat is configured in the Netatmo developer app.
- Confirm the client ID and client secret are correct.
- Reauthorize after changing callback URL or credentials.
- Refresh the Hubitat app page after completing Netatmo authorization.

### No Devices Found

- Run **Test getstationsdata** first.
- Confirm the Netatmo account has Weather Station devices.
- Confirm the Netatmo developer app is authorized for station read access.
- Check Hubitat logs for API or token errors.

### Selected Device Does Not Create a Child

- Confirm the device is selected in the discovery list.
- Run **Create/update selected supported devices**.
- Confirm the matching driver is installed and saved before running sync.
- Check Hubitat logs for child creation warnings.

### Devices Do Not Appear After Installation

- Open **Apps** and create/open the **Netatmo Weather Station Connect** parent app.
- Enter the Netatmo client ID and client secret, then click **Done**.
- Reopen the app and authorize Netatmo.
- Run **Refresh station discovery**.
- Select the modules you want.
- Run **Create/update selected supported devices**.
- Child devices appear only after the selected-device sync succeeds.

### Scheduled Polling Stops Updating Devices

- Open the **Netatmo Weather Station Connect** parent app and check the Polling section.
- Click **Run poll now** to confirm the API and child update path still works.
- Click **Reschedule polling** to refresh Hubitat's scheduled job.
- Click **Done** after changing the poll interval or after package updates.
- Check Hubitat logs if the app reports a polling error.

### Missing Rain or Wind Fields

- Run **Inspect available fields**.
- Check whether Netatmo returned the raw dashboard fields.
- If raw fields are missing, the driver cannot expose current values for that reading.
- Stale or unreachable modules may return metadata but no current rain or wind dashboard values.

### Stale or Unreachable Modules

- Check `reachable`, `lastSeen`, and `measurementTime`.
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
