# DriveMate Stage 2 — route-data integration and testing gates

**Development branch:** `dev/stage2-traffic-intelligence-20261123`, based on the separate Stage 1 development branch. **Never merge to `main`, privately sign, or build a release APK without explicit user approval.**

## Implemented features (approved improvements 2, 3, 4 and 6)

- **2: Verified lane guidance.** The existing TomTom Routing API v1 is requested with `sectionType=lanes`. Only explicitly recommended `follow` directions in a route-indexed lane section are shown. Unrecognised arrows, sections without recommended lanes and unreliable GPS are hidden. Lane arrows disappear as soon as their applicable route segment has ended. Documented left/right U-turn directions are handled explicitly.
- **3: Route traffic timeline.** `sectionType=traffic` identifies TomTom traffic incidents *on the calculated route*, including roadworks, congestion and closures where categorised. A compact notice shows the next event, approximate delay and a tappable list of up to five upcoming route sections. Speech is throttled and suppressed near imminent turns. **This is NOT a comprehensive crowdsourced Waze-style hazard database**: accident/vehicle/pothole warnings are not invented and require further verified incident-data integration.
- **4: Mapped speed limits.** `sectionType=speedLimit` supplies speed-limit sections; the speed badge shows the value alongside GPS speed in UK mph by default. Never infer from road class. The limit displays as unknown for missing, conflicting, stale or unreliable GPS/route alignment. Optional warning tolerances are 3/5/7/10/15 mph. **Physical road signs take precedence over all mapping data.**
- **6: Conservative rerouting.** An updated TomTom fastest route is compared with the remaining current-route estimate and geometry. Substantial savings (at least three minutes and 15%) are required to propose a switch; otherwise keep the existing route. Ask-first is default; Automatic is optional. A verified off-route departure can still be recovered automatically. Suggested routes expire rapidly and are rejected once they no longer start close to the driver's recent GPS. A network response is also rejected if the driver travelled more than 110 metres from the route request's origin. Automatic mode never switches solely because the previous ETA is stale.
- **Fallback:** If enhanced Routing API metadata is rejected with HTTP 400/403, retry basic TomTom routing and show unknown/absent supplemental road facts rather than interrupt turn navigation.

## Data sources

- TomTom Routing API v1 [Calculate Route](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route) — `lanes`, `speedLimit`, `traffic` sections, indexed by route polyline point.
- TomTom [Guidance instructions](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/guidance-instructions) — lane directions and explicit follow recommendation.
- TomTom [Traffic API Incident Details](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-incidents/incident-details) is a possible follow-on data source for **more** hazard types. **Not queried by this branch:** incident geometry must first be reliably matched to the active direction/road and commercial entitlements and quota verified.

No API key is hardcoded or logged. The user provides a TomTom API key at runtime.

## Automated validation

`.github/workflows/stage2-debug-validation.yml` compiles the Android app, executes all JVM tests (including TomTom JSON response fixtures and source-integrity rules), checks map regression fixtures, and builds **only** `:app:assembleDebug` with package `com.drivemate.android.stage2`, labelled **DriveMate Stage 2 Test**.

The Stage 2 debug build can coexist with the stable `com.drivemate.android` app and the Stage 1 `com.drivemate.android.stage1` preview. Each test install has its own settings and will need separate TomTom API key configuration.

## Mandatory device/simulator QA before approval or release

- [ ] Confirm basic TomTom search, route preview, start, guidance and route refresh still work with the user's API key.
- [ ] Check lane sections where supplied: left-to-right orientation, recommended lanes, arrows, disappearance without recommendations, and no false arrows on narrow roads.
- [ ] Check traffic notice and timeline against an actual on-route reported incident; ensure roads without incident sections display no invented warnings.
- [ ] Verify GPS speed and mapped speed-limit pairing, unknown-limit behaviour, route-age expiry and conflicting-limit guard. Check any displayed limit against road signs; route map data may be outdated or conditional.
- [ ] Test false-positive GPS snaps and parallel roads: limit and lane guidance must hide for uncertain/off-route fixes.
- [ ] Test alert thresholds and voice modes, particularly no interruption of a critical imminent turn.
- [ ] Verify frequent traffic refresh while on-route: same route updates quietly; different route with small or unproven saving is retained.
- [ ] Test Ask-first route suggestions, Keep/Switch, moving car expiration, auto mode, an actual verified missed turn and route refresh failure.
- [ ] Check low-screen resolution, both map engines, dark/light theme and internet loss. Driver must never rely on this experimental app as the only navigation system.

**Current status:** CI can verify compilation and synthetic policies, but it cannot prove live TomTom account entitlements, geographical coverage, correct road direction, speed-limit accuracy or in-car UX. Only device testing can address these.
