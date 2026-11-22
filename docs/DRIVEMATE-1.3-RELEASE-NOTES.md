# DriveMate 1.3.1 — Navigation polish batch

This is the requested 12-item upgrade applied as one Android release to the existing
`com.drivemate.android` application. Existing private release-signing certificate
must be reused for in-place updates. Version code: **100301**.

## Implemented changes

1. **Moderately sized manoeuvre arrows.** The guidance panel displays a compact 52dp
   roundabout/turn/straight/arrival icon using TomTom's structured `maneuver`,
   `junctionType` and turn-angle fields, with roundabout exit number where provided.
2. **Improved junction camera.** Junction zoom is speed-adaptive with bounded
   zoom steps and bounded rotations, retaining both car and next junction.
   Optional closer junction view is available in Settings.
3. **Following junction.** A secondary instruction names the manoeuvre after
   the next turn and how far it follows the first.
4. **Steadier vehicle marker.** Position is filtered for display only, without
   overwriting the raw GPS fix or falsely snapping onto a mapped road.
5. **Roundabouts.** Structured exit numbers appear with the roundabout indicator,
   and wording avoids repeating exit numbers that TomTom already speaks.
   The visually suspicious loop in earlier screenshots has **not** been removed
   without route-response evidence; that would risk unsafe road guidance.
6. **Journey calculations.** Remaining distance, trip time and 24-hour ETA
   update using route progress and bounded recent-speed observations, with the
   original TomTom traffic-aware journey estimate as the baseline.
7. **Rerouting.** Continuous, accurate GPS deviation is required for 12 seconds;
   reroute requests are rate-limited. A failed route request retains the
   previously displayed route and reports that it may be outdated.
8. **Voice options.** Detailed/Normal/Minimal spoken prompts, adjustable TTS
   guidance volume, and a repeat instruction control.
9. **Destination search.** UK full-postcode formatting, private local recent
   and favourite suggestions, clear search control, and keyboard dismissal.
10. **Connection / GPS warnings.** Distinguishable GPS accuracy, GPS loss,
    disconnected internet and successful reconnection messages without
    discarding the route line.
11. **Driving dashboard.** Prominent immediate turn, the following turn,
    distance remaining, travel time and estimated arrival in one panel.
12. **Persistent choices.** Speech mode, TTS volume, UK/metric units,
    map north-up or direction-up, closer junction zoom, and traffic display
    are saved in private on-device preferences.

## Validation and limitations

GitHub CI runs GeoMath, GuidanceLogic, VehicleFilter, NavigationCamera and
NavigationUnits JUnit tests before creating the Android debug and unsigned
release APKs. The final user APK needs signing with the established private
DriveMate owner signing certificate (not GitHub's transient debug key).

**Not yet certified or road-tested:** REST guidance does not offer full
map-matched road positioning. Roundabouts, junctions, GPS multipath,
congestion, tunnels and parallel roads must be validated on a physical
device while stationary or with a passenger observing. TomTom request
failures and bad GPS can still produce imperfect guidance; traffic data
coverage and quotas depend on the user's TomTom key. The app must remain
open on-screen and requires internet for new routing/search/map tiles.
Offline map/routing, background voice navigation, and Android Auto
are outside this release. Always obey actual road signs and restrictions.
