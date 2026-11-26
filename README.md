## DriveMate v1.6.1 — version label matches signed APK

DriveMate's header and About dialog now use the installed Android package's
version name instead of hard-coding `v1.5.1`. Previously the signed 1.6.0
APK was correctly built from `versionCode 100600` but could still display
`v1.5.1` within the app. This fix advances to `versionCode 100601` so
owners can update in place with the existing stable signing certificate.
Navigation and routing behaviour are unchanged from v1.6.0.

## DriveMate v1.6.0 — forward-looking junction camera

The MapLibre driving view now pitches into a forward-looking perspective,
zooms toward the upcoming turn, and compensates for on-screen navigation
panels so the approaching road and junction remain visible. The camera eases
from a stronger tilted overview to a clearer, closer view at a turn. Directional
vehicle arrows now show travel relative to the rotating map in both GPU and
raster views. The osmdroid/TomTom fallback remains **2D**; map tilt is only
available when the DriveMate Map vector renderer is functioning.

[Camera changes, limits and testing guidance](docs/DRIVEMATE-1.6-JUNCTION-CAMERA.md).
This is not yet verified on a moving Android device and must not be relied upon
until appropriately tested.

# DriveMate v1.5.0 — smoother follow-route camera

DriveMate now samples an indexed TomTom route on Android's display clock for
continuous camera/vehicle motion between GPS updates (GPU up to 30fps, raster
fallback about 9fps). Zoom has a gradual rate limit, hysteresis, and a brief
post-junction hold; road bearing rotates through the shortest angle. Visual-only
prediction is bounded by GPS quality, a short timeout and the next unconfirmed
turn. Manual map gestures pause follow until the user taps recenter.

[Implementation notes and validation limitations](docs/DRIVEMATE-1.5-SMOOTH-CAMERA.md).
The UK vector tile source still supplies native geometry only through z14;
building a higher-detail regional dataset is a separate data-source task.

# DriveMate — UK Navigation (Android)

## DriveMate 1.4 — DriveMate Map + local street cache

DriveMate now offers the own-brand **DriveMate Map** as a GPU-rendered
MapLibre map alongside the previous TomTom raster map. A checksum-verified
world overview is included inside the APK. Individual UK street tiles are
extracted from the dated DriveMate Map PMTiles release on demand, saved
privately in a **180 MB** capped cache, and nearby roads are prefetched on
unmetered Wi-Fi. The app does **not** download the 1.87 GB UK archive in full.
Settings lets you switch back to TomTom's map if vector detail cannot load.

TomTom still supplies traffic-aware driving routes, location search, and
spoken navigation; a downloaded street map is **not** offline routing.
Current UK native detail is zoom 14, with vector overzoom on junctions;
higher-resolution road data and real-device performance testing remain
necessary.

**[Read the v1.4 local-map technical notes](docs/DRIVEMATE-1.4-LOCAL-MAP.md)**,
including map data attribution, download limitations and verification.

**v1.3.1: all twelve navigation improvements, with a compact 52dp turn pictogram.**
[Full v1.3 release notes](docs/DRIVEMATE-1.3-RELEASE-NOTES.md)
cover the 12 approved improvements: turn symbols, next junction, smoother
GPS vehicle marker, closer camera, roundabout exits, improved ETA and
rerouting, configurable voice, UK search, connectivity warnings, dashboard
and saved preferences. Version code: `100301`.

**v1.2: one DriveMate app ID and a map-first redesigned home screen.**

The 22 November 2026 reference screenshots informed the navy/cyan design: a large on-map DriveMate header with a turquoise road mark, a prominent destination search bar with an Android speech-search button, map/GPS/speed controls, and Home/Work/Saved Places/Recent Destinations cards. Navigating switches to the existing route-preview and spoken-guidance panel.

**Important:** The package identity now matches original Drivemate (`com.drivemate.android`), but its former signing certificate is unknown. You may need to uninstall the older Drivemate install **once**, and also remove the separately installed `com.drivemate.app` DriveMate GPS package. From this release onward, only APKs signed with the owner's **same private signing key** can update in place; see [Updating and signing](docs/UPDATING-AND-SIGNING.md). Never put the keystore in this public repo.

## DriveMate v1.3.2 — map responsiveness fix

Fixes the reported **slow GPS map response** without changing TomTom routing or the
permanent application ID `com.drivemate.android`:

- Replaces repeated osmdroid `animateTo(...)` panning with direct
  `setCenter(...)` updates while following a live GPS fix; animations previously
  took about a second and were restarted when the camera moved.
- Updates the moving driving camera at normal GPS cadence (typically about one
  second), rather than waiting at least 2.4 seconds.
