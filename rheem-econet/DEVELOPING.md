# Developing the Rheem EcoNet drivers

## Layout

| Path | What it is |
|---|---|
| `EcoNetThermostat.groovy`, `EcoNetWaterHeater.groovy` | The drivers users install. Each is one self-contained file. |
| `shared/EcoNetCommon.groovy` | Code both drivers use: login and retry backoff, polling, unit selection, command publishing, helpers. |
| `tools/build.py` | Copies the shared file into both drivers and checks the package is consistent. |
| `tests/` | Off-hub tests: a stand-in for the Hubitat sandbox, a fake EcoNet API, and test suites. |
| `tests/surface/` | Snapshots of each driver's capabilities, attributes, commands and preferences. |

## Shared code

The bottom of each driver, between the `BEGIN SHARED CODE` and `END SHARED CODE` markers, is a copy of `shared/EcoNetCommon.groovy`. Never edit it there. Edit the shared file, then:

```sh
python3 tools/build.py
```

Why not a Hubitat library (`#include`)? Hubitat Package Manager can only deliver a library inside a bundle zip exported from a hub, and anyone installing by Import URL would have to install the library first, then each driver, every time. Copying at build time gets the same single source without making users do anything differently.

Each driver supplies what the shared code needs from it: the `DRIVER_VERSION`, `LOG_TAG`, `UNIT_NOUN`, `UNIT_TITLE` and `ROSTER_KEY` fields, plus `initialize()`, `unitsIn(equip)` and `applyUnit(equip)`. The list is at the top of the shared file.

## Tests

```sh
tests/run.sh               # everything
tests/run.sh Selection     # only tests whose name contains "Selection"
```

All you need is Java 8 or later. The first run downloads Groovy 2.4.21, the version line the hub runs, into `~/.cache` and checks its checksum.

The suites:

- **SelectionTests** — which unit a device controls. Each test is a rule that exists because a real bug once made a device control the wrong unit. These run against both drivers.
- **LoginTests** — login, session expiry, retry backoff, polling.
- **ThermostatTests**, **WaterHeaterTests** — attributes and commands.
- **LogPrivacyTests** — no log line may contain a full serial, device id, account id, email address, token or password.
- **SurfaceTests** — the declared surface matches `tests/surface/`. 1.0 is a promise about these names, so changing one should be a decision. When it is one, run `UPDATE_SURFACE=1 tests/run.sh Surface` and commit the new snapshot along with the change.

Fixtures are built in `tests/Fixtures.groovy`, with record shapes taken from the EcoNet API. Every identifier in them is an obvious placeholder. **Never put a real serial number, device id, account id or email into a fixture or test**, including values copied from your own hub's logs.

GitHub Actions runs `build.py --check` and the tests on every push that touches `rheem-econet/`.

## Testing on a hub

HPM and the Import URL both read from `main`, so anything merged there reaches users. Work on a branch, and on your hub import the branch's raw URL into the existing driver (**Drivers Code → driver → Import**):

```
https://raw.githubusercontent.com/brossow/hubitat-drivers/<branch>/rheem-econet/EcoNetThermostat.groovy
https://raw.githubusercontent.com/brossow/hubitat-drivers/<branch>/rheem-econet/EcoNetWaterHeater.groovy
```

Then open the device and click **Save Preferences** so it starts again on the new code.

## Releasing

1. Set the version in both drivers: the header `Version:` line and `DRIVER_VERSION`. A pre-release such as `0.4.0-beta.1` is fine on a branch.
2. For the release itself, use the final version (`0.4.0`) in both drivers and in `packageManifest.json`, with `dateReleased` and `releaseNotes`. The release notes must not contain a double quote, backtick or dollar sign, because the release workflow passes them through a shell `echo`. `build.py --check` enforces both rules.
3. Merge to `main`. HPM users get the update from this point.
4. Tag `rheem-econet/vX.Y.Z` and push the tag. That creates the GitHub release.
