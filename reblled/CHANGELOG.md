# Changelog

## [1.0.0] - 2026-10-19

First version.

- Live status over the controller's web socket (`/ws`, no login needed): what the strip shows and why (`strip`, `stripColor`, `status`, `reason`), printer status, `attention`, `door`, `printerLight`, `printerConnection`, `printerModel`, `firmware`, `rssi`, `healthStatus`.
- `lastPrintFinished`, sent when a print the driver saw running finishes.
- Switch (on = Follow or Always on, off = Off), SwitchLevel (brightness) and **Set Mode** through `POST /api/settings`, with the WebUI login as HTTP basic auth when one is entered.
- Events only on change; reconnects with a back-off from 5 s to 5 minutes; polls `/api/status` while the socket is down if it can.
- Off-hub tests (`tests/run.sh`), run by GitHub Actions.
