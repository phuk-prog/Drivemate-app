# Turn-card overhaul

The live turn is topmost; the existing bottom journey metrics remain there. Distance, action and mapped street use separate visual levels. Signposts are shown only when returned by the routing provider. Road and sign text can wrap; the card limits secondary content by suppressing the following turn during complex guidance, and the supplemental junction diagram is used when the map is panned away and lanes are not displayed.

Roundabout arrows use the real route slice when available. They do not imply an exit count, lane or road that the route does not verify. The full diagram draws the calculated path with an arrowhead. Keep the existing voice instruction and no-unverified-exit-number policy.

Lane graphics use equal-width vector cells, with recommendations highlighted. GPS loss, inaccurate or stale fixes and large off-route errors suppress live turn data and lane advice. The upcoming manoeuvre list is accessible by tapping the card only with a fresh stationary GPS fix.

## Release acceptance checks

- Run the Android unit suite and debug build.
- Check small phones, landscape, accessibility font scaling and day/night contrast.
- Replay normal turns, UK roundabouts, forks, motorway exits and closely spaced turns; compare with real road signs and voice.
- Confirm lane arrows are equal-width and disappear when data is unavailable or stale.
- Disconnect GPS, restore it, and simulate off-route and reroute states; ensure old instructions cannot appear as current.
- Verify the card does not cover the junction on the map, and the stopped-only list cannot be opened while moving.
- Confirm geometry arrows reflect the provider path and do not show unverified exit counts.
