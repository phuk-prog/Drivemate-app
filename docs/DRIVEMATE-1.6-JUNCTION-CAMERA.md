# DriveMate v1.6.0 — forward-looking junction camera

## Changes

- **Junction-aware framing:** the camera planner uses the original TomTom route
  distance to the next manoeuvre, speed, and the visible area left after the
  top guidance and bottom control overlays. It reserves road distance after the
  turn and caps how far past a nearby junction the camera can look.
- **Automatic approach zoom:** zoom increases as the turn gets closer, but
  expands the viewing distance at higher speed for safety. The existing
  hysteresis, rate cap and post-turn hold remain.
- **3D perspective (MapLibre only):** during active guidance, pitch is usually
  28–48 degrees, stronger for longer approaches and more overhead at the
  junction. Pitch smooths independently at up to 20 degrees per rendered
  second. Manual route preview and idle map remain overhead.
- **Directionally correct vehicle marker:** the car icon is aligned to the
  approach road relative to the camera bearing. MapLibre now uses a screen-
  aligned arrow instead of a directionless circle. The osmdroid marker uses
  flat, screen-relative rotation and distinguishes direction-up from north-up.
- **Before-turn bearing:** the planner and vehicle arrow sample the approach
  route before the next unconfirmed manoeuvre rather than looking down the
  exit road too early.
- **Regression tests:** heading in both orientation modes, approaching-turn
  zoom and tilt, overlays and viewport framing, preventing premature turn,
  pitch rate limiting, and independently preserving vehicle heading.

## Limitations

- The legacy osmdroid/TomTom raster map **does not provide pitch**. It retains
  heading, smooth pan and automatic zoom as a 2D fallback. If the MapLibre
  vector map is unavailable or cannot render roads, 3D perspective cannot be
  displayed. Never silently present raster as 3D.
- This change does **not** alter TomTom routing, lane instructions, GPS
  measurements, traffic, off-route detection, or road restrictions. A detour
  shown by TomTom requires separate investigation with a recorded route.
- Framing is based on the route arc length and a conservative pitch projection
  allowance, not on a guaranteed measured view frustum for every screen ratio.
- Source vector street geometry still has native detail through zoom 14 with
  overzoom beyond it. Higher zoom alone cannot add road geometry.
- CI unit tests and an APK build are **not** actual-road validation. Test as
  a passenger or while safely parked; never interact with the screen driving.

## Suggested acceptance checks

1. Direction-up: approach a turn on a straight road; car arrow points along
   the visible road, not sideways, including at 2–5 mph.
2. North-up: arrow shows actual compass course without forcing north-facing
   map to rotate. Switch back to direction-up and verify smooth orientation.
3. At about 600, 300, and 100 yards before a junction, verify both the car
   and turn remain visible between top and bottom panels while the camera
   smoothly zooms in and gradually levels off.
4. Pass a junction: the route view holds close detail briefly before easing
   outward to the next instruction. Avoid rapid bearing flips on curves.
5. Try landscape and portrait, online/poor-network, GPS drift, and the raster
   fallback. Raster must remain usable even though pitch is unavailable.

Package: `com.drivemate.android`; version `1.6.0`; version code `100600`.
Keep the owner's existing private signing certificate for installable updates.
