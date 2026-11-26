# Integrated driver-awareness candidate

Review branch only; not road-approved or signed. This candidate addresses the observed map and driving-screen issues without changing the stable APK identity or merging to main.

Implemented in this branch:
- Preserve the original route geometry; camera adopts a longer forward lead, more useful speed-aware pitch/zoom, and an approach bearing even at a zero-offset departure. Complex-junction framing remains bounded by existing viewport checks.
- Enlarge the directional vehicle marker. Keep real flat building polygons at higher zoom beneath optional extrusions; this cannot restore footprints absent from the source tile.
- After a confirmed route start, present the next actionable manoeuvre for an explicit departure token without advancing the underlying confirmation index. Recompute distance from confirmed progress. Compact controls with End available in the expanded row.
- Show a forthcoming limit at explicit adjacent, non-conflicting route speed sections; distinct advance voice preference, adaptive horizon, duplicate suppression and existing fix-confidence gates. Retain prior overspeed default and add zero tolerance option. Road signs take precedence.
- Map route speed transitions and supplied traffic events; expire route-derived markers after eight minutes. Preserve provider-matched traffic style policy.
- Read source-pinned bounded static UK camera, crossing and traffic-calming point data alongside the existing traffic-signal overlay. Points are map locations, not confirmation of current operation, direction or live hazard status. No proximity-only camera/traffic-light voice warnings.
- Free-driving foreground GPS view: heading/tilt/zoom and speed display without a destination. Road limits and directional warnings remain unverified without a trusted directed road match.

Not completed or certified by this change:
- Nationwide native high-zoom tiles and full building/address coverage; source remains z14 outside the existing regional candidate. No fabricated map features or geometric smoothing.
- Verified fixed/mobile/average camera direction, coverage and zone membership; mapped speed-camera nodes alone are not enough.
- Live hazard/incident feed beyond the existing route sections, live signal phases, free-driving road matching, verified speed-change warnings without a route, and complete roadside voice alerts. These require actual licensed/current provider data and route/direction association.
- Real device screenshots, replay of the photographed journey, build/signature and passenger-operated road acceptance. CI does not prove physical-road safety. No main merge, signing secret exposure or public release.

Acceptance: exercise preview and guided driving at 0/urban/motorway speeds; check both heading-up and north-up, full junction and exit, day/night, pitch transition, vehicle bounds, flat/3D buildings at all zooms, GPS-loss and reroute, expired/ambiguous speed sections, simultaneous alerts and turn priority, UK/metric settings, free-drive activation and exit. Verify actual generated roadside asset coverage and attribution, and inspect actual rendered layers rather than relying on code presence.
