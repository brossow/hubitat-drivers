# Hubitat Drivers

Device drivers for [Hubitat Elevation](https://hubitat.com) by [Brent Rossow](https://github.com/brossow).

## Drivers

| Driver | Description |
|--------|-------------|
| [Aeotec Heavy Duty Smart Switch](aeotec/) | Z-Wave switch with power metering (ZW078) |
| [BirdWeather PUC](birdweather/) | Live bird detection data from a BirdWeather PUC station |
| [Rheem EcoNet](rheem-econet/) | Rheem EcoNet thermostats and water heaters |
| [Xiaomi/Aqara Temperature & Humidity](xiaomi-aqara/) | Zigbee T&H sensors (WSDCGQ01LM, WSDCGQ11LM, Aqara T1, Keen Home) |

## Installation

Each driver has its own README with an import URL for manual installation via **Drivers Code → New Driver → Import**, as well as a Hubitat Package Manager (HPM) listing.

## Releases

Tags follow the format `{driver}/v{version}` — for example, `birdweather/v1.2.0`. Each tag triggers a GitHub Release with the notes from that driver's `packageManifest.json`.
