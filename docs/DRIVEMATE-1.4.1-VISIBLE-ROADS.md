# DriveMate 1.4.1 — blank-map and fallback correction

## Trigger

A real phone screenshot showed only the DriveMate background and GPS marker
after 30 seconds. The first v1.4 vector-map integration incorrectly marked the
map successful after fetching a tile, without checking whether any UK roads
were painted by MapLibre. This could suppress its 15-second TomTom fallback.

## Fixes

- **Do not equate an HTTP success with a working map.**
  `VectorDriveMap` now queries MapLibre's actual rendered `uk-roads`
  layer across the on-screen viewport every 1.8 seconds.
- A new pure-Java `RoadDisplayHealth` policy requires actual street-line
  features, for UK street zooms (9+), and reports a roadless display after
  12 seconds. It does **not** count the GPS marker, the blue route, background
  fills, buildings or label text as working road geometry.
- Keep **the existing TomTom online raster map visible** while the vector
  renderer loads behind it. The app switches to DriveMate Map only once the
  street geometry is rendered. If the vector roads cease rendering, the app
  falls back to TomTom and clearly reports the problem.
- Initialise the candidate map camera at the actual GPS location, even while
  TomTom is visible, so MapLibre can request nearby UK tiles. Keep route
  preview and live camera behaviour in sync during startup.
- Settings indicates when automatic fallback is active and offers a single-tap
  retry of DriveMate Map. Manual TomTom selection remains available.
- Log actual MapLibre style-loading errors to Android Logcat for diagnostics.
- Retain the existing MapLibre, TomTom routing, voice, package ID and private
  signing certificate. No map data, personal positions or credentials are
  committed.

## Added checks

- `RoadDisplayHealthTest` rejects tile-success-with-zero-rendered-roads,
  missing style, off-UK/low-zoom false alarms and stalls after camera changes.
- `tools/verify_drive_map_roads.py` fetches **real** z14 road tiles near
  Stockport, using bounded HTTPS PMTiles Range requests, inspects the MVT
  feature layers, and checks compatibility with the Android JSON map style.
  This is an integration check on map DATA, not a GPU screenshot.
- `tools/check_drive_map_pixels.py`, adapted from the previously successful
  offline map prototype, fails on a blank screenshot with only a GPS dot and
  passes a diagnostic screenshot containing painted roads. The CI regression
  currently uses **synthetic fixtures**, not an instrumented Android emulator.
  The checker can also be used with a real device screenshot:
  `python3 tools/check_drive_map_pixels.py screenshot.png`.

## Still required for real-road approval

Compilation, JUnit checks and map-source tests do **not** prove MapLibre will
successfully render all roads on a particular GPU or phone. Physical-device
screenshots after installation, a timed network-loss test, and driving
performance checks conducted while safely stationary or with a passenger
are still required. TomTom routing still requires an API key and connection;
cached PMTiles are for display only. This release cannot verify legality of
routes, GPS map matching or junction instructions.

Version: `1.4.1`, Android versionCode `100401`,
package ID `com.drivemate.android`, same private owner's signing
certificate as DriveMate 1.4.0.
