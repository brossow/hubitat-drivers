# Changelog

## [1.6.0] - 2026-10-10

Fixed:

- **Busy stations missed first sightings.** Each poll read only the newest 10 detections (or the history depth, if larger). A busy station logs more than that in five minutes, and anything older was skipped: no `birdDetected`, and no `newSpeciesDetected` or `newLifetimeSpeciesDetected` if that was the only sighting. A first-ever species skipped this way never fired at all, because the hourly all-time list refresh then added it quietly. Night mode and hub downtime caused the same gap every morning. Each poll now reads a page of 25 and, if the last detection it already saw isn't on it, walks back through the history until it is (up to about 425 detections, with a warning beyond that).
- **Private stations.** The API token was sent in an `Authorization` header, which BirdWeather reads only for administrator tokens; station tokens belong in the URL, in place of the station ID. The token now goes there.
- **Minimum Confidence %.** When none of the recent detections met the threshold, the poll failed with *Cannot get property 'species' on null object*. Raising the threshold could also replay up to 10 detections already seen as new `birdDetected` events.
- **Retries could switch off for good.** Saving preferences within a minute of a failed poll cancelled the pending retry but left it marked as pending, so no transient error was retried again until **Reset History**.
- **The detections handler and the all-time species refresh could save over each other.** Both rewrite the tracked lifetime list and ran at the same moment. They now run one after the other, detections first, so a first-ever species is announced before the refresh adds it to the list.
- A Station ID with stray spaces, or pasted as the whole station link, now works.
- Night mode no longer stops polling with an error if the hub has no sunrise and sunset times.

Changed:

- After a long gap, `birdDetected` fires for the 10 newest detections only, so a backlog doesn't set off a string of announcements. The daily and lifetime species events still consider every detection.

Added:

- `healthStatus`: `online` after a successful poll, `offline` once a poll and its retry a minute later have both failed.
- Off-hub tests (`tests/run.sh`), run by GitHub Actions.

## [1.5.0] - 2026-08-12

Fixed:

- False `newLifetimeSpeciesDetected` alerts for species already seen. The BirdWeather `/species` endpoint silently caps results at 100, most-detected first, so the driver only knew a station's 100 commonest birds. The all-time list is now read page by page, merged rather than overwritten, never cleared by an empty or failed response, and refreshed hourly instead of every poll. Lifetime alerts stay quiet until the list has loaded once.

## [1.4.1] - 2026-04-25

Fixed:

- Every new detection since the last poll is processed, not just the latest, so a second bird between polls no longer drops its events.

## [1.4.0] - 2026-04-25

Added:

- `newLifetimeSpeciesDetected`, fired the first time a species is ever detected at the station.

## [1.3.1] - 2026-04-24

Added:

- An option to turn off the `birdDetected` event.

## [1.3.0] - 2026-04-24

Changed:

- Lifetime totals and the species list come from the BirdWeather API (`period=all`) now that BirdWeather has fixed that endpoint. The `setLifetimeDetections` command is gone.

## [1.2.2] - 2026-04-23

Fixed:

- Lifetime stats recover from their attributes after a state wipe.

## [1.2.1] - 2026-04-23

Fixed:

- Self-healing lifetime state, and a daily watchdog that re-registers the polling schedule if it was dropped.
