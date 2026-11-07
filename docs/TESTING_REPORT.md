# Testing report

Date: 2026-11-07. Environment: Linux container, JDK 21, Gradle 8.14.3, Kotlin 2.0.21 compiler (bundled with Gradle). **No Android SDK; Google Maven, Maven Central, the Gradle plugin portal and dl.google.com all returned HTTP 403 from the network policy.** Nothing that needs Android classes could be compiled, run, or installed here.

## 2026-11-07 build attempt

`./gradlew :core:test` and `:app:assembleDebug` both failed at plugin resolution (`com.android.application 8.7.3 was not found`) because the sandbox blocks Google Maven. Log: `docs/build-attempt-2026-11-07.log`. Targeted fixes made afterwards: `@get:Ignore` on `ScheduleEntity.scheduleKind` (Room would otherwise treat it as a column), braced statements in `BackupManager.restore`. Core tests re-run: 30 passed (`docs/core-test-run.log`). The CI workflow now uploads the debug APK (`awaken-debug-apk`) plus test/build logs and `apk-info.txt` (`awaken-build-logs`).

## Result in one line

The platform-independent logic is tested and passing. The Android layer is written but **unbuilt and untested**. There is no APK from this environment, so "verify that the APK installs" is **not done**.

## Verified

| What | How | Result |
| --- | --- | --- |
| `core` module (schedule engine, trigger gate, playlist order, volume ramp, update manifest/verifier, URL rules, remote-config sanitiser) | Compiled with kotlinc 2.0.21 and run with 30 JUnit-style tests (`core/src/test`); output in `docs/core-test-run.log` | 30 passed, 0 failed |
| All Android XML (manifest, drawables, values, xml) | Parsed with an XML parser | 0 errors |
| All 35 Kotlin files in `app/` | Run through the Kotlin compiler's parser | 0 syntax errors (type-checking impossible without Android classes) |
| `make_manifest.py` | Ran on a dummy file | Correct size and SHA-256 output |
| Launcher/logo vector artwork | Rendered the SVG twin of the vector drawables | Looks as intended (`docs/assets/`) |

The 30 core tests cover: first-install hourly schedule fires on the next hour; 15/30/45-minute intervals; weekday filtering; quiet hours incl. overnight wrap; alarm clock today/tomorrow/weekday; windows that cross midnight; date ranges; disabled/invalid schedules; a DST spring-forward day; duplicate and stale triggers; random/sequential order; fade and gradual-volume maths; manifest parsing and rejection (HTTP, IP literals, credentials, bad hash, future schema); optional vs mandatory updates; lock lifting; verifier rejection of wrong size, hash, package, version, downgrade, unsigned or differently signed APKs, and pinned-signer mismatch; SHA-256 correctness; remote-config sanitising (unknown widgets/flags/texts dropped, unsafe media dropped, protected settings kept visible).

## Not verified (needs a device or emulator)

Compilation of the Android code, UI rendering, Room schema generation, ExoPlayer playback, the foreground service, AlarmManager delivery, screen-off playback, reboot restore, permission flows, SAF import, backup/restore, real downloads and installation, and every on-device behaviour below.

**Where I'd expect first-build compile errors** (APIs I wrote from memory against Media3 1.5.1 / Compose BOM 2024.12.01 without a compiler):
1. `PlaybackService`: the `DefaultRenderersFactory.buildAudioSink` override signature, `DefaultMediaNotificationProvider.Builder`, `MediaSession.Callback.onConnect/onCustomCommand`.
2. `PlaybackBus.kt` `LevelTap` (a `BaseAudioProcessor` subclass).
3. `PlayerScreen` (`MediaController`, `PlayerView`).
4. Compose/Material3 signatures in the screens (`TimePicker`, `DatePicker`, `FlowRow`, pager).
5. `app/build.gradle.kts` signing block.
They should be small, local fixes. The GitHub Actions log shows them all at once.

## On-device test plan

Run on a physical phone (Android 8–15 if possible). `adb` commands are optional helpers.

| # | Area | Steps | Expected |
| --- | --- | --- | --- |
| 1 | First installation | Fresh install, open | Splash, onboarding; Library has *Video-26260* marked DEFAULT; Schedules has an enabled *Hourly playback*; Home shows a next trigger on the next hour |
| 2 | Media importing | Library > Import; pick MP3, WAV, M4A, AAC, MP4 together | All appear with duration; file persists after app restart and reboot |
| 3 | Invalid media | Import a `.txt`, a renamed `.mp3` that is really text, a 0-byte file, a corrupt mp4 | Each rejected with a reason in the import report; library unchanged |
| 4 | Hourly scheduling | Set interval 15 min via Settings; wait | Plays at :00/:15/:30/:45; History shows each; changing interval re-times the next trigger |
| 5 | Custom schedules | Create alarm (weekdays, snooze), recurring window 9–17 every 45 min, playlist random | Next-trigger text matches; fires on time; playlist varies without immediate repeats |
| 6 | Volume persistence | Change volume in Player and in Settings; force-close and reopen; trigger a schedule | Same volume restored; a schedule with its own volume uses that instead |
| 7 | Screen-off playback | Lock the phone; schedule 2 min ahead (alarm kind and recurring kind) | Audio plays, notification with controls shows on lock screen; Stop/Snooze work |
| 8 | Reboot restoration | Reboot with schedules enabled; do not open AWAKEN; wait for the next trigger | Plays; `adb shell dumpsys alarm \| grep -i awaken` shows the alarms after boot |
| 9 | Permission denial | Deny notifications; revoke exact alarms; leave battery restricted | Home banner and Alarm settings show what is off; playback still works but timing is approximate and the app says so; no crash |
| 10 | Duplicate alarms | Open/close app repeatedly, toggle schedules, change time zone | Exactly one PendingIntent per schedule (`dumpsys alarm`); each trigger plays once; History shows no doubles |
| 11 | Interruptions | Play, then start a phone call / other audio / unplug headphones | Pauses via audio focus; resumes after transient loss; stops after 2 min if never resumed |
| 12 | Do Not Disturb / quiet hours | Enable DND (Respect it) and quiet hours | Recurring triggers logged as *Skipped: Do Not Disturb / Quiet hours*; alarm clocks still ring |
| 13 | Update availability | Serve `update.json` (higher versionCode) over HTTPS; Update center > Check | Shows current vs latest, size, date, changelog |
| 14 | Failed downloads | (a) wrong `sha256`, (b) wrong `sizeBytes`, (c) APK signed with another key, (d) Wi-Fi off mid-download, (e) `http://` URL | (a–c) *Update rejected, nothing installed* and the file is deleted; (d) retry button; (e) manifest refused |
| 15 | Install & recheck | Good APK: Download and verify > Install | System installer opens; after install the app reports success only once Android shows the new version; cancel the installer and the UI says it was not installed |
| 16 | Mandatory update | Set `minSupportedVersionCode` above installed | Full-screen *Update required*, no navigation; survives restart and airplane mode; lifts only after installing |
| 17 | Remote config | Serve `config.example.json`, refresh | Tagline/theme/layout/flags/announcement apply; garbage keys ignored; works offline from cache |
| 18 | Backup / reset | Back up, change things, restore; then Reset | Restore matches the backup; a malformed file changes nothing; Reset returns to first-install state |
| 19 | Force-stop honesty | Settings > Force stop; wait for a trigger | Nothing plays (Android limitation); opening the app restores schedules |

Helpful commands: `adb logcat -s AWAKEN`, `adb shell dumpsys alarm`, `adb shell dumpsys deviceidle force-idle` (Doze test), `adb shell cmd appops get com.awaken.app.debug`.
