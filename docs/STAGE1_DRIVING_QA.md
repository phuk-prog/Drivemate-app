# DriveMate Stage 1 — branch QA and safety checklist

This document belongs to the **development branch only**. The released 1.6.1
`main` branch remains the stable baseline. Do **not** merge or create a release
APK without explicit approval.

## Implemented UI behaviour

- **1 — Map-first display:** branded/version header hidden during guidance,
  next turn remains at top, ETA/duration/distance moved to a compact bottom
  strip, with small labelled-accessible repeat/refresh/voice/end controls.
- **5 — Two-turn guidance:** show the following manoeuvre only if it is within
  450 m of the first and the first is within 900 m; existing monotonic TomTom
  instruction progression promotes the following turn automatically.
- **7 — Junction display:** confirmed TomTom roundabout exit numbers, larger
  manoeuvre icons, correct keep-left/right fork arrows for `BIFURCATION`, and
  actual route geometry highlighted near verified roundabouts and forks.
- **8 — Themes:** automatic system day/night preference with manual Day/Night;
  separate high-contrast MapLibre night style; documented TomTom night raster
  tiles used by the fallback renderer.
- **9 — Camera:** Auto 3D, heading-up 2D, north-up 2D; existing
  route-aware GPS interpolation and junction zoom retained. Manual panning
  shows a distinct paused-follow/recenter state, and poor GPS is identified.
- **10 — Voice:** Full, Normal, Alerts Only, Silent, plus in-drive mode cycling,
  volume settings and independent saved flags for verified speed/hazard alerts.
  The Alerts Only mode can still announce factual GPS/network failures.

## Device QA to complete before merge

1. Start a route in portrait and landscape. Check the header disappears during
   guidance, the manoeuvre remains legible, the ETA strip and all four action
   buttons fit, and none covers a road junction.
2. Preview a route, cancel it, start and finish a route. Confirm home/preview
   controls return and no overlapping panels or missing navigation actions.
3. Verify a real pair of close manoeuvres, then a pair far apart. The second
   instruction must appear only when relevant, with no stale 'Then' after the
   first completes or rerouting changes the instruction list.
4. Follow a mapped roundabout and a verified fork. Check the exit number against
   signs and the cyan line against the actual calculated route. **No lane
   recommendation is inferred from the exit number.**
5. Force Day and Night using both map renderers. Check street names,
   road geometry, traffic overlay, vehicle, route, roundabout highlight and ETA
   remain readable. Reopen with Automatic and change the phone's appearance.
6. Switch Auto 3D, 2D heading-up and 2D north-up while navigating. Verify
   orientation and vehicle arrow direction; pan the map and tap recenter.
7. Test with stopped GPS, weak GPS and a brief signal loss. Ensure the
   camera does not keep moving on an old fix and uncertainty is apparent.
8. Switch Full, Normal, Alerts Only and Silent. Check routine turns are
   suppressed in the latter two, factual GPS loss announcements are allowed
   in Alerts Only, and manual Repeat works except in Silent.
9. Confirm tests-only GitHub Actions validation passes and **no production
   signing, release APK packaging or merge** has occurred.

## Known Stage 1 limitations

- Verified roadside hazard and posted-speed data are Stage 2 dependencies. The
  new category preferences are ready, but they do **not** generate alerts without
  real verified incident/speed-limit sources.
- The highlighted cyan junction segment follows TomTom's verified route
  geometry; it is **not** a photorealistic lane-level junction view.
- MapLibre road rendering, GPS behaviour, audio focus and screen layout still
  require real-device/drive simulation QA before this code is approved for
  production driving.
- The branch CI intentionally runs `testDebugUnitTest` and static map checks
  **without** compiling a release package or uploading APK artifacts.
