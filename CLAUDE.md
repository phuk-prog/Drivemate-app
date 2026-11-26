# Drivemate-app (Android, Java, Gradle 8.9 wrapper)

Orientation so sessions don't need to rediscover the repo. Keep this file short.

## Where things are
- `app/src/main/java/com/drivemate/app/` — all app code. `MainActivity.java` is ~4,300 lines: never read it whole; use Grep, then Read with offset/limit.
- `app/src/test`, `app/src/androidTest` — unit / instrumented tests.
- `tools/` — release, version and lab scripts (Python/shell). `tools/test_*.py` are their tests.
- `docs/` — ~60 historical design notes. Read only the one named in the task; start from `docs/REPOSITORY-WORKFLOW.md`.
- `.github/workflows/` — CI; edit only when the task is about CI.

## Rules of thumb (token saving)
- Old release notes are in `docs/HISTORY.md`; don't read them or old `docs/DRIVEMATE-*RC*.md` unless asked.
- Grep for the symbol first; don't open whole files or list `docs/`.
- Release versions: `python3 tools/bump_release_version.py VERSION` (preview; `--apply` to write). Never hand-edit the version register.
- Don't edit dependency lockfiles by hand; they come from CI.
- Cross-repo map data lives in Drivemate-map; pinned by hash (`check_map_lock.py`).
- Run only the narrow test you need (`./gradlew :app:testDebugUnitTest --tests <Class>`), not the full suite.
- Safety: routing is preview-only, not road-approved. Don't loosen safety gates.
