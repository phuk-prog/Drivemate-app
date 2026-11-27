# Repository consolidation and release contract

Base c6b6ee6ff7c841f88dcb88f4b995c9a1ea15cbc6 (signed 2.0.1). Candidate 2.1.0 / 200015 preserves ID and owner signing certificate. Main becomes the sole integration branch after review; feature PRs target main. Historical branches are retained, not deleted. The old signing workflows are removed from the consolidated tree; administrators must restrict signing secrets to a protected release environment and archive or disable historical branches to prevent their legacy workflows from accessing the same credentials.

The official release workflow checks a committed version register against the Gradle manifest and all published versioned release manifests; it refuses reuse/downgrades, verifies the real APK and owner certificate, and uploads a one-hour-target signed APK artifact with separately retained SHA256 and provenance. Earlier APK artifacts expire; keep an owner-controlled backup.

Use Gradle 8.9 wrapper with official distribution/JAR SHA-256. The app Gradle lockfile is committed; CI checks the resolved lock against the committed file. Build and test advisories are reported separately from release-runtime findings, but both currently block the signed-release gate. Android test-toolchain upgrades remain required; no advisory is silently waived. Cache the pinned world map only; verify its digest on every use. Dependabot proposes updates; independent OSV Maven scanning checks resolved dependencies without relying on GitHub dependency graph settings. Any reported advisory or incomplete scan blocks release until reviewed and fixed. Full recorded navigation replay (Wells fixture), offline and Stockport rendering gates run before release signing; optional six-app reference lab remains manually runnable and is not a competitor performance claim. Its UI replay is a test-only fixture, not road-certified guidance.

The separate older display-overhaul branch is not blindly merged over newer 2.x navigation. Its documentation and compact-screen policy are incorporated; route matching, provider behavior, new guidance and safety gates are retained. Remaining differences (alternate route UI, arrival cards, optional styling) require separate reconciliation and recorded device checks.

Map lock records exact source pins; do not automatically move to a failed nationwide candidate. Local routing is still preview-only. Origin snaps must have usable outgoing edges; weighted A* uses same priority as revised verifier while retaining device resource limits. National acceptance remains pending; no increase in Android limits.

Before road use: recorded roundabout, motorway, GPS-loss, route refresh, reroute, lane, accessibility, day/night, landscape, and real update install test on previous signed APK. Release prereleases are engineering candidates, not safety certification.

## 26 November stability gate

PR #36 was merged and 2.0.2 signed successfully; PR #48 is the 2.1 integration candidate. The host-only dependency
overrides must be resolved using the proposed-lock workflow; review and commit
the exact generated lock before the normal validation and security checks can
pass. No manually fabricated lock or advisory waiver. The signed workflow
uses environment `drivemate-release`; the owner must configure its reviewers,
main-only deployment policy and environment-scoped secrets, and remove legacy
repository-level signing secrets only after preserving secure backups.
See [stability acceptance](STABILITY-ACCEPTANCE.md).

Host dependency lock resolved by CI run 45766894580, job 139729840249:
132 Maven coordinates, zero OSV findings at scan time; generated lock SHA-256
d13fb793b28097cbca613ee7e20f3ebfad7d0a8161bcddbf813fa4a329db62ac.
The new commit still requires ordinary security, validation and replay gates.
Navigation start uses a separate tested GPS/provider policy; preview callbacks
reject obsolete route references and independent routes no longer imply live traffic was checked.

Owner delivery preference: no ZIP password; temporary signed-only APK download, one-hour deletion target with one-day GitHub fallback. See docs/PRIVATE-DISTRIBUTION.md.

## Workflow 3.0
Main protection changes are included as a merge parent; historical raw APKs are removed by idempotent scheduled cleanup. Validation/security/replay use PR checks and main pushes rather than duplicate feature pushes where applicable. Release readiness checks version advancement, remote immutable map asset digests, and APK distribution. Run python3 tools/bump_release_version.py VERSION to preview and add --apply to update the registry and Gradle together. Never bypass published version checks. National routing remains preview-only, as the two exact-anchor cases still fail. Preserve verified last-known-good packages in owner-controlled storage; recovery builds must use higher version codes. Physical device validation and required-check configuration are owner actions.

## Consolidation release control
During native-stack and dependency PR consolidation, official signing requires explicit workflow dispatch on main, rather than being triggered by a merge touching the workflow or version file. All existing version, security, replay, provenance and protected-environment gates remain unchanged. No preview or old provider-based build is automatically signed by these merges. Restore any automatic release trigger only in a separately reviewed change after native acceptance.

## Coordinated build-toolchain candidate
AGP 9.5.3 requires Gradle 9.6 or later; combine Dependabot #45/#47 on this candidate. Gradle 9.9.2 distribution SHA-256 971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b and wrapper JAR SHA-256 621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8 are pinned to the official https://gradle.org/release-checksums/ values. CI still verifies wrapper, regenerates and compares the dependency lock, scans dependencies, and builds/replays. The existing main lock is intentionally preserved until the proposed-lock workflow generates actual new resolution output. Do not merge a failed migration or change the signing trigger. This candidate is independent of the native renderer integration; reconcile and rerun against the final native tree before promotion.
