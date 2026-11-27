# Drivemate-app (Android, Java, Gradle 8.9 wrapper)

Orientation so sessions don't need to rediscover the repo. Keep this file short.

## Where things are
- `app/src/main/java/com/drivemate/app/` — all app code. `MainActivity.java` (~3,200 lines) still holds guidance/location state; settings, destinations, parking, review, home panel, road context and guidance dialogs live in `*Controller.java` / `GuidanceDialogs.java` (they reach activity state through field `a`). Grep first, then Read with offset/limit.
- `app/src/test`, `app/src/androidTest` — unit / instrumented tests.
- `tools/` — release, version and lab scripts (Python/shell). `tools/test_*.py` are their tests.
- `docs/` — ~60 historical design notes. Read only the one named in the task; start from `docs/REPOSITORY-WORKFLOW.md`.
- `.github/workflows/` — CI; edit only when the task is about CI.

## Rules of thumb (token saving)
- Old release notes are in `docs/HISTORY.md`; don't read them or `docs/archive/` (old RC notes) unless asked.
- Grep for the symbol first; don't open whole files or list `docs/`.
- Release versions: `python3 tools/bump_release_version.py VERSION` (preview; `--apply` to write). Never hand-edit the version register.
- Don't edit dependency lockfiles by hand; they come from CI.
- Cross-repo map data lives in Drivemate-map; pinned by hash (`check_map_lock.py`).
- Run only the narrow test you need (`./gradlew :app:testDebugUnitTest --tests <Class>`), not the full suite.
- Driving: the owner lifted the preview-only guard (`NavigationStartPolicy.LOCAL_ROUTES_PREVIEW_ONLY=false`); local routes can be started. Routing data is owner-approved, not independently road-validated. Keep the integrity checks (dependency lock, map-lock hashes, wrapper checksum, action pins, signing environment).

## Native integration note
The independent candidate consolidates the new original renderer and regional routing in MainActivity and NativeMapController/NativeRegionalRouter. The older extracted provider-era controllers from PR #52 are superseded in the compiled app; their historical implementations remain in the Git history. Do not reintroduce SDK/provider adapters from that refactor. Documentation archive and read-deny rules remain.
