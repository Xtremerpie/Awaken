# AWAKEN

A native Android app (Kotlin, Jetpack Compose, Material 3) that plays audio and video on a schedule: an hourly chime out of the box, custom recurring reminders, and traditional alarm clocks. Black and gold design, no account, no ads, no subscription.

> **Build status, read this first.** The source in this repository was written in an environment that could not reach the Android SDK or Maven repositories, so **the app has not been compiled and no APK has been produced or installed yet.** What *was* verified: the scheduling engine, the update verifier and the remote-config sanitiser (30 unit tests, compiled and run), every XML resource parses, and every Kotlin file parses. See [docs/TESTING_REPORT.md](docs/TESTING_REPORT.md) for the exact list. Your first build may surface compile errors in the Android-specific code; the fastest path is below.

## Get a debug APK

**Option A: GitHub Actions (no local setup).** Push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` runs the unit tests and builds `app-debug.apk`, which appears under the run's *Artifacts*.

**Option B: Android Studio.** Install Android Studio (Ladybug or newer, which bundles JDK 17+). Open this folder, let Gradle sync, press Run, or Build > Build APK(s).

**Option C: command line.** Needs JDK 17+ and the Android SDK (platform 35, build-tools). Set `ANDROID_HOME`, then:

```bash
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the build reports compile errors, send me the log and I will fix them. That is expected on a first compile of ~45 files that could not be compiled where they were written.

The debug build installs as `com.awaken.app.debug` so it can sit beside a release build.

## Release build and signing

```bash
keytool -genkeypair -v -keystore awaken-release.jks -alias awaken -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` next to `settings.gradle.kts` (it is git-ignored):

```
storeFile=/absolute/path/awaken-release.jks
storePassword=...
keyAlias=awaken
keyPassword=...
```

Then `./gradlew :app:assembleRelease`. In CI, add secrets `AWAKEN_KEYSTORE_B64` (base64 of the .jks), `AWAKEN_KEYSTORE_PASSWORD`, `AWAKEN_KEY_ALIAS`, `AWAKEN_KEY_PASSWORD` and push a `v*` tag.

**Keep the keystore safe and backed up.** Android only installs an update signed with the same key as the installed app. Lose it and no existing user can be updated.

## What it does

| Area | Implementation |
| --- | --- |
| First install | Bundled `Video-26260.mp4` is copied to private storage and set as default media; an enabled schedule plays it every 60 minutes. |
| Schedules | Unlimited. Alarm-clock or recurring (15/30/60/custom minutes inside a start–end window), weekdays, date range, media or playlist (sequential/random), volume, max duration, times-per-trigger, snooze, gradual volume. |
| Reliability | `AlarmManager` (`setAlarmClock` for alarms, `setExactAndAllowWhileIdle` for recurring, inexact fallback if exact alarms are denied), foreground media service, boot / time-change / app-update restore, duplicate-trigger guard, missed-trigger logging, rolling error log. |
| Player | Media3/ExoPlayer in a `MediaSessionService`: notification and lock-screen controls, volume/mute, speed, repeat/loop, seek, fade-in/out, live spectrum visualizer, video surface. |
| Library | Storage Access Framework import with persisted permissions; validates type, size and decodability; rename, delete, default, playlists. |
| Update center | Manifest check, version/size/date/changelog, optional or mandatory, HTTPS download, size + SHA-256 + package + version + signing-certificate verification, hand-off to Android's installer, re-check of the installed version, full-screen lock for mandatory updates. |
| Remote config | Branding, themes, dashboard layout, feature flags, announcements, texts, hidden settings, featured downloads, suggested schedules. Data only. |
| Data | Room, DataStore, backup/restore to a user-chosen file, factory reset. |

## Architecture

```
core/   pure Kotlin (no Android): ScheduleEngine, TriggerGate, PlaybackOrder, VolumeRamp,
        update manifest + ApkVerifier + SecureUrl, RemoteConfig parser/sanitiser   ← unit-tested
app/
  data/         Room entities/DAOs, repositories, SettingsStore (DataStore), backup, remote-config repo
  scheduling/   AlarmScheduler, AlarmReceiver, BootReceiver
  playback/     PlaybackService (Media3), PlaybackBus (shared state), LevelTap (visualizer)
  notifications/channels, alert + failure notifications, action receiver
  update/       SecureHttp, UpdateRepository
  di/           AppContainer (hand-written constructor injection)
  ui/           Compose screens + ViewModels (MVVM), theme, components, navigation
```

Dependency injection is a small hand-written container instead of Hilt: fewer generated moving parts, every dependency explicit.

## Permissions

`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` (to hand a verified update to the system installer). No storage, contacts, location, camera or microphone permission.

## Honest limits

- A force-stopped app receives no alarms until it is opened again. Some phone makers add their own background killers; Alarm settings explains how to relax them.
- Without the exact-alarm permission, Android may deliver triggers late. The app says so instead of pretending.
- A trigger that arrives more than 10 minutes late (30 for alarms), for example after the phone was off, is skipped and recorded in History rather than played at a surprising time.
- Alarm-clock playback uses the alarm audio stream. Whether it passes Do Not Disturb is controlled by the user's DND settings.
- Backup stores references to media, not the files.

## More

- [docs/UPDATE_SYSTEM.md](docs/UPDATE_SYSTEM.md): manifest format, signing, deployment, remote-config schema, how V2–V5 add features
- [docs/TESTING_REPORT.md](docs/TESTING_REPORT.md): what was verified, and the on-device test plan
- `update-server/`: example manifest, example remote config, `make_manifest.py`
- `docs/assets/`: logo SVG and PNGs (the launcher icon itself is the vector drawables in `app/src/main/res`)
