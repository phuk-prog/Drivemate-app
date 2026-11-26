# Consolidation reconciliation (26 November 2026)
Integration candidate: app PR #36; map PR phuk-prog/Drivemate-map#21.

| Historical PR | Evidence | Disposition |
| --- | --- | --- |
| App #27–32 | #32 head c6b6ee6 is ancestor of #36 (11 commits ahead at a73360d). | Preserve until integration accepted; no duplicate merge. |
| App #33 | Diverged history, but all Java replay/session code, docs and most tools are byte-identical in #36. Workflow and preparation script differ. | Review the consolidated workflow and preparation changes; do not close until verified. |
| App #34 | Diverged history; some policy/docs identical, multiple display/activity files differ. | Reconcile non-overlapping changes first; do not overwrite the independent navigation stack with older UI files. |
| Map #16–19 | #19 head 7789ca2 is ancestor of #21 (10 commits ahead at 3590ed8). | Preserve until map integration accepted; no duplicate merge. |

This pass brings the previously unchanged JunctionManeuverView and TripHeaderFormat plus matching TripHeaderFormatTest from #34 into #36. The route-direction arrow uses actual geometry, and long durations use hours/minutes. Remaining divergent display changes (MainActivity, VectorDriveMap, TomTomApi, turn/lane cards, camera and styles) need a deliberate semantic reconciliation and full UI replay, not wholesale replacement.

Source evidence: GitHub compare and recursive Git tree blob identities. An identical blob is not evidence of physical-device acceptance. No history rewrite, branch deletion, PR closure, main merge or release publication was performed.
