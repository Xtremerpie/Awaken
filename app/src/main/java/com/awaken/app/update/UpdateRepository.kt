package com.awaken.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.awaken.app.AppLog
import com.awaken.app.data.settings.SettingsStore
import com.awaken.app.notifications.Notifications
import com.awaken.core.update.ApkVerifier
import com.awaken.core.update.ArchiveFacts
import com.awaken.core.update.Hashing
import com.awaken.core.update.ManifestParse
import com.awaken.core.update.UpdateDecision
import com.awaken.core.update.UpdateManifest
import com.awaken.core.update.UpdateManifestParser
import com.awaken.core.update.UpdateState
import com.awaken.core.update.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object NotConfigured : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val checkedAt: Long) : UpdateUi
    data class Available(val manifest: UpdateManifest, val mandatory: Boolean) : UpdateUi
    data class Downloading(val manifest: UpdateManifest, val bytes: Long) : UpdateUi
    data object Verifying : UpdateUi
    /** Downloaded and fully verified. Nothing is installed yet. */
    data class ReadyToInstall(val manifest: UpdateManifest, val mandatory: Boolean) : UpdateUi
    /** The system installer was opened. The app has NOT been updated until Android reports the new version. */
    data class AwaitingAndroid(val manifest: UpdateManifest) : UpdateUi
    /** Android reported the new version as installed. */
    data class InstalledConfirmed(val versionName: String) : UpdateUi
    data class Failed(val message: String, val manifest: UpdateManifest? = null, val mandatory: Boolean = false) : UpdateUi
}

class UpdateRepository(private val context: Context, private val settings: SettingsStore) {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state

    private val mutex = Mutex()
    private var verifiedFile: File? = null
    private var expectedVersionCode: Long = 0
    private var expectedVersionName: String = ""

    private val pm get() = context.packageManager

    val installedVersionCode: Long
        get() = installedInfo()?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    val installedVersionName: String get() = installedInfo()?.versionName.orEmpty()

    private fun installedInfo(): PackageInfo? = try { pm.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }

