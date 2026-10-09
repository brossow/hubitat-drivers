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

## License

Everything in this repo is licensed under the [Apache License 2.0](LICENSE), **except** the [Xiaomi/Aqara Temperature & Humidity](xiaomi-aqara/th-sensor/) driver. That driver is a fork of Markus Liljergren's GPL-licensed original, so it stays under the [GNU GPL v3.0 or later](xiaomi-aqara/th-sensor/LICENSE). Each driver folder has its own `LICENSE` file.

## Releases

Tags follow the format `{driver}/v{version}` — for example, `birdweather/v1.2.0`. Each tag triggers a GitHub Release with the notes from that driver's `packageManifest.json`.

## Checks

`tools/check-syntax.sh` parses every driver with Groovy 2.4, the version the hub runs, and GitHub Actions runs it on every push that touches a `.groovy` file. It needs only Java 8 or later. It catches files that wouldn't save on the hub, not runtime bugs. Rheem EcoNet and the Xiaomi/Aqara T&H driver also have off-hub tests (`tests/run.sh` in their folders) that check behaviour.
