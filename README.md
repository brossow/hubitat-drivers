# Hubitat Drivers

Device drivers for [Hubitat Elevation](https://hubitat.com) by [Brent Rossow](https://github.com/brossow).

## Drivers

| Driver | Description |
|--------|-------------|
| [Aeotec Heavy Duty Smart Switch](aeotec/) | Z-Wave switch with power metering (ZW078) |
| [Bambu Lab Printer](bambu-lab/) *(unreleased)* | Bambu Lab printer status, AMS filament and dashboard tiles over the printer's local MQTT broker. Not in HPM; builds on jonnyborbs's driver — see its README |
| [BirdWeather PUC](birdweather/) | Live bird detection data from a BirdWeather PUC station |
| [Netatmo Weather Station](netatmo-weather-station/) | Netatmo weather stations: base station, indoor and outdoor modules, rain and wind gauges |
| [ReBLLED LED Controller](reblled/) | Status and control for ReBLLED, alternative firmware for the BLLED LED controller in Bambu Lab printers |
| [Rheem EcoNet](rheem-econet/) | Rheem EcoNet thermostats and water heaters |
| [Yale Assure Lock 2](yale-assure-lock-2/) *(offered upstream)* | Z-Wave Yale Assure Lock 2 with DoorSense and Lock Code Manager. Fixes to Sleuth255's driver, mainly for Hubitat's Z-Wave JS stack, meant to go back upstream. Not in HPM |
| [Xiaomi/Aqara Temperature & Humidity](xiaomi-aqara/) | Zigbee T&H sensors (WSDCGQ01LM, WSDCGQ11LM, Aqara T1, Keen Home) |

## Installation

Each released driver has its own README with an import URL for manual installation via **Drivers Code → New Driver → Import**, as well as a Hubitat Package Manager (HPM) listing. Drivers marked unreleased, in development or offered upstream aren't in HPM.

## License

Everything in this repo is licensed under the [Apache License 2.0](LICENSE), **except** the [Xiaomi/Aqara Temperature & Humidity](xiaomi-aqara/th-sensor/) driver. That driver is a fork of Markus Liljergren's GPL-licensed original, so it stays under the [GNU GPL v3.0 or later](xiaomi-aqara/th-sensor/LICENSE). Each driver folder has its own `LICENSE` file.

## Releases

Tags follow the format `{driver}/v{version}` — for example, `birdweather/v1.2.0`. Each tag triggers a GitHub Release with the notes from that driver's `packageManifest.json`.

## Checks

`tools/check-syntax.sh` parses every driver with Groovy 2.4, the version the hub runs, and GitHub Actions runs it on every push that touches a `.groovy` file. It needs only Java 8 or later. It catches files that wouldn't save on the hub, not runtime bugs. Bambu Lab Printer, BirdWeather PUC, Netatmo Weather Station, ReBLLED LED Controller, Rheem EcoNet, the Xiaomi/Aqara T&H driver and Yale Assure Lock 2 also have off-hub tests (`tests/run.sh` in their folders) that check behaviour.

<sub>If these drivers are useful to you, [donations](https://www.paypal.me/brossow) are welcome but never expected.</sub>
