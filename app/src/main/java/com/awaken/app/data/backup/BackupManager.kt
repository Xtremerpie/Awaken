package com.awaken.app.data.backup

import android.content.Context
import android.net.Uri
import com.awaken.app.data.db.AwakenDatabase
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.db.PlaylistEntity
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.DndBehavior
import com.awaken.app.data.settings.SettingsStore
import com.awaken.app.data.settings.StartupBehavior
import com.awaken.core.update.SecureUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit, user-driven backup to a file the user picks (Storage Access Framework). Cloud backup is off.
 * The file holds settings, schedules, playlists and *references* to media; the media files themselves are
 * not copied, so they must still exist (or be re-imported) after a restore.
 */
class BackupManager(
    private val context: Context,
    private val db: AwakenDatabase,
    private val settings: SettingsStore,
) {
    companion object { const val FORMAT = "awaken-backup"; const val VERSION = 1 }

    class BackupException(message: String) : Exception(message)

    suspend fun export(destination: Uri) = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val root = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("createdAt", System.currentTimeMillis())
            .put("settings", settingsJson(cfg))
            .put("media", JSONArray().also { a -> db.media().getAll().forEach { a.put(mediaJson(it)) } })
            .put("playlists", JSONArray().also { a ->
                val items = db.playlists().getAllItems().groupBy { it.playlistId }
                db.playlists().getPlaylists().forEach { p ->
                    a.put(JSONObject().put("id", p.id).put("name", p.name).put("orderMode", p.orderMode)
                        .put("items", JSONArray(items[p.id].orEmpty().sortedBy { it.position }.map { it.mediaId })))
                }
            })
            .put("schedules", JSONArray().also { a -> db.schedules().getAll().forEach { a.put(scheduleJson(it)) } })
        val out = context.contentResolver.openOutputStream(destination, "wt") ?: throw BackupException("Cannot write to that location")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    /** Validates everything first, then replaces schedules/playlists/settings. Returns a summary line. */
    suspend fun restore(source: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw BackupException("Cannot read that file")
        if (text.length > 5_000_000) throw BackupException("That file is too large to be an AWAKEN backup")
        val root = try { JSONObject(text) } catch (_: Exception) { throw BackupException("That is not an AWAKEN backup") }
        if (root.optString("format") != FORMAT) throw BackupException("That is not an AWAKEN backup")
        if (root.optInt("version", 0) !in 1..VERSION) throw BackupException("This backup was made by a newer AWAKEN; update the app first")

        // Parse into memory before touching the database, so a bad file changes nothing.
        val mediaIn = root.optJSONArray("media").toObjects().map { mediaFrom(it) }
        val playlistsIn = root.optJSONArray("playlists").toObjects()
        val schedulesIn = root.optJSONArray("schedules").toObjects().map { scheduleFrom(it) }

        val currentBundled = db.media().findBundled()
        val idMap = HashMap<Long, Long>()
        db.media().getAll().filter { !it.bundled }.forEach { db.media().delete(it.id) }
        db.schedules().deleteAll()
        db.playlists().deleteAll()

        mediaIn.forEach { (oldId, m) ->
            if (m.bundled) {
                if (currentBundled != null) idMap[oldId] = currentBundled.id
            } else if (db.media().findByUri(m.uri) == null) {
                idMap[oldId] = db.media().insert(m.copy(id = 0))
            }
        }
        playlistsIn.forEach { p ->
            val newId = db.playlists().insert(PlaylistEntity(name = p.optString("name", "Playlist"), orderMode = p.optString("orderMode", "SEQUENTIAL")))
            val ids = p.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { idMap[a.getLong(it)] } }.orEmpty()
            db.playlists().replaceItems(newId, ids)
            idMap[-1000 - p.getLong("id")] = newId // playlists share the map under a negative key space
        }
        schedulesIn.forEach { s ->
            db.schedules().insert(
                s.copy(
                    id = 0,
                    mediaId = s.mediaId?.let { idMap[it] },
                    playlistId = s.playlistId?.let { idMap[-1000 - it] },
                    lastFiredTriggerAt = 0,
                )
            )
        }
        root.optJSONObject("settings")?.let { applySettings(it, idMap) }
        "Restored ${schedulesIn.size} schedule(s), ${playlistsIn.size} playlist(s) and ${mediaIn.count { !it.second.bundled }} media reference(s)."
    }

    // ----------------------------------------------------------------- JSON mapping

    private fun JSONArray?.toObjects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun settingsJson(c: AppSettings) = JSONObject()
        .put("masterEnabled", c.masterEnabled).put("defaultVolume", c.defaultVolume.toDouble())
        .put("defaultDurationSec", c.defaultDurationSec).put("defaultSnoozeMinutes", c.defaultSnoozeMinutes)
        .put("quietEnabled", c.quietEnabled).put("quietStartMinutes", c.quietStartMinutes).put("quietEndMinutes", c.quietEndMinutes)
        .put("dnd", c.dnd.name).put("themeId", c.themeId).put("vibration", c.vibration).put("hapticsEnabled", c.hapticsEnabled)
        .put("gradualVolume", c.gradualVolume).put("gradualSeconds", c.gradualSeconds)
        .put("fadeInMs", c.fadeInMs).put("fadeOutMs", c.fadeOutMs)
        .put("startup", c.startup.name).put("showSplash", c.showSplash)
        .put("defaultMediaId", c.defaultMediaId)
        .put("autoCheckUpdates", c.autoCheckUpdates).put("updateManifestUrl", c.updateManifestUrl)
        .put("remoteConfigEnabled", c.remoteConfigEnabled).put("remoteConfigUrl", c.remoteConfigUrl)

    private suspend fun applySettings(j: JSONObject, idMap: Map<Long, Long>) {
        val s = settings
        if (j.has("masterEnabled")) s.setMasterEnabled(j.getBoolean("masterEnabled"))
        if (j.has("defaultVolume")) s.setDefaultVolume(j.getDouble("defaultVolume").toFloat())
        if (j.has("defaultDurationSec")) s.setDefaultDurationSec(j.getInt("defaultDurationSec"))
        if (j.has("defaultSnoozeMinutes")) s.setDefaultSnoozeMinutes(j.getInt("defaultSnoozeMinutes"))
        if (j.has("quietEnabled")) s.setQuietEnabled(j.getBoolean("quietEnabled"))
        if (j.has("quietStartMinutes")) s.setQuietStart(j.getInt("quietStartMinutes"))
        if (j.has("quietEndMinutes")) s.setQuietEnd(j.getInt("quietEndMinutes"))
        j.optString("dnd").let { v -> runCatching { DndBehavior.valueOf(v) }.getOrNull()?.let { s.setDnd(it) } }
        if (j.has("themeId")) s.setThemeId(j.getString("themeId"))
        if (j.has("vibration")) s.setVibration(j.getBoolean("vibration"))
        if (j.has("hapticsEnabled")) s.setHaptics(j.getBoolean("hapticsEnabled"))
        if (j.has("gradualVolume")) s.setGradualVolume(j.getBoolean("gradualVolume"))
        if (j.has("gradualSeconds")) s.setGradualSeconds(j.getInt("gradualSeconds"))
        if (j.has("fadeInMs")) s.setFadeInMs(j.getInt("fadeInMs"))
        if (j.has("fadeOutMs")) s.setFadeOutMs(j.getInt("fadeOutMs"))
        j.optString("startup").let { v -> runCatching { StartupBehavior.valueOf(v) }.getOrNull()?.let { s.setStartup(it) } }
        if (j.has("showSplash")) s.setShowSplash(j.getBoolean("showSplash"))
        if (j.has("defaultMediaId")) idMap[j.getLong("defaultMediaId")]?.let { s.setDefaultMediaId(it) }
        if (j.has("autoCheckUpdates")) s.setAutoCheckUpdates(j.getBoolean("autoCheckUpdates"))
        if (j.has("remoteConfigEnabled")) s.setRemoteConfigEnabled(j.getBoolean("remoteConfigEnabled"))
        // Endpoints from a file are only accepted if they are public HTTPS addresses (or empty).
        j.optString("updateManifestUrl").takeIf { it.isEmpty() || SecureUrl.isAllowed(it) }?.let { s.setUpdateManifestUrl(it) }
        j.optString("remoteConfigUrl").takeIf { it.isEmpty() || SecureUrl.isAllowed(it) }?.let { s.setRemoteConfigUrl(it) }
    }

    private fun mediaJson(m: MediaEntity) = JSONObject().put("id", m.id).put("uri", m.uri).put("displayName", m.displayName)
        .put("mimeType", m.mimeType).put("isVideo", m.isVideo).put("durationMs", m.durationMs).put("sizeBytes", m.sizeBytes)
        .put("addedAt", m.addedAt).put("bundled", m.bundled)

    private fun mediaFrom(j: JSONObject): Pair<Long, MediaEntity> = j.getLong("id") to MediaEntity(
        uri = j.getString("uri"), displayName = j.getString("displayName").take(80), mimeType = j.optString("mimeType", "audio/*"),
        isVideo = j.optBoolean("isVideo"), durationMs = j.optLong("durationMs"), sizeBytes = j.optLong("sizeBytes"),
        addedAt = j.optLong("addedAt", System.currentTimeMillis()), bundled = j.optBoolean("bundled"),
    )

    private fun scheduleJson(s: ScheduleEntity) = JSONObject().put("id", s.id).put("name", s.name).put("kind", s.kind)
        .put("enabled", s.enabled).put("startMinutes", s.startMinutes).put("endMinutes", s.endMinutes)
        .put("intervalMinutes", s.intervalMinutes).put("daysMask", s.daysMask)
        .put("startEpochDay", s.startEpochDay ?: JSONObject.NULL).put("endEpochDay", s.endEpochDay ?: JSONObject.NULL)
        .put("mediaId", s.mediaId ?: JSONObject.NULL).put("playlistId", s.playlistId ?: JSONObject.NULL)
        .put("orderMode", s.orderMode).put("volume", s.volume.toDouble()).put("durationSec", s.durationSec)
        .put("repeatCount", s.repeatCount).put("snoozeMinutes", s.snoozeMinutes)
        .put("respectQuietHours", s.respectQuietHours).put("gradualVolume", s.gradualVolume)
        .put("isDefaultHourly", s.isDefaultHourly)

    private fun scheduleFrom(j: JSONObject): ScheduleEntity {
        fun optLong(k: String): Long? = if (j.isNull(k)) null else j.getLong(k)
        return ScheduleEntity(
            name = j.getString("name").take(60),
            kind = j.optString("kind", "RECURRING"),
            enabled = j.optBoolean("enabled", true),
            startMinutes = j.optInt("startMinutes").coerceIn(0, 1439),
            endMinutes = j.optInt("endMinutes", -1).let { if (it < 0) -1 else it.coerceIn(0, 1439) },
            intervalMinutes = j.optInt("intervalMinutes", 60).coerceIn(1, 1440),
            daysMask = j.optInt("daysMask") and 0x7F,
            startEpochDay = optLong("startEpochDay"), endEpochDay = optLong("endEpochDay"),
            mediaId = optLong("mediaId"), playlistId = optLong("playlistId"),
            orderMode = j.optString("orderMode", "SEQUENTIAL"),
            volume = j.optDouble("volume", -1.0).toFloat().let { if (it < 0f) -1f else it.coerceIn(0f, 1f) },
            durationSec = j.optInt("durationSec").coerceIn(0, 6 * 3600),
            repeatCount = j.optInt("repeatCount", 1).coerceIn(1, 50),
            snoozeMinutes = j.optInt("snoozeMinutes", 10).coerceIn(1, 120),
            respectQuietHours = j.optBoolean("respectQuietHours", true),
            gradualVolume = j.optBoolean("gradualVolume"),
            isDefaultHourly = j.optBoolean("isDefaultHourly"),
        )
    }
}
