# DriveMate v1.5.0 — continuous route-aware driving camera

The camera no longer waits for each GPS callback to move the map.

## Implemented

- **Continuous visual movement:** a UI frame clock runs at approximately
  20 Hz for MapLibre native GPU maps, or 8 Hz for the legacy raster fallback.
  It samples a cached route arc-length index in O(log N), rather than rescanning
  route geometry or restarting a 650 ms MapLibre ease animation on each fix.
- **Short and bounded motion prediction:** when an accurate fix indicates the
  car is moving, its display-only icon and camera are projected up to 1.15 s
  along the current TomTom route, no farther than 35 m. There is **no
  indefinite dead reckoning** during GPS loss.
- **Jitter resistance:** inaccurate/off-route fixes do not force visual jumps.
  High-confidence fixes can align the display marker with the route; lower-
  confidence positions remain filtered and are not falsely snapped.
  Route instructions and off-route decisions still use the original GPS fix,
  not the predicted display marker.
- **Independent time-based zoom smoothing:** zoom changes progress at most
  about 0.75 levels per second. A 0.24-level dead band and ~1.7 s target hold
  prevent rapid zoom hunting. The camera remains close up for roughly 2.2 s
  after a turn before gradually zooming out to the next distant junction.
- **Road-following bearing:** a shortest-angle rotation filter prevents
  long-way spins at 359°/0°, and rate limits changes to 25–48° per second;
  stopped GPS jitter does not swing the map.
- **Ahead-of-car road framing:** TomTom route geometry and the distance to
  the next manoeuvre determine camera look-ahead. The top turn card and the
  lower controls are accounted for in usable screen height.
- **Map gestures:** touching/panning the driving map pauses follow. The
  existing recenter control resumes route tracking. No GPS changes are
  applied to a manually repositioned map until tracking is reenabled.
- **Fallback:** visual smoothing works on both the MapLibre GPU engine and
  the osmdroid/TomTom raster fallback, at different rendering cadences to
  limit CPU/tile-redraw use.

### Engineering and testing

`SmoothFollowCamera` and `RouteMotionIndex` are pure Java. They are
unit-tested for between-fix movement, capped prediction after a dropout,
GPS inaccuracy rejection, gradual zoom, hysteresis/turn exit hold,
heading wraparound and stopped-vehicle rotation.

**Do not interpret visual position interpolation as a new actual GPS
measurement.** The off-route detector, TomTom route and manoeuvre timing
remain derived from actual GPS. Estimated movement can still be
incorrect when a vehicle makes an unconfirmed turn, stops suddenly or
travels off the expected road.

**Map-data limitation:** the published UK Shortbread PMTiles package
still has native detail up to zoom 14. Higher-detail regional native
tiles require their own verified OpenStreetMap source rebuild; simply
overzooming a z14 tile does not create road geometry. Existing native
mapping covers the current area at z14 and remains unchanged until a
trusted regional high-detail pack is published.

### Real-world validation required

Android CI compilation and JUnit tests are not a benchmark against Waze
or TomTom. Evaluate on the intended phone while stationary/passenger-
observed. In particular verify 20 Hz camera smoothness, map gestures,
urban high-error GPS, 30 mph and 70 mph framing, roundabouts, tunnels,
power consumption and adverse connectivity. Real driving guidance
must follow the actual road signs, not an estimated camera position.

Package `com.drivemate.android`; version `1.5.0`; Android version
code `100500`. Sign the user-installable APK with the same private
DriveMate certificate as v1.4.2 to preserve in-place updates.
