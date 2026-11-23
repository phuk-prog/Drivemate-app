# DriveMate 1.7.0-rc2 — navigation camera, maps and turn clarity

**Implementation branch:** `dev/map-zoom-turns-rc2-20261123` from the integrated all-11 candidate. `main` and stable release 1.6.1 are not modified. A private **signed release-candidate APK** is required by the project owner, but this does **not** authorise merging to main or publishing a GitHub Release.

## Improvements delivered together

### Automatic zoom and camera
- The existing user preference **Zoom · junction detail** now changes the actual navigation camera, primarily in the final 650m before turns at <=22m/s (about 49 mph). At faster speeds, preserve the more conservative route look-ahead and zoom.
- For closely spaced turns, the camera horizon includes the **second** route instruction so zooming toward the first does not obscure it.
- Approaching a verified complex turn, the 3D view becomes progressively more top-down. Normal Auto 3D / flat direction-up / north-up remain available.
- The camera direction and vehicle arrow now sample **only the confirmed approach road** before the next turn; they should not pre-rotate into the exit.
- Existing GPS-stale guards, recenter, camera smoothing and rate limits remain in place.

### Junction and roundabout visibility
- The app draws a more legible two-stroke outline following the **actual TomTom route geometry** through nearby roundabouts and forks, not a made-up aerial diagram.
- The compact turn card displays `signpost` or `street` text **only when supplied by TomTom** and the GPS/route match is confident.
- The junction path is cleared when it is no longer relevant or GPS becomes too uncertain.
- Do **not** claim lane-level guidance or safe road access when source data does not provide it.

### Higher-detail maps at navigation zoom
- The pinned UK OpenStreetMap/PMTiles archive **really stops at source zoom 14**. The app retains that local offline geometry and does not claim that zoom 19 contains newly measured OSM roads.
- Motorways, slip roads and minor streets are now more legible via source-attribute-based MapLibre styling, stronger building edges and street-name halos at close zoom, with matching high-contrast night styles.
- When a valid TomTom Map Display key is configured, an **optional online TomTom raster layer** fades in at camera zoom 16.25–17.2 and provides genuinely higher-resolution road tiles through zoom 20. It draws below the calculated navigation route and junction highlights, with traffic overlay above.
- The setting **Close-up road detail (online)** can disable these extra tile requests. It is enabled by default but always gated on a nonempty TomTom key; absent/failed tiles leave the existing local vector map underneath. API permissions, requests and attribution remain subject to the user's TomTom account and terms.
- The independent TomTom raster map renderer remains the last-resort fallback if the local vector roads are unavailable.

Sources: [MapLibre Style Specification](https://maplibre.org/maplibre-style-spec/) and [TomTom Map Display Raster Tiles](https://docs.tomtom.com/map-display-api/documentation/tomtom-maps/v1/raster/map-tile).

## Automated checks and signed candidate

`.github/workflows/map-zoom-turns-debug.yml` validates actual UK vector street tiles, JSON style parity, the online tile-source/fallback contract, blank-map regression fixtures, pinned world map archive, and all JVM tests (including new turn/camera tests). It builds **debug only** under test package `com.drivemate.android.zoom`.

The signed-candidate workflow runs independently on the branch, compiling/testing and producing only the **release variant**, signed by the established owner's certificate, verifying certificate SHA-256 and `com.drivemate.android`, versionCode **100701**, versionName **1.7.0-rc2**. A signed APK is *not* a public stable release.

## Physical Android acceptance tests — still required

- [ ] On an urban route, toggle **Zoom · junction detail** and observe meaningful closer zoom 100–300m before a turn, without losing the junction under guidance panels.
- [ ] On a motorway-speed scenario, the detail toggle does **not** dangerously shorten sight distance or jerk the camera.
- [ ] On consecutive nearby turns, both route actions remain visible; the road arrow stays on the true approach until the first turn has been taken.
- [ ] Verify roundabouts, bifurcations and motorway exits in the UK: clear exit text when supplied; no false signposts, lanes or exit geometry.
- [ ] When GPS is inaccurate/off-route, no overconfident junction highlight; after a fresh fix, guidance recovers.
- [ ] With a licensed TomTom key and internet, zoom to 17–19 and verify that actual sharper map detail loads, without hiding turn arrows or the route.
- [ ] Disconnect internet or use a key without Map Display entitlement: the local z14 vector map remains visible. Toggle online close-up detail off to compare.
- [ ] Switch day/night styles at high zoom, with traffic enabled: no blank roads, illegible labels or flickering overlays.
- [ ] On low-end Android hardware, monitor frame cadence, app memory and background battery/network usage during close-up mapping.
- [ ] Confirm routing search, preview, turn speech, hazards, speed limits, rerouting, arrival and parked-car actions still work.

Do not rely on this candidate as the sole navigation source during road testing. Always follow physical road signs.

## Installation and version protection

**Signed release candidate `com.drivemate.android` updates an existing DriveMate 1.6.1 installation** when the signer matches. It does not install beside stable DriveMate. To preserve 1.6.1 while testing, install the isolated debug variant `com.drivemate.android.zoom` first.
