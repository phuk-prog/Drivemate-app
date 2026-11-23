# DriveMate 1.5.1 — local map startup without a fresh GPS fix

## Reported behaviour

User screenshot of v1.5.0: app launched into a zoomed-out world map.
Tapping a previously saved destination showed a toast reading
"Waiting for a fresh GPS fix. Enable device location, then try again."
The top interface, saved places and map engine loaded successfully, but
the app had no fresh navigation GPS position.

## Root cause in code

Previously DriveMate queried only GPS_PROVIDER's most recent cached fix
on startup and fed it to an onLocationChanged handler that rejected
anything more than 15 seconds old. NETWORK_PROVIDER's last-known fix
was ignored for initial visual positioning. With no accepted fix, MapLibre
could show its initial world view. OnCreate and onResume could also
remove and recreate the location subscriptions during startup.

This does not prove the phone's precise-location permission, device
Location service or outdoor GPS reception were enabled at screenshot time.

## v1.5.1 repairs

- Start MapLibre and TomTom raster at a **UK overview**, not the world.
- If available and newer than 30 days, restore an approximate, coarse
  (~1km) previous map area saved in app-private preferences. This
  location is **display only**, not a blue live GPS marker or a route origin.
- When location permission is granted, examine the phone's most recent
  **GPS_PROVIDER and NETWORK_PROVIDER** results. A cached coordinate
  newer than seven days with plausible accuracy may restore the *map view*.
  A fresh location (<=20 seconds and <=100m accuracy) may enter the
  actual navigation GPS path.
- Do not start and stop GPS subscriptions twice during launch. Remove
  them on pause and re-enable on resume; accept callbacks through both
  providers. Continue requiring valid, fresh GPS for driving routes.
- Distinguish user-facing errors for missing Precise Location permission,
  disabled phone Location services, stale GPS and pending fresh fix.
- The GPS/recenter control retries acquiring location even when the phone
  has not supplied a current fix.
- Unit tests cover UK overview, remembered area, stale location rejection,
  valid cached visual position and strict fresh navigation requirements.

## Location privacy and safety

Only a rounded **0.01-degree (~1km)** area and save time are persisted
within the app, at most once per minute when live GPS is available. They
do not leave DriveMate through this feature. Android permissions still
control location access. Historical map coordinates are never treated as
live driving positions: TomTom routing still requires a new accurate fix.
If no permission or enabled provider is available the app can show a
regional map, but it cannot navigate.

## Validation

Run Android unit tests, debug and release build, and verify signature
matches the existing owner's update certificate. On an Android device:
disable/enable phone Location, revoke/restore DriveMate Precise Location,
launch with poor GPS signal, return to a previously visited area, and
confirm recent-destination selection waits for **fresh** GPS instead of
routing from the displayed remembered location.

This is not a claim that GPS will be available indoors or with device
Location disabled. Zoom smoothness and junction-camera behaviour from
v1.5.0 are unchanged by this targeted release.

Package `com.drivemate.android`, version `1.5.1`, version code
`100501`.
