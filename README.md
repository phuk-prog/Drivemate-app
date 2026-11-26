# DriveMate — UK Navigation (Android)

Driver-awareness review candidate 2.1.1 (200016), based on integration version 2.1.0. Not signed or road-approved. Start new work from consolidated main. Install only certificate-verified signed APKs from the [main signing workflow](https://github.com/phuk-prog/Drivemate-app/actions/workflows/signed-release.yml). The download has a one-hour cleanup target; no ZIP password or permanent release asset. Experimental own-map/offline routing is preview-only. Physical road validation is outstanding.

See [repository workflow](docs/REPOSITORY-WORKFLOW.md) and [map repository](https://github.com/phuk-prog/Drivemate-map).

## Historical notes
Archived in [docs/HISTORY.md](docs/HISTORY.md) (v1.1–v1.6.1). Not needed for current work.

## Features

- A TomTom raster road map with an optional live traffic-flow layer.
- Search for real places, postcodes and addresses; choose from matching results. Save favourites and revisit recent destinations.
- Traffic-aware **car** routing from the phone's live GPS position.
- On-screen route line, position and heading marker, next road instruction, distance remaining, estimated arrival time and current GPS speed in mph.
- Spoken turn-by-turn instructions using Android's **on-device TextToSpeech** service, with speech at up to approximately 1 km, 250 m and 65 m before a manoeuvre.
- Rerouting if a sufficiently accurate GPS fix stays more than 70 metres away from the current route for over 9 seconds; optional route refresh every 5 minutes while driving.
- Preview your route with time/distance/traffic information before explicitly starting. Toggle UK/metric units, live traffic, speech and GPS follow. End navigation at any time, repeat spoken guidance or manually refresh the route.
- API key entered on the device (stored in app-private preferences, not in source control).
- GitHub Actions build and test internal debug variants; only the verified owner-signed release APK is downloadable.

## Install the latest APK

1. Sign in to GitHub and open the [DriveMate verified signed release workflow](https://github.com/phuk-prog/Drivemate-app/actions/workflows/signed-release.yml).
2. Open its latest successful main run and download the `DriveMate-VERSION-signed-apk-1h` artifact promptly (target deletion 60 minutes after artifact creation).
3. Extract the normal GitHub artifact ZIP; install the signed APK. No password is required. Debug and unsigned APKs are never distributed.
4. Review the separately retained release evidence and verify the APK checksum; confirm Android offers an update if the existing install uses the same certificate.
5. Configure DriveMate and review the route while safely parked. Physical-road testing is still required.

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
- Debug APKs are internal CI/test intermediates and are not distributed. Production release signing, accessibility review, key-management hardening, telemetry/privacy review, and compliance with TomTom licensing still need work.
- API keys entered into a mobile client are extractable by someone with sufficient device access; scope and rate-limit keys accordingly. Keys are not uploaded to GitHub.

## Ownership and license

**Proprietary, source-visible software. All rights reserved for original DriveMate materials.** See [licence](LICENSE.md), [evaluation terms (draft)](docs/EULA.md), [branding](docs/BRANDING.md), [contributions](CONTRIBUTING.md) and [temporary signed distribution](docs/PRIVATE-DISTRIBUTION.md). Public viewing and GitHub forking rights and third-party licences are unaffected.

This repository is the DriveMate Android app code. TomTom data and APIs remain subject to TomTom's terms; osmdroid is an open-source Android map renderer subject to its own license. This repo is independent of the archived offline-routing research in `phuk-prog/Drivemate--app` and data in `phuk-prog/Drivemate-map`.

## Verification

`GeoMathTest` covers route geometry calculations, and `NavigationUnitsTest` covers miles/yards/kilometres, speed and durations. GitHub Actions runs the unit tests and builds the APK. Successful compilation and tests **do not** prove physical-road safety or successful API calls with a particular key.
