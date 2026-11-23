# DriveMate 1.4.2 — top-of-map driving guidance

This update responds to the real DriveMate v1.4.1 navigation screenshot,
where the next-turn arrow, remaining distance, time left and arrival estimate
were inside a large panel covering the bottom of the road map.

## New live driving layout

- **Top of map:** 24-hour UK ETA, **minutes remaining**, and **miles
  remaining** in three large, high-contrast columns. The distance respects
  the saved kilometres preference when metric units are selected.
- **Next-turn card:** a separate dark card immediately below the trip strip,
  with a large TomTom manoeuvre arrow, distance to next turn, street name,
  and the following manoeuvre in one small line. The same instructions
  continue to drive spoken guidance; there is no new routing data source.
- **More map:** the large lower guidance panel is hidden in active navigation.
  Repeat, refresh and End are one compact bottom row; the normal destination
  search bar is hidden only while actively driving. Search and the full
  route-preview content reappear when navigation ends.
- The GPS speed and recenter buttons are repositioned above the compact
  navigation row, not above the old tall bottom panel.
- The junction camera now measures the actual **top trip/turn overlay height**
  and the smaller bottom action row. It no longer assumes a fixed 145dp
  header when calculating the visible road-map area.
- ETA, remaining minutes, mileage and instructions update from the existing
  TomTom route progress and GPS refresh. ETA remains a **prediction**, not a
  promised arrival time.
- Compact summary values have separate TalkBack descriptions.
- Pure Java `TripHeaderFormatTest` covers rounded minutes remaining,
  UK miles including less than 0.1 miles, and the saved metric preference.

## Scope and limitations

Changes only the DriveMate navigation UI. Does not change map source,
live traffic settings, route geometry, GPS processing, API keys,
permissions, app identity or signing certificate.

Tests verify Java formatting and Android compilation. A real Android
device must still confirm the text sizes, screen insets and readability
in portrait and landscape modes before using the UI for driving.
The app is not road-certified; test with the vehicle parked or a
passenger observing.

Version `1.4.2`, version code `100402`, package
`com.drivemate.android`. Sign release with the same private owner
certificate as DriveMate v1.4.1 for in-place updates.
