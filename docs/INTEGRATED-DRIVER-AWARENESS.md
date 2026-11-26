# Integrated driver-awareness candidate

Review branch only; not road-approved or signed. This candidate addresses the observed map and driving-screen issues without changing the stable APK identity or merging to main.

Implemented in this branch:
- Preserve the original route geometry; camera adopts a longer forward lead, more useful speed-aware pitch/zoom, and an approach bearing even at a zero-offset departure. Complex-junction framing remains bounded by existing viewport checks.
- Enlarge the directional vehicle marker. Keep real flat building polygons at higher zoom beneath optional extrusions; this cannot restore footprints absent from the source tile.
- After a confirmed route start, present the next actionable manoeuvre for an explicit departure token without advancing the underlying confirmation index. Recompute distance from confirmed progress. Compact controls with End available in the expanded row.
- Show a forthcoming limit at explicit adjacent, non-conflicting route speed sections; distinct advance voice preference, adaptive horizon, duplicate suppression and existing fix-confidence gates. Retain prior overspeed default and add zero tolerance option. Road signs take precedence.
- Map route speed transitions and supplied traffic events; expire route-derived markers after eight minutes. Preserve provider-matched traffic style policy.
- Read source-pinned bounded static UK camera, crossing and traffic-calming point data alongside the existing traffic-signal overlay. Points are map locations, not confirmation of current operation, direction or live hazard status. No proximity-only camera/traffic-light voice warnings.
- Free-driving foreground GPS view: heading/tilt/zoom and speed display without a destination.
- Second pass: preserve camera pitch/heading across style changes; source-matched hosted browse geometry (real roads, buildings, areas and mapped points), bounded to local coverage, with existing fallback. The independent HTTPS service must be configured and deployed.
- Second pass: conservative free-drive road matching checks two distinct accurate heading-consistent fixes, explicit numeric limits, competing road geometry and source identity. Conditional advance notice only for a sole same-named/referenced continuation with exact shared source node, explicit different limit and no intervening mapped branch. No predicted turn or legal-access guarantee. Context expires after two minutes.
- Second pass: on the independently verified route geometry, associate roadside events by original source way/node IDs and exact coordinates. Announce mapped traffic lights, crossings, calming, and explicitly both-direction mapped cameras; suppress unknown/one-direction cameras, conditional tags and ambiguous locations. Requires hosted mapped points; regional fallback alone has no point identities.
- Second pass: retain explicitly supplied TomTom v1 TEC accident, obstruction, animal, pedestrian and vehicle hazard categories even when delay is zero. Reference: https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route and TISA ITSTF20001 v1.5 tables 3-4, 3-5. Not a separate complete live hazard feed.

Not completed or certified by this change:
- Nationwide native high-zoom tiles and full building/address coverage; default national source remains z14. The new bounded exact-geometry hosted browse path is implemented but its national service is not deployed/verified. No fabricated map features or geometric smoothing.
- Verified fixed/mobile/average camera direction, coverage and zone membership; mapped speed-camera nodes alone are not enough.
- Live hazard/incident coverage beyond route sections, full camera direction and average-speed zone data, and deployment/real-road verification of the hosted context and conditional free-drive notices. Live signal phase information is not part of the mapped signal dataset. No claims of complete warnings.
- Real device screenshots, replay of the photographed journey, build/signature and passenger-operated road acceptance. CI does not prove physical-road safety. No main merge, signing secret exposure or public release.

Acceptance: exercise preview and guided driving at 0/urban/motorway speeds; check both heading-up and north-up, full junction and exit, day/night, pitch transition, vehicle bounds, flat/3D buildings at all zooms, GPS-loss and reroute, expired/ambiguous speed sections, simultaneous alerts and turn priority, UK/metric settings, free-drive activation and exit. Verify actual generated roadside asset coverage and attribution, and inspect actual rendered layers rather than relying on code presence.