- Ignores very small stationary GPS movements; limits map rotation and zoom
  changes that would reload raster tiles unnecessarily.
- Uses a bounded app-private tile cache with four parallel tile downloader
  threads, so previously visited map areas can render faster without unbounded
  storage. TomTom's traffic layer is still optional from Settings.
- Uses a reduced set of vertices **for displaying the blue route only**. The
  full original TomTom route remains used for manoeuvres, progress, distance
  calculations and rerouting.
- Adds `MapFollowPolicyTest` and `DisplayPolylineTest`.

The fast camera reduces avoidable *application-level* delay. Map tiles may
still load slowly on poor internet connections or if TomTom tiles are slow.
This app is not certified for real-road navigation, and a successful build
does not replace testing on the intended phone. The APK must be signed using
the same private DriveMate v1.2 certificate as previous installable releases.
Version: `1.3.2`; version code: `100302`.

## v1.2.2 — Junction-focused driving view

Changes based on actual DriveMate screenshots from an Android phone:

- **Driving camera now follows the upcoming manoeuvre**: during an active trip it looks ahead along TomTom's route geometry and dynamically adjusts zoom according to the remaining distance to the next turn and the vehicle speed.
- **Driver and next junction visible together**: map camera accounts for header and guidance-card occlusion and positions the car lower in the usable map area. The map follows the road heading for an ahead-up view, with bounded camera rotation.
- Route line reduced from 8dp to 5dp with rounded joins/caps, so it obscures less of roundabouts and side roads. TomTom's original route geometry is left intact.
- Search keyboard is dismissed on Find; when the keyboard is showing, large dashboard and map controls are hidden rather than overlapping the search field.
- Speeds below approximately 2 mph are treated as zero to reduce stopped-GPS jitter. ETA uses 24-hour UK clock formatting.
- Camera geometry and route interpolation/bearing tests added.

**Important limitations:** the road camera uses TomTom REST geometry and GPS rather than a certified map-matching navigation SDK. At roundabouts or on parallel roads, GPS geometry may still snap to the wrong segment. Distinguishing a true routing problem from a display/position problem requires an anonymised TomTom route response and controlled device testing. This release is not validated for real-road use. Do not manipulate the screen while driving.

Version code 100202, app ID `com.drivemate.android`, for installation over the privately signed v1.2.x versions when signed with the same owner's certificate.

## v1.2 additions

- Install display name **DriveMate** and branded adaptive launcher icon.
- Navy map-first interface with a home destination panel modelled on the screenshot.
- Working Home/Work shortcuts (tap to route, hold or choose Settings to change).
- Saved places and recent trips in the home panel, with private on-device persistence.
- Voice destination search where Android provides a recognizer.
- Settings for TomTom key, traffic overlay, voice, distance units and Home/Work.
- Route preview, traffic-aware directions and on-screen voice/refresh controls retained.
- Higher versionCode `100200` and a deliberately **unsigned release** build for securely owner-controlled signing.
- Updated tests, Android CI and stable signing instructions.

**The debug APK artifact is not a stable update signer.** Use the signed release APK for updates. No real-world device or road validation has been completed.

An independently owned, native Android driving-navigation app.

## What's new in v1.1 (one-pass upgrade)

- **Route preview before you start:** calculate a TomTom traffic-aware driving route and show its complete map overview, total distance, expected driving time and any reported traffic delay. Tap **Start navigation** explicitly to begin guidance, or cancel the route.
- **UK units by default:** miles, yards and mph; tap **mi/yd** to switch to kilometres/metres and km/h. Your choice is saved on the device.
- **Saved and recent places:** tap the **★** button beside search to reuse a destination. Save favourite places from route preview; remove favourites in the saved places dialog. Favourites and recents stay on the device.
- **Repeat voice**: replay the upcoming turn instruction while navigating. **Refresh route** manually requests updated TomTom traffic-aware routing.
- **GPS-loss warning:** a navigation banner and spoken warning if a fresh GPS fix hasn't arrived within 15 seconds, with notice when GPS resumes. A fresh fix is required to start.
- **More unit tests** for the distance, spoken units, driving speed and time display. Version code is now 2.

**Installation note for previous v1 users:** GitHub's ephemeral debug signing key may differ between workflow runs. Android may refuse an in-place update. If the new APK cannot install, uninstall the old DriveMate GPS app first, then install v1.1 and re-enter your TomTom key; uninstalling clears local app data. For seamless production upgrades, provision a stable private release-signing key rather than relying on GitHub Actions debug signing.

## Features

