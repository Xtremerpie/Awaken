# Update system

Two layers with a hard line between them.

| Layer | Can change | Cannot change |
| --- | --- | --- |
| **Remote configuration** (JSON) | Branding colours and tagline, theme presets, dashboard widget order, feature flags, announcements, a fixed set of UI texts, which hideable settings sections show, featured downloads, suggested schedules | Behaviour. It carries no code, scripts or expressions; unknown keys are discarded. |
| **Signed APK update** | Anything: new screens, new engines, new permissions | Nothing is installed without Android's own installer and the user's confirmation |

## Remote configuration

The app fetches the URL set in Update center > Remote configuration (empty by default, so the app contacts nobody until you set it). The document is parsed by `RemoteConfigParser` in `core/`, which:

- rejects the whole file if `schemaVersion` is missing or newer than the app understands (`BuildConfig.CONFIG_SCHEMA_VERSION`);
- keeps only dashboard widget IDs, feature flags and text keys the app defines (see the enums in `core/.../remote/RemoteConfig.kt`);
- validates colours as `#RRGGBB`, IDs against `[A-Za-z0-9_.-]{1,40}`, caps text at 280 characters and lists at 20 items;
- accepts featured media only with an `https` URL on a public host, an audio/video extension, a SHA-256 and a size ≤ 100 MB; the user must tap *Add* to download it and the checksum is verified;
- never lets `updates` or `reset` be hidden from Settings;
- adds suggested schedules only when the user taps *Add*.

The last good config is cached and applied at start-up, so the app is correct offline. See `update-server/config.example.json`.

## Real app updates

### Manifest (`update.json`)

```json
{
  "schemaVersion": 1,
  "packageName": "com.awaken.app",
  "versionCode": 2,
  "versionName": "1.1.0",
  "minSupportedVersionCode": 1,
  "mandatory": false,
  "releaseDate": "2026-12-01",
  "sizeBytes": 18432000,
  "sha256": "<64 hex>",
  "apkUrl": "https://updates.example.com/awaken/awaken-1.1.0.apk",
  "signerSha256": ["<64 hex of the release certificate>"],
  "changelog": ["What changed"]
}
```

`versionCode` must equal the APK's `versionCode` in `app/build.gradle.kts`, and must be higher than what users have.

### What the app checks, in order

1. Manifest is valid JSON and a supported schema; `packageName` equals the installed app's; `apkUrl` is HTTPS on a public host (no IP literals, `localhost`, or credentials in the URL; every redirect hop is re-checked).
2. `versionCode` is newer than the installed one. Otherwise: up to date.
3. The download is capped at `sizeBytes` while streaming.
4. After download: size matches; SHA-256 matches; `PackageManager` can read the file; package name matches; version code equals the manifest's and is newer than installed; the signing certificate equals the installed app's certificate (and is on `signerSha256` if the manifest pins one).
5. Only then does it open Android's package installer on the file (through a `FileProvider`). If "install unknown apps" is not allowed for AWAKEN, it sends the user to that Android setting first.
6. When the app is next in the foreground it asks Android for the installed version code. Only if that is at least the manifest's does the UI say the update is installed. If the user cancelled or Android refused, it says so and offers to retry. The app never claims success earlier.

Any failure at 3–4 deletes the file and installs nothing.

### Mandatory updates

An update is mandatory if `"mandatory": true` **or** the installed `versionCode` is below `minSupportedVersionCode`. The required version code is stored in DataStore. While the installed version is below it, the whole app is replaced by a full-screen *Update required* screen. The lock survives restarts and being offline, and lifts only when Android reports the required version installed. To retire a bad version, raise `minSupportedVersionCode`; to force everyone, also keep `versionCode` ≥ it (a manifest that demands a version it doesn't offer is treated as invalid and does *not* lock users out).

Alarms already registered keep working while locked; only the UI is blocked.

## Deploying an update

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; commit; tag `v1.1.0`.
2. Build the **release** APK signed with the same keystore as before.
3. Generate the manifest:
   ```bash
   python3 update-server/make_manifest.py app-release.apk --version-code 2 --version-name 1.1.0 \
     --url https://updates.example.com/awaken/awaken-1.1.0.apk \
     --changelog "New thing" --changelog "Another thing" > update.json
   ```
   (`apksigner` from Android SDK build-tools on your PATH fills `signerSha256`.)
4. Upload the APK **first**, then `update.json`. Any static HTTPS host works (GitHub Releases + Pages, S3/R2 + CDN, Netlify, your own server). Serve `update.json` with `Cache-Control: no-cache`.
5. In the app: Update center > enter the manifest URL > *Save and check*. To ship the URL in the build instead, set `DEFAULT_UPDATE_MANIFEST_URL` in `app/build.gradle.kts`.
6. Test on a device running the previous build before announcing it.

To make an update mandatory, set `"mandatory": true` or raise `minSupportedVersionCode`.

## Designing for V2 … V5

- **New native features** ship as APK updates; the update center carries them to users.
- **Staged rollout of a feature** in an installed build: add a value to `FeatureFlag` and gate the UI on `remote.flag(...)`; flip it from `config.json`. Defaults live in code so old configs keep working.
- **New remote-config fields**: add them to `RemoteConfig` and `RemoteConfigParser`, keep `schemaVersion` at 1 while the change is additive (old apps ignore unknown keys); bump it only for a breaking change, which makes older apps ignore the file instead of misreading it.
- **New dashboard widgets**: add to `DashboardWidget`, render in `HomeScreen`. Old builds drop unknown widget IDs.
- **Database changes**: bump `AwakenDatabase.version` and add a Room migration; `app/schemas/` (exported on build) records each version.
- **Manifest schema**: `UpdateManifestParser.SUPPORTED_SCHEMA` rejects manifests from the future instead of misreading them.
