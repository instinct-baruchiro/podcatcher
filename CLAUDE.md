# CLAUDE.md

Guidance for AI agents working in this repository. Adopted from
[`baruchiro/vibe-coding-template`](https://github.com/baruchiro/vibe-coding-template)
(process only; the stack is native Android).

## Project

Podcatcher (פודקאסטים) — native Android podcast app, Kotlin, no appcompat /
Material, plain XML layouts, Hebrew RTL. See `README.md` and `STORIES.md`.

## Commands

| Task | Command |
|---|---|
| Build (typecheck+assemble) | `./gradlew assembleDebug` |
| Unit tests (JVM) | `./gradlew testDebugUnitTest` |
| On-device flow + screenshots | `./gradlew connectedDebugAndroidTest` (booted emulator/device) |
| Story-coverage gate | `node scripts/check-stories.mjs` |

No formatter/linter is configured; keep the existing style (4-space Kotlin,
plain Android views).

## Layout

- `app/src/main/java/com/baruchiro/podcatcher/`
  - `net/Discovery.kt` — iTunes search + RSS parsing (`Platform.kt` holds the
    parser factory so tests can swap in kxml2 on the JVM).
  - `data/Store.kt` — persistence (SharedPreferences + files), download map
    per feed, keep-N pruning.
  - `player/PlayerService.kt` — Media3 `MediaSessionService` (background
    playback, media notification).
  - `ui/MainActivity.kt`, `ui/PodcastActivity.kt` — library and episodes.
  - `work/FeedRefreshWorker.kt` — hourly refresh/auto-download (WorkManager).
- `app/src/test/` — JVM tests (`*.test.kt`), fixtures in `resources/`.
- `app/src/androidTest/` — on-device flow with per-stage screenshots.

## How we work (portable defaults)

- **Stories are the contract.** `STORIES.md` describes product behavior;
  changing behavior wording needs the owner's approval (a one-word answer is
  enough). Every story needs at least one test tagged `// @story: <ID>`;
  `node scripts/check-stories.mjs` fails the build otherwise. Stories not yet
  covered carry `<!-- @unimplemented -->` with an honest status note.
- **Test-first where feasible.** New behavior starts as a story + a failing
  tagged test.
- **Verify before claiming done.** Logic: JVM tests. UI/playback/downloads:
  on-device run with screenshots — compilation and signing are NOT runtime
  verification, and screenshots must be real (never mock-ups).
- **Pre-push gate**: `/ship` (build, story coverage, tests; instrumentation
  when an emulator is available). Push only through `/open-pr` or the owner.
- **No secrets in the repo.** The debug keystore is local-only; nothing
  personal goes into code, fixtures, or commit history.

## Fixed PR flow (when issues are tracked)

Assign the issue → comment the session link → branch `{issue}-{slug}` → PR
that `Closes #N`. Not active yet: this repo doesn't track issues so far.
