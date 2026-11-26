# Unified display overhaul

Builds on the turn-card work in PR 34. It changes the display, not the navigation graph or the original TomTom route used for guidance.

- Map: day/night road palette and label refinement; consistent route casing, remaining-route rendering, direction indicators, alternative route overlay, dimmed uncertain vehicle marker, lower pitch for constrained camera viewports, compass and reduced driving clutter. The local UK tile source remains z14; no fabricated detail.
- Driving cards: numeric speed and separate unit, UK-style speed limit, unknown speed dash, trip progress and current/stale traffic estimate, longer-duration formatting, visible prioritised connection/reroute/fallback status, expanded arrival and hazard cards, stationary-only parking/traffic details.
- Route preview: request up to two alternatives from TomTom Routing v1; only present routes with valid geometry and guidance. Retry single route when alternatives are rejected by the provider. Display alternatives as dashed paths, compare provider estimates and allow selection. Replan if origin moved or the route is stale. In-drive alternative offers retain current safeguards and expire automatically.
- Controls: vector icons, larger touch targets, compass/north-up, expandable recent destinations with explicitly direct distances, destination title in route preview, grouped settings, compact-screen suppression of optional content.
- Safety: continue valid guidance during background refresh; hide stale directions and trip metrics on uncertain GPS/off-route; no new guessed lanes, exit counts, hazards, speed limits or road geometry.

## Acceptance criteria before merging/release

Run unit tests and build the Android APK, then replay urban, motorway and roundabout routes in both map engines. Check route arrows, map labels/attribution and compass orientation; verify the primary route remains dominant over alternatives. Check loss/recovery of GPS and data, route refresh during motion, off-route recalculation, expired alternatives, stale journey estimates, missing speed and zero speed. Test phone sizes, landscape, font scaling, TalkBack, day/night and keyboard. Test API entitlement fallback and multiple real alternatives. Check CPU/GPU load during route trimming and camera motion. Test arrival, parking permission and stationary restrictions. No physical driving or render validation has been performed by the author.

TomTom reference: https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route
