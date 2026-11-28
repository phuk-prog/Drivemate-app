# DriveMate 2.2.1 — full audit

Clean-slate audit of the app as of 2.2.0. Each finding lists where it lives, what the driver saw, and what 2.2.1 does.

## A. Map display (the "inconsistent map" problem)

| # | Finding | Where | Fix in 2.2.1 |
|---|---------|-------|--------------|
| A1 | Tile zoom ignored screen density. On a 2.6–3.5× phone the map asked for tiles 1–2 levels too coarse, so streets, names and buildings came and went between zoom steps. | `NativeTileSource` (zoom → tile z) | `MapLayers.sourceZoom(zoom, density)` = floor(z − log2(density) + 0.6), clamped 2–14. |
| A2 | View capped at 16 tiles, which forced z12 at city scale (sparse tiles, no minor roads). | `NativeTileSource` | Cap raised to 49 tiles (`MAX_VIEW_TILES`), loaded centre-first. |
| A3 | Going over the vertex budget threw an exception, and the whole view fell back to the world z0–6 map (the "blank / blobby map after Back"). | `NativeTileSource` | Over-budget tiles are skipped (counted as "trimmed"); the rest of the view still draws. Budget 900k vertices / 80k shapes. |
| A4 | A tile that timed out left a hole. | `NativeTileSource` | Falls back to a cached parent tile (up to 3 levels up). |
| A5 | Browse mode drew a second "detail overlay" only in a 530×670 m box, so detail stopped at a visible edge. | `NativeMapController` | Browse overlay off (`MapLayers.BROWSE_OVERLAY=false`); overlay kept only for the route corridor. |
| A6 | Tile cache too small to hold a zoomed-in city view plus its parents. | `NativeTileSource` | 128 tiles / 1.2M vertices. |
| A7 | No way to see why the map looked wrong. | — | Settings → Developer → "Map debug overlay": tile z, loaded/total, missing, trimmed, points, draw time. |
| A8 | Most UK maps came from the network every time. | `MainActivity` | One-time offer on Wi-Fi to download a map area when none is installed. |

## B. Routing and messages

| # | Finding | Where | Fix |
|---|---------|-------|-----|
| B1 | Raw engine errors (exception text, cell ids) shown to the driver. | route error handler in `MainActivity` | `RouteMessages.friendly(...)`: outside coverage / no road nearby / already there / generic. |
| B2 | Offline routing only covers Stockport and Cheadle (`native-region-catalog.json` is empty), but nothing said so. | `DmTileEngine`, coverage text | Coverage line now says "outside offline routing area"; the error says to connect for routes elsewhere. **Still open:** building UK-wide routing cells needs a Drivemate-map data run. |
| B3 | Preview card mixed engine names, provider jargon and policy notes. | `previewDetails`, status | Preview shows distance · time · traffic only; status "Route ready · tap Start when parked" + live traffic / offline route. |
| B4 | Route-switch prompt was unclear. | reroute offer | "Different route available · choose Switch or Keep" / "Keeping current route". |
| B5 | Recents showed "0 yd" for the place you are standing at. | recents list | Shows "Here" under 80 m. |

## C. Features that did nothing

| # | Finding | Where | Fix |
|---|---------|-------|-----|
| C1 | Both parking buttons ("🅿 Nearby" on preview, parking while stopped) only showed "Native parking search is not yet validated". | `findNearbyParking()` | Real search: car parks within 1 km of the destination (online search when a key is saved and there is signal, offline names otherwise), nearest first, tap one to drive there. |

## D. Settings and wording

| # | Finding | Fix |
|---|---------|-----|
| D1 | Settings rows used internal names ("native", "provider", "regional cells"). | "Routing · online key and coverage", "Offline routing areas", "Download map areas · faster, works offline". |
| D2 | Debug-only rows mixed into everyday groups. | New Developer group holds debug overlay and lab rows (unlocked by 7 taps, as before). |

## E. Still open (cannot be done inside this repo session)

1. UK-wide offline routing data (Drivemate-map pipeline run + new hashes).
2. Lane data: the OSM extract has no `turn:lanes` on the tested roads, so lane arrows only appear where OSM has them.
3. Spotify App Remote (play/skip via Spotify itself) needs a Spotify developer client id; the media-session controls work today.
4. Road-validated routing: routing data is owner-approved, not independently validated.

## Tests
- New: `RouteMessagesTest`, `MapLayersTest.sourceZoomFollowsScreenDensity`.
- Updated: `NavigationStartPolicyTest` (traffic wording).

## 2.2.2 follow-up (from the 2.2.1 home-screen screenshot)

| # | Finding | Fix |
|---|---------|-----|
| F1 | Roads looked "beaded": every road piece drew its own grey casing over the previous piece's fill, so dark rings showed at every join and tile edge. | `NativeBaseRenderer` batches each road class into one path; all casings are drawn before any fill. |
| F2 | Overview zoom was a mass of thick yellow lines. | `MapLayers.roadWidth` thins roads below street zoom; `MapLayers.casing` drops casings on minor roads below z14. |
| F3 | Place names in white boxes, some cut by the search bar and the bottom panel (Chapel Field, Prestwich, Sal…, Baguley). | Names drawn with a soft halo, no box; names kept out of the area under full-width overlays (measured from the layout). |
| F4 | Panning felt heavy: the whole map (hundreds of thousands of points) was redrawn on the UI thread for every finger movement. | Flat map is drawn on a background thread into a bitmap; while you drag or pinch, the last bitmap is moved/scaled instantly and refreshed behind the scenes. |
| F5 | No momentum: the map stopped dead on release. | Fling with smooth decay. |
| F6 | New areas only loaded after the finger lifted, and a load requested within 1 s of the previous one was silently dropped (map stayed part-empty). | Tiles are requested every 400 ms while dragging/flinging; throttled requests are deferred, never dropped (350 ms gap). |