    /** Fetches the manifest and decides: up to date, optional, mandatory, or invalid. */
    suspend fun check(): UpdateUi = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext _state.value
        try {
            val cfg = settings.current()
            if (cfg.updateManifestUrl.isBlank()) { _state.value = UpdateUi.NotConfigured; return@withContext _state.value }
            _state.value = UpdateUi.Checking
            try {
                val text = SecureHttp.getText(cfg.updateManifestUrl)
                val raw = JsonConvert.toMap(JSONObject(text))
                val manifest = when (val p = UpdateManifestParser.parse(raw)) {
                    is ManifestParse.Ok -> p.manifest
                    is ManifestParse.Bad -> { _state.value = UpdateUi.Failed("The update manifest is not valid: ${p.reason}"); return@withContext _state.value }
                }
                settings.setLastUpdateCheck(System.currentTimeMillis())
                val installed = installedVersionCode
                _state.value = when (val d = UpdateDecision.evaluate(context.packageName, installed, manifest)) {
                    UpdateState.UpToDate -> {
                        if (!UpdateDecision.isLocked(installed, cfg.requiredVersionCode)) settings.setRequiredVersionCode(0)
                        UpdateUi.UpToDate(System.currentTimeMillis())
                    }
                    is UpdateState.Available -> {
                        val required = UpdateDecision.requiredVersionCode(d)
                        if (required > 0) settings.setRequiredVersionCode(required)
                        if (cfg.autoCheckUpdates) Notifications.postUpdateAvailable(context, manifest.versionName, d.mandatory)
                        UpdateUi.Available(manifest, d.mandatory)
                    }
                    is UpdateState.Invalid -> UpdateUi.Failed("The update server reply was rejected: ${d.reason}")
                }
            } catch (e: SecureHttp.HttpFailure) {
                AppLog.w("Update check failed: ${e.message}")
                _state.value = UpdateUi.Failed(e.message ?: "Update check failed")
            } catch (e: org.json.JSONException) {
                _state.value = UpdateUi.Failed("The update server did not return valid JSON.")
            } catch (e: Exception) {
                AppLog.e("Update check crashed", e)
                _state.value = UpdateUi.Failed("Update check failed: ${e.message}")
            }
            _state.value
        } finally {
            mutex.unlock()
        }
    }

    /** Downloads the APK, then verifies size, SHA-256, package, version and signing certificate. */
    suspend fun downloadAndVerify(manifest: UpdateManifest, mandatory: Boolean) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "awaken-${manifest.versionCode}.apk.part")
        val apk = File(dir, "awaken-${manifest.versionCode}.apk")
        _state.value = UpdateUi.Downloading(manifest, 0)
        try {
            val sha = SecureHttp.download(manifest.apkUrl, part, manifest.sizeBytes) { _state.value = UpdateUi.Downloading(manifest, it) }
            _state.value = UpdateUi.Verifying
            if (!part.renameTo(apk)) throw SecureHttp.HttpFailure("Could not store the download", false)

            val facts = archiveFacts(apk, sha) ?: run {
                apk.delete()
                _state.value = UpdateUi.Failed("The downloaded file is not a readable Android package.", manifest, mandatory)
                return@withContext
            }
            val installedSigners = signerFingerprints(installedInfoWithSigners())
            when (val v = ApkVerifier.verify(manifest, facts, context.packageName, installedVersionCode, installedSigners)) {
                VerifyResult.Ok -> {
                    verifiedFile = apk
                    expectedVersionCode = manifest.versionCode
                    expectedVersionName = manifest.versionName
                    _state.value = UpdateUi.ReadyToInstall(manifest, mandatory)
                }
                is VerifyResult.Fail -> {
                    apk.delete()
                    AppLog.w("Update rejected: ${v.reason}")
                    _state.value = UpdateUi.Failed("Update rejected, nothing was installed. ${v.reason}", manifest, mandatory)
                }
            }
        } catch (e: SecureHttp.HttpFailure) {
            part.delete()
            _state.value = UpdateUi.Failed(e.message ?: "Download failed", manifest, mandatory)
        } catch (e: Exception) {
            part.delete()
            AppLog.e("Update download crashed", e)
            _state.value = UpdateUi.Failed("Download failed: ${e.message}", manifest, mandatory)
        }
    }

    fun canRequestInstall(): Boolean = Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()

    /** Settings screen where the user allows AWAKEN to hand APKs to the installer. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The official Android package installer, pointed at the verified file. Null if nothing verified is staged. */
    fun installIntent(): Intent? {
        val file = verifiedFile?.takeIf { it.exists() } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun markInstallerOpened(manifest: UpdateManifest) { _state.value = UpdateUi.AwaitingAndroid(manifest) }

    /**
     * Call whenever the app returns to the foreground. The only thing that counts as "installed" is
     * Android reporting a version code at or above the expected one; anything else is reported honestly.
     */
    suspend fun recheckInstalled() {
        val installed = installedVersionCode
        val cfg = settings.current()
        if (cfg.requiredVersionCode > 0 && installed >= cfg.requiredVersionCode) settings.setRequiredVersionCode(0)
        val s = _state.value
        if (s is UpdateUi.AwaitingAndroid) {
            if (installed >= s.manifest.versionCode) {
                _state.value = UpdateUi.InstalledConfirmed(installedVersionName)
                File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            } else {
                _state.value = UpdateUi.Failed(
                    "Android has not installed the update (still on version ${installedVersionName}). It was cancelled or blocked; you can try again.",
                    s.manifest, cfg.requiredVersionCode > 0,
                )
            }
        }
    }

    private fun installedInfoWithSigners(): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    } catch (_: Exception) { null }

    private fun archiveFacts(file: File, sha: String): ArchiveFacts? {
        val info: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) { null }
        if (info == null) return null
        return ArchiveFacts(
            sizeBytes = file.length(),
            sha256 = sha,
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signerSha256 = signerFingerprints(info),
        )
    }

    private fun signerFingerprints(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return sigs.orEmpty().map { Hashing.sha256Hex(it.toByteArray()) }.toSet()
    }
}