- A TomTom raster road map with an optional live traffic-flow layer.
- Search for real places, postcodes and addresses; choose from matching results. Save favourites and revisit recent destinations.
- Traffic-aware **car** routing from the phone's live GPS position.
- On-screen route line, position and heading marker, next road instruction, distance remaining, estimated arrival time and current GPS speed in mph.
- Spoken turn-by-turn instructions using Android's **on-device TextToSpeech** service, with speech at up to approximately 1 km, 250 m and 65 m before a manoeuvre.
- Rerouting if a sufficiently accurate GPS fix stays more than 70 metres away from the current route for over 9 seconds; optional route refresh every 5 minutes while driving.
- Preview your route with time/distance/traffic information before explicitly starting. Toggle UK/metric units, live traffic, speech and GPS follow. End navigation at any time, repeat spoken guidance or manually refresh the route.
- API key entered on the device (stored in app-private preferences, not in source control).
- GitHub Actions build of an installable, Android-debug-signed APK.

## Install the latest APK

1. Open **Actions → Build installable Android APK** in this GitHub repository.
2. Open the latest **successful** run from this branch (or its pull request).
3. Download **DriveMate-Android-debug-APK** from *Artifacts* and unzip the file.
4. Install `app-debug.apk` on an Android 8.0+ phone. Android may ask you to allow installation from that source.
5. Open **DriveMate GPS** and grant **Precise location** permission.
6. Paste your TomTom Maps API key when prompted. Confirm it allows **Map Display**, **Search**, **Routing** and **Traffic** requests. The app does not include a key.
7. Wait for GPS, search for an address/postcode, select a result, review your route and tap **Start navigation** while safely parked. Keep the app open and the screen on for driving guidance. The phone needs a data connection.

For development: use Android Studio with JDK 17, Android SDK platform 35 and Gradle 8.9, or run `gradle :app:assembleDebug`. The Gradle build uses Android Gradle Plugin 8.7.3.

## API endpoints

The app uses TomTom REST APIs, not the separate licensed Android Navigation SDK:
- `GET /map/1/tile/basic/main/{z}/{x}/{y}.png`
- `GET /traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png`
- `GET /search/2/search/{query}.json`
- `GET /routing/1/calculateRoute/{start}:{end}/json` with `traffic=true` and `instructionsType=text`.

Every call is over HTTPS and requires a valid TomTom key with the relevant API permissions. If map tiles do not show or search fails with HTTP 401/403, check TomTom API product entitlements, key validity, quota and billing. Traffic availability is subject to TomTom coverage.

## Important current limitations

This is a **first-build candidate**, not a tested or certified navigation device. It must undergo real Android phone, GPS and actual-road validation before being relied upon for driving. Always obey actual road signs, restrictions, closures and conditions. Never interact with the screen while driving. Use a safe mount and pull over to enter destinations.

- **Foreground only**: GPS updates stop while the activity is backgrounded, and the app keeps the screen awake while navigating. Background navigation, lock-screen guidance, Android Auto and offline maps are **not included**.
- Spoken cues are based on TomTom REST guidance and GPS geometry, without the full map-matched positioning, lane guidance or dead reckoning of TomTom Navigation SDK.
- Route ETA is estimated from route duration and remaining distance, then updated during rerouting. It is not a continuously predictive traffic ETA between refreshes.
- This build handles one destination at a time; no waypoint trips, alternate-route picker, automatic speed-limit display, cameras, vehicle dimensions or motorway lane assist.
- No guarantees of continuous service if the TomTom key's permissions/quotas/terms are insufficient.
- A debug APK is suitable for personal installation/testing, not for Play Store distribution. Production release signing, accessibility review, key-management hardening, telemetry/privacy review, and compliance with TomTom licensing still need work.
- API keys entered into a mobile client are extractable by someone with sufficient device access; scope and rate-limit keys accordingly. Keys are not uploaded to GitHub.

## Ownership and license

**Proprietary, source-visible software. All rights reserved for original DriveMate materials.** See [licence](LICENSE.md), [evaluation terms (draft)](docs/EULA.md), [branding](docs/BRANDING.md), [contributions](CONTRIBUTING.md) and [private distribution](docs/PRIVATE-DISTRIBUTION.md). Public viewing and GitHub forking rights and third-party licences are unaffected.

This repository is the DriveMate Android app code. TomTom data and APIs remain subject to TomTom's terms; osmdroid is an open-source Android map renderer subject to its own license. This repo is independent of the archived offline-routing research in `phuk-prog/Drivemate--app` and data in `phuk-prog/Drivemate-map`.

## Verification

`GeoMathTest` covers route geometry calculations, and `NavigationUnitsTest` covers miles/yards/kilometres, speed and durations. GitHub Actions runs the unit tests and builds the APK. Successful compilation and tests **do not** prove physical-road safety or successful API calls with a particular key.
