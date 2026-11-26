# Road-test navigation improvement candidate

Based on the five Cheadle road-test screenshots, not a live Waze replay. This branch builds on 2.1.1 and preserves its map source and signing contract. No signing, deployment, route data changes or safety certification.

Changes: route-aligned vector turn marker, remaining-route overview with recenter, explicit coincident speed-limit change wording, route-indexed incident stretches and traffic state wording, larger static traffic-light symbols at zoom 14, explicit speed marker font, and last-confirmed metrics for up to 30 seconds during positional uncertainty. Overview continues to update the vehicle on fixes while camera follow is paused.

Existing audio modes, route matching confidence, GPS rate, rerouting thresholds, building layers, mapped roadside data, lane guidance and arrival access policy are retained rather than weakened. The photographed journey must still be replayed: 09:28 Massie Street, 09:30 Wilmslow Road, 09:36 Queens Road, 09:37 Councillor Lane, 09:39 Boundary Road. Verify both map styles, raster fallback, dark/light, silent/full, recovery, close turns and real mapped feature coverage. The on-map maneuver overlay and traffic stretches are vector-only. No unverified crossing or road features are synthesized. Nationwide data coverage, camera automatic framing and physical-device responsiveness remain acceptance work. Existing release version retained pending release gate; no APK built or signed.

