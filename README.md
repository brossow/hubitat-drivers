# Hubitat Drivers

Device drivers for [Hubitat Elevation](https://hubitat.com) by [Brent Rossow](https://github.com/brossow).

## Drivers

| Driver | Description |
|--------|-------------|
| [Aeotec Heavy Duty Smart Switch](aeotec/) | Z-Wave switch with power metering (ZW078) |
| [BirdWeather PUC](birdweather/) | Live bird detection data from a BirdWeather PUC station |
| [Netatmo Weather Station Connect](netatmo-weather-station/) | Netatmo Weather Station integration with base station and module child devices |
| [Rheem EcoNet](rheem-econet/) | Rheem EcoNet thermostats and water heaters |
| [Xiaomi/Aqara Temperature & Humidity](xiaomi-aqara/) | Zigbee T&H sensors (WSDCGQ01LM, WSDCGQ11LM, Aqara T1, Keen Home) |

## Related Projects

| Project | Description |
|---------|-------------|
| [bambu-hubitat](https://github.com/brossow/bambu-hubitat) | Bambu Lab 3D printer → Hubitat integration. Kept as a separate repo since it ships a Python/Docker MQTT bridge alongside the Hubitat app and driver, rather than being a plain driver install. |

## Installation

Each driver has its own README with an import URL for manual installation via **Drivers Code → New Driver → Import**, as well as a Hubitat Package Manager (HPM) listing.

## Releases

Tags follow the format `{driver}/v{version}` — for example, `birdweather/v1.2.0`. Each tag triggers a GitHub Release with the notes from that driver's `packageManifest.json`.
