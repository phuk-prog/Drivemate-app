# DriveMate — all eleven approved improvements: combined development QA

Branch: `dev/all-approved-stage3-arrival-20261123`, based on Stage 2, which is based on Stage 1, which is based on stable 1.6.1.

**Never merge this branch to main, privately sign, or produce a release APK without explicit separate approval.** The workflow here packages **only a separately installable debug APK**, ID `com.drivemate.android.all11`, app label **DriveMate All 11 Test**. It does not replace stable `com.drivemate.android` or the earlier stage previews. Test app has separate settings and requires its own TomTom key.

## All approved scope in one integrated branch

1. Compact map-first driving layout, bottom journey summary.
2. Explicitly recommended route lane arrows when reliable and supported by TomTom. Unknown directions hidden.
3. TomTom route-indexed traffic reports/timeline, no invented Waze-style community hazards.
4. Mapped speed limits beside GPS speed; verified-route matching, unknown otherwise, configurable tolerance.
5. Two-turn guidance when close together.
6. Conservative alternative route suggestions and missed-turn recovery.
7. Clearer roundabout exit and route-path highlighting; no inferred per-lane geometry.
8. Adaptive system Day/Night map style with manual override and map fallback.
9. Auto 3D, direction-up 2D and north-up 2D camera modes with recenter and GPS uncertainty safeguards.
10. Full, Normal, Alerts Only and Silent speech controls, with separate warning categories.
11. Arrival and parking:
    - Requires **three consecutive distinct, fresh and precise low-speed fixes** close to the destination and route endpoint before ending navigation.
    - Compact final-approach hint, explicitly stating when an entrance cannot be verified.
    - TomTom Search `entryPoints` only: map-listed main entrance marker appears when provider supplies a valid, local coordinate. **An entry point does not prove safe vehicle access, a driveway, or correct side of road.**
    - Parking shown **only from TomTom parking-category POIs**, on explicit request when stopped, within 1.4 km of the selected destination. User must confirm changing the destination. **Free spaces, cost and access are not verified.**
    - Optional *save where parked* uses only fresh, accurate stationary phone GPS and explicit confirmation, stored in app-private SharedPreferences; view or clear from home. No background tracking or synchronisation, no automatic parking inference and no walking navigation claim.

## Automated validation

`.github/workflows/all11-debug-validation.yml` runs map style parity and fixture checks, checksum-verifies the world overview asset, compiles all Android Java source, executes all JVM tests including offline Search API entrance/parking and multi-fix arrival tests, then assembles **only debug**. The workflow verifies the APK's separate package ID and label.

## Phone/simulator acceptance checklist before release

- [ ] Simulate a full UK route and ensure all Stage 1/2 features still work together after Stage 3.
- [ ] Confirm roundabout voice/maneuver progression remains smooth after dynamic speed/traffic notices.
- [ ] Switch themes and camera modes while a route and parked-car/entrance markers are visible; no blank road layers.
- [ ] Test missing/unlicensed Search API parking: normal navigation must continue without invented parking.
- [ ] Test search POI with verified entryPoint vs normal address with no entryPoint: latter must state unknown.
- [ ] Verify final approach at 700m and 90m, and ensure a drive-by on adjacent/parallel road does not falsely complete the trip.
- [ ] Confirm GPS jitter, old GPS, high speed, inaccuracy and bad route matching cannot trip arrival.
- [ ] Simulate three accurate stationary fixes and check the arrived state and optional save prompt.
- [ ] Test user chooses parking *only while stopped*, explicitly confirms replacing route, and does not get redirected without consent.
- [ ] Save car location from home while stopped; restart app; show pin; clear; confirm no pin or data remains.
- [ ] Check small-screen UI: 'Parking', voice and reroute actions must not block road or essential navigation.
- [ ] Confirm separate debug install, no upgrade to stable app, and no private release signing.

**Not certified**: TomTom Search and Routing key entitlements/coverage, real entrance vehicular access, live car-park capacity or pricing, UK posted sign accuracy, traffic direction matching on all roads, road-legal turn instructions or physical driving UX. Use a passenger, stationary phone, simulator or closed-course test for driving checks; the driver must follow road signs.
