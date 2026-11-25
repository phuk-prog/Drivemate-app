# DriveMate 2.0.1 intelligent guidance candidate

RC13 base, stable Android ID, existing signing certificate. Candidate versionCode 200013.
- Adaptive navy guidance card: distance, action, road, signs, lane cues and following action.
- Provider-bounded roundabout exit in visual and spoken wording. Path-only curved geometry trace when conservative checks pass; no fabricated branches or lane counts.
- Speed-adaptive voice tiers retained. Spoken lane guidance only from trusted route-indexed recommendations; close following turn and UK road-reference pronunciation.
- Existing map matching, stale GPS suspension, routing, offline proof and junction camera retained.
- Signing workflows in this candidate branch share a version floor and remote-branch check. Other historical branches must be retired or independently updated before allowing further signing. The version check is not an immutable distribution ledger.

## Acceptance before signing or real-road use
Run full Gradle unit and Android instrumentation suite. Replay urban left/right, two close turns, multi-lane motorway exit, roundabout with missing or inconsistent metadata, GPS loss, route refresh and reroute. Check source wording and map path match; inspect small/landscape screens and accessibility scaling. Verify voice timing and lane correctness on a safe recorded trace before real driving. For oversized or irregular roundabouts, no full topology is claimed. No route-source junction number or sign is fabricated. No change to provider selection or experimental offline routing policy.

The existing protected RC13 branch signing workflow will sign only after this candidate is merged into it and its existing acceptance gates pass. Do not distribute the old 1.7.7 builds as updates.

Additional provider metadata: TomTom Routing v1 roadNumbers, exitNumber and drivingSide are kept separately from roundaboutExitNumber. Unknown fields use the existing generic manoeuvre; the schematic arrow uses explicit maneuver direction and traffic handedness, not the exit ordinal. Curved route traces require an agreeing direction and stable final leg. Source: https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/guidance-instructions
Stationary-only upcoming manoeuvres auto-dismiss when position becomes stale, movement is detected or the activity pauses. Detailed voice mode includes one long-stretch reassurance per instruction. Compact displays reduce sizes and suppress secondary text, while camera framing uses the measured top-card height.
