# Podcatcher (פודקאסטים)

A native Android podcast app in Kotlin (minSdk 26, target 34), Hebrew RTL UI:

- Subscribe by pasting an RSS feed link or by searching a podcast name (iTunes).
- Background playback with lockscreen/media controls (Media3).
- Download episodes to on-device storage; play the local copy when present.
- Per-podcast policy: auto-download new episodes, keep newest N (1/3/5/10),
  playback speed ×1–×2, skip intro 0/15/30/60s.
- Hourly background refresh + auto-download with a summary notification
  (WorkManager).

Status: early trial build. Behavior is specified in `STORIES.md`; the
workflow contract for AI agents working on this repo is in `CLAUDE.md`.

## Build & test

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0).

```sh
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest        # JVM unit tests (feed parsing, search)
node scripts/check-stories.mjs     # story-coverage gate
./gradlew connectedDebugAndroidTest  # on-device flow + screenshots (booted emulator/device)
```

The on-device test subscribes to a live feed, plays and downloads an episode,
and saves a screenshot per stage under the app's files dir on the device;
CI pulls them as the `emulator-screenshots` artifact.

Signing: local debug keystore only, never committed. No release signing yet.
