# DriveMate 1.7.8 review candidate

Based on main 1.7.7. TomTom remains authoritative; no inferred lane occupancy or offline-routing promotion. Package and signing identity unchanged.

- Route-only matching with speed/time-bounded progress, heading, accuracy, ambiguity and duplicate-fix guards. Uncertain fixes hide lane/speed/traffic and stop camera prediction.
- Two confirmed fixes after a maneuver before advancement; speed-dependent spoken warning thresholds.
- Lane cards preserve all source markings and highlight only explicitly validated follow directions. Ambiguous or unrelated lane sections are hidden.
- Lower-centre framing target, automatic detail for verified complex junctions, cautious re-anchor and less 3D building clutter during navigation.
- Map data upgrades are separate; existing native resolution remains until validated higher-detail packs are available.

## Validation gates
Run the full Gradle regression suite, map checks and debug build. Review synthetic loops/parallel roads, missed exits, short consecutive turns, GPS recovery, duplicate fixes, 8-lane cards, day/night and portrait/landscape; collect passenger-operated physical Android road replays and compare with actual road signs. Verify camera framing on real screens before release. Signed candidate only via approved branch-scoped signing and established certificate fingerprint. Do not merge, tag, publish or treat as sole navigation without approval and validation.
