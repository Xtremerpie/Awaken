package com.awaken.app.data.remote

import android.content.Context
import com.awaken.app.AppLog
import com.awaken.app.BuildConfig
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.repo.MediaRepository
import com.awaken.app.data.settings.SettingsStore
import com.awaken.app.update.JsonConvert
import com.awaken.app.update.SecureHttp
import com.awaken.core.remote.FeaturedMedia
import com.awaken.core.remote.RemoteConfig
import com.awaken.core.remote.RemoteConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Remote configuration: declarative JSON that switches on/off or re-skins capabilities already inside the
 * installed APK. It is parsed and sanitised by [RemoteConfigParser]; nothing in it is ever executed.
 */
class RemoteConfigRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val media: MediaRepository,
) {
    private val _config = MutableStateFlow(RemoteConfig.EMPTY)
    val config: StateFlow<RemoteConfig> = _config

    /** Applies the last good config immediately at start-up, so the app is correct even offline. */
    suspend fun loadCached() {
        val cached = settings.current().remoteConfigJson
        if (cached.isBlank()) return
        parse(cached)?.let { _config.value = it }
    }

    /** Fetches and applies a new config. Returns a short status line for the UI. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        if (!cfg.remoteConfigEnabled) return@withContext "Remote content is turned off."
        if (cfg.remoteConfigUrl.isBlank()) return@withContext "No remote configuration address is set."
        try {
            val text = SecureHttp.getText(cfg.remoteConfigUrl)
            val parsed = parse(text) ?: return@withContext "The remote configuration was ignored (unsupported or invalid)."
            settings.setRemoteConfigJson(text)
            _config.value = parsed
            "Remote configuration applied (revision ${parsed.revision})."
        } catch (e: SecureHttp.HttpFailure) {
            AppLog.w("Remote config fetch failed: ${e.message}")
            e.message ?: "Could not fetch remote configuration."
        } catch (e: Exception) {
            AppLog.e("Remote config failed", e)
            "Could not read remote configuration."
        }
    }

    private fun parse(text: String): RemoteConfig? = try {
        RemoteConfigParser.parse(JsonConvert.toMap(JSONObject(text)), BuildConfig.CONFIG_SCHEMA_VERSION)
    } catch (_: Exception) { null }

    /**
     * Downloads a featured item the user explicitly chose, verifies its SHA-256, and adds it to the library.
     * Nothing is downloaded automatically.
     */
    suspend fun downloadFeatured(item: FeaturedMedia, onProgress: (Long) -> Unit): MediaEntity = withContext(Dispatchers.IO) {
        val ext = item.url.substringBefore('?').substringAfterLast('.', "mp3").lowercase()
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        val dest = File(dir, "remote_${item.id}.$ext")
        val sha = SecureHttp.download(item.url, dest, item.sizeBytes, onProgress)
        if (!sha.equals(item.sha256, ignoreCase = true)) {
            dest.delete()
            throw SecureHttp.HttpFailure("Checksum mismatch; the file was discarded.", false)
        }
        try {
            media.importLocalFile(dest, item.title, bundled = false)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }
}
