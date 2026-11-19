---
description: Pre-push verification — build, story-coverage, tests. Fix everything before declaring done.
---

Run the full pre-push sequence and report results. Do not skip any step. If a
step fails, stop and fix before continuing.

1. **Build / typecheck** — `./gradlew assembleDebug` must succeed.
2. **Story coverage** — `node scripts/check-stories.mjs` must pass (every
   story has a tagged test; no test references an unknown story ID).
3. **Unit tests** — `./gradlew testDebugUnitTest` must be green.
4. **On-device** — when an emulator or device is available,
   `./gradlew connectedDebugAndroidTest`; UI/playback/download changes are not
   "done" without it (CI runs it on every push).
5. **Secrets sweep** — confirm no keystore, `local.properties`, or personal
   paths are staged.

When all steps pass, say so explicitly with the command outputs. If any step
fails, surface the failing command and the relevant error excerpt — do not
summarize away the actual error message.

Do not run `git push` automatically. The user pushes (or `/open-pr` does).
