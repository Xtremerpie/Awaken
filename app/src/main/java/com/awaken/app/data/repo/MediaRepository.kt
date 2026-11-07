package com.awaken.app.data.repo

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.awaken.app.AppLog
import com.awaken.app.data.db.AwakenDatabase
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class ImportReport(val imported: List<MediaEntity>, val rejected: List<Pair<String, String>>)

class MediaRepository(
    private val context: Context,
    private val db: AwakenDatabase,
    private val settings: SettingsStore,
) {
    companion object {
        val ALLOWED_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "mp4", "m4v", "3gp", "ogg", "oga", "opus", "flac", "mkv", "webm")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "3gp", "mkv", "webm")
        const val MAX_BYTES = 1L * 1024 * 1024 * 1024
        /** MIME types picked in the SAF chooser. */
        val PICKER_MIME_TYPES = arrayOf("audio/*", "video/*")
    }

    private val dao get() = db.media()

    fun observeAll(): Flow<List<MediaEntity>> = dao.observeAll()
    suspend fun getAll(): List<MediaEntity> = dao.getAll()
    suspend fun get(id: Long): MediaEntity? = dao.getById(id)
    suspend fun getByIds(ids: List<Long>): List<MediaEntity> = if (ids.isEmpty()) emptyList() else dao.getByIds(ids)

    /**
     * Validates and imports media chosen through the Storage Access Framework. Every file is checked
     * before it is stored: it must be readable, have an allowed extension and an audio/video type, be a
     * sane size, and decode far enough to report a duration. Rejections carry a reason for the UI.
     */
    suspend fun importUris(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        val ok = mutableListOf<MediaEntity>()
        val bad = mutableListOf<Pair<String, String>>()
        for (uri in uris) {
            val label = displayName(uri) ?: uri.lastPathSegment ?: "file"
            try {
                ok += importOne(uri, label)
            } catch (e: ImportException) {
                bad += label to (e.message ?: "Not supported")
            } catch (e: Exception) {
                AppLog.e("Import failed for $label", e)
                bad += label to "Could not be read"
            }
        }
        ImportReport(ok, bad)
    }

    private suspend fun importOne(uri: Uri, name: String): MediaEntity {
        // Keep access across restarts. Documents from the picker support this; anything else is refused.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            throw ImportException("Access cannot be kept for this file; choose it from the file picker")
        }
        dao.findByUri(uri.toString())?.let { throw ImportException("Already in your library") }

        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val looksMedia = mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/ogg"
        if (ext !in ALLOWED_EXTENSIONS && !looksMedia) throw ImportException("Unsupported file type")

        val size = querySize(uri)
        if (size == 0L) throw ImportException("File is empty")
        if (size > MAX_BYTES) throw ImportException("File is larger than 1 GB")

        val meta = readMetadata { it.setDataSource(context, uri) }
            ?: throw ImportException("File is damaged or cannot be decoded")
        val isVideo = meta.hasVideo
        val entity = MediaEntity(
            uri = uri.toString(),
            displayName = name.substringBeforeLast('.', name).ifBlank { name },
            mimeType = mime.ifEmpty { if (isVideo) "video/*" else "audio/*" },
            isVideo = isVideo,
            durationMs = meta.durationMs,
            sizeBytes = size,
            addedAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        val saved = entity.copy(id = id)
        if (settings.current().defaultMediaId < 0) settings.setDefaultMediaId(id)
        return saved
    }

    /** Adds a file that lives in app-private storage (bundled default or a verified remote download). */
    suspend fun importLocalFile(file: File, title: String, bundled: Boolean): MediaEntity = withContext(Dispatchers.IO) {
        val meta = readMetadata { it.setDataSource(file.absolutePath) }
            ?: throw ImportException("Downloaded file cannot be decoded")
        val entity = MediaEntity(
            uri = Uri.fromFile(file).toString(),
            displayName = title,
            mimeType = if (meta.hasVideo) "video/mp4" else "audio/*",
            isVideo = meta.hasVideo,
            durationMs = meta.durationMs,
            sizeBytes = file.length(),
            addedAt = System.currentTimeMillis(),
            bundled = bundled,
        )
        entity.copy(id = dao.insert(entity))
    }

    suspend fun rename(id: Long, newName: String) {
        val n = newName.trim().take(80)
        if (n.isNotEmpty()) dao.rename(id, n)
    }

    /** Removes the library entry, its stored permission/file, and any schedule reference to it. */
    suspend fun delete(item: MediaEntity) = withContext(Dispatchers.IO) {
        dao.delete(item.id)
        db.schedules().clearMedia(item.id)
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "file") {
            runCatching { File(requireNotNull(uri.path)).delete() }
        } else {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (settings.current().defaultMediaId == item.id) {
            settings.setDefaultMediaId(dao.getAll().firstOrNull()?.id ?: -1)
        }
    }

    /** True when the stored URI can still be opened (file removed or permission lost → false). */
    suspend fun isAccessible(item: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** The media used when a schedule has none of its own: chosen default → bundled → newest item. */
    suspend fun defaultMedia(): MediaEntity? {
        val id = settings.current().defaultMediaId
        return (if (id >= 0) dao.getById(id) else null) ?: dao.findBundled() ?: dao.getAll().firstOrNull()
    }

    private data class Meta(val durationMs: Long, val hasVideo: Boolean)

    private fun readMetadata(source: (MediaMetadataRetriever) -> Unit): Meta? {
        val r = MediaMetadataRetriever()
        return try {
            source(r)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (dur <= 0 || (!hasAudio && !hasVideo)) null else Meta(dur, hasVideo)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L).let { if (it < 0) 1L else it } // unknown size: let the decoder decide

    class ImportException(message: String) : Exception(message)
}
