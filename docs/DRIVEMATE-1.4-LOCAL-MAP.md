# DriveMate 1.4 — local-first vector map and nearby caching

## What changed

The native DriveMate GPS app (`com.drivemate.android`) now has an optional
**DriveMate Map** renderer alongside the old TomTom raster renderer:

- A GPU-rendered MapLibre Native OpenGL map.
- A **45 MB immutable world/UK overview** PMTiles archive bundled in the APK,
  verified using the public DriveMate Map release's SHA-256 before every build.
  This gives a basic geographic basemap even before downloading street tiles.
- **DriveMate Map UK Shortbread MVT street tiles** read from the public
  GitHub Release `tiles-20261122T0943Z`. The full archive is about 1.87 GB,
  but the app uses HTTPS **Range** reads to request only the tile bytes it
  needs and never tries to download the full archive onto the phone.
- A private, bounded on-device cache: up to **180 MB** of detailed street
  tiles. Cached tiles are reused between journeys and available offline
  where they have already been downloaded.
- **Approximately 20–30 km of surrounding map** prefetch, including streets
  at z14, automatically on **unmetered Wi-Fi only**. Cellular data fetches
  only map tiles needed to show the current view; it never starts the bulk
  prefetch task.
- A local HTTP vector tile endpoint bound to **127.0.0.1 only**. The app
  expressly allows cleartext traffic on its own loopback server, not to
  outside map providers.
- **TomTom car routing, traffic-aware route selection, destination search,
  TTS guidance and GPS** are unchanged. An optional transparent live TomTom
  traffic overlay can be shown atop the vector map.
- Settings → **Map** switches between DriveMate Map and the retained
  TomTom raster view. When detailed tiles cannot initially be loaded, the
  app automatically falls back to the existing TomTom raster view when
  a key is available.

## Source and provenance

Source repositories:

- Android application: https://github.com/phuk-prog/Drivemate-app
- Source map tiles: https://github.com/phuk-prog/Drivemate-map

Vector map: © OpenStreetMap contributors, ODbL 1.0.
World overview also includes Natural Earth/Protomaps data.
DriveMate Map's published UK archive is produced from a pinned,
date-stamped OpenStreetMap/Geofabrik source.

The bundled world archive is checked against a pinned full-file SHA-256.
The remote UK PMTiles archive uses a versioned release and validates
PMTiles headers/directories and exact HTTP Content-Range replies.
**The app does not verify the SHA-256 of the entire 1.87 GB remote
archive**, because it deliberately downloads only small ranges.
Do not claim otherwise.

## Limitations to test

- The currently published UK street map has native data to **zoom 14**.
  MapLibre can smoothly overzoom those vector roads at 15–19, but it does
  **not** add extra real road geometry. True native z16–18 packs remain
  a future map-data-pipeline improvement.
- Online traffic, TomTom search and new route calculation **still need
  internet and a valid TomTom key**. Offline cached roads are for display,
  not independent offline routing.
- Missing street tiles outside previously cached/prefetched coverage
  require internet. Poor reception, a GitHub Release failing to support
  range requests, or a temporary HTTP error may cause TomTom raster
  fallback or gaps in new UK detail.
- Labels may need an internet connection to retrieve fonts/glyphs on the
  first use. Road geometry itself can display from already cached tiles.
- The 180 MB limit applies to extracted MVT street tiles, not the app's
  bundled world archive or small cached archive index metadata.
- UK tile requests reveal the **approximate viewed map area** to GitHub's
  public Release host, like ordinary map browsing. No API key is sent to
  the DriveMate Map server; TomTom receives its own API requests.
- The renderer switch and map caching need **physical Android device
  performance profiling and road accuracy testing** before being relied
  upon while driving. The older road-navigation flow is intentionally
  preserved as a fallback.

## Build, sign and install

The Android CI workflow downloads and verifies the pinned world archive
using `python3 tools/fetch_pinned_map.py`, then runs unit tests and compiles
debug and unsigned release APKs. Release APKs must be signed with the
existing private DriveMate v1.2 owner certificate to update in place.
Version: `1.4.0`, Android version code `100400`, package:
`com.drivemate.android`.

To test: use the app on a stationary phone. Check that the map renders
roads with internet; switch to TomTom map in Settings; turn Wi-Fi on and
monitor cache stats while the phone has a good GPS fix; revisit the same
area offline and verify previously cached geometry loads. Also compare
frame responsiveness and memory consumption before using for driving.
