# DriveMate Stage 1 — development review and device-test checklist

**Branch:** `dev/stage1-driving-display-20261123` (based on the 1.6.1 `main` commit).
**Scope:** approved improvements 1, 5, 7, 8, 9, and 10 only.
**Release restrictions:** do not merge to `main`, change signing, or generate a release APK without separate user approval.

## Implemented on this branch

- **1 — Map-first view:** hide the large brand header while following a route; show the next manoeuvre at the top and an ETA/time/distance strip above compact bottom actions.
- **5 — Two-turn guidance:** show a second manoeuvre when it is near the first and both are approaching. The existing route-progress logic promotes it automatically as the first turn is completed.
- **7 — Roundabouts and junctions:** enlarge the existing reliable roundabout exit number; trace the *actual calculated TomTom route* across a near roundabout or verified route fork. Never infer an unprovided lane or exit.
- **8 — Day/night map:** user-selectable Automatic (Android system appearance), Day, and Night. Preserve the driving session on UI-mode changes. The fallback TomTom raster map uses its documented night endpoint. Re-check rendered UK vector roads before displaying a reloaded style.
- **9 — Camera and GPS:** Auto 3D, heading-up 2D, and north-up 2D use the existing route-smoothed camera. The user can pan to pause following and tap the highlighted recenter control to resume. Do not extrapolate indefinitely when GPS becomes uncertain or stale.
- **10 — Voice:** Full, Normal, Alerts Only, Silent; one-tap mode cycling; voice volume and per-category settings. Explicitly tapping Repeat speaks a turn in Alerts Only but not in Silent. GPS/network warnings follow the selected warning policy.

## Automated checks

The branch-only `.github/workflows/stage1-tests-only.yml` runs:
1. Original DriveMate UK road layer validator and a day/night layer/source parity check.
2. Original blank-map fixture checks.
3. Checksum-verified world-map asset retrieval.
4. `gradle --no-daemon :app:testDebugUnitTest` to compile the Android app and run all JVM unit tests.

**No release APK, signing job, PR merge, or release upload** is part of this workflow.

## Physical Android test checklist — still required

- [ ] Start a preview, then navigate: home/header controls disappear while driving, route and turn are visible, bottom ETA/time/distance and four compact controls remain reachable on small screens.
- [ ] Drive/simulate two turns less than 450 m apart: second turn appears only near the first, then becomes the primary instruction after the first.
- [ ] Navigate verified roundabouts and forks: correct exit and real-route highlight, with no spurious lane advice. Test no-exit-number data.
- [ ] Select Day, Night and Automatic during a journey; switch the phone appearance while guidance runs. The route should remain active and street tiles should become visible again without a blank permanent map.
- [ ] Compare local vector rendering with TomTom raster fallback; confirm correct road positioning and route arrows in both map themes.
- [ ] Switch between Auto 3D / 2D direction up / 2D north up. Verify the vehicle points correctly and the view does not spin or lose the upcoming junction.
- [ ] Pan/zoom manually: follow mode pauses, recenter is obvious, and tapping it resumes smoothly without jumping to a stale GPS location.
- [ ] Interrupt GPS: show an accurate warning and freeze misleading movement; restore GPS and confirm recovery.
- [ ] Test Full / Normal / Alerts Only / Silent speech, manual Repeat, mute transitions and configurable voice volume.
- [ ] Confirm preview cancellation, route refresh, reroute failure, arrival and End Navigation still work.

## Important boundaries

- **Verified live road hazards and posted speed limits are Stage 2**: Stage 1 makes room for their separate voice preferences but does not invent incidents or speed alerts.
- Junction highlighting uses only *route geometry*, not a true aerial junction diagram or verified per-lane turn instructions.
- Automatic night switching follows the **phone's UI appearance**, not a standalone sunset/sunrise calculator.
- GPS/camera safety cannot be fully certified with unit tests. This is still a **development build awaiting driving tests**.
- The existing repository `pull_request` workflow assembles an **unsigned release APK**. Do not open a PR triggering that workflow until separately approved or the workflow is changed so that no release APK is built.

Test only while safely parked or with a passenger/test harness; the driver must follow road signage.
