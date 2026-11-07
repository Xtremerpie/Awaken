package com.awaken.app.data.repo

import com.awaken.app.data.db.AwakenDatabase
import com.awaken.app.data.db.HistoryEntity
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.db.PlaylistEntity
import com.awaken.app.data.db.PlaylistItemEntity
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.SettingsStore
import com.awaken.core.OrderMode
import com.awaken.core.PlaybackOrder
import com.awaken.core.QuietHours
import com.awaken.core.ScheduleEngine
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleRepository(private val db: AwakenDatabase, private val settings: SettingsStore) {
    private val dao get() = db.schedules()

    /** Set by the container so any change re-arms AlarmManager. */
    var onChanged: suspend () -> Unit = {}

    fun observeAll(): Flow<List<ScheduleEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<ScheduleEntity?> = dao.observeById(id)
    suspend fun getAll(): List<ScheduleEntity> = dao.getAll()
    suspend fun get(id: Long): ScheduleEntity? = dao.getById(id)

    suspend fun save(s: ScheduleEntity): Long {
        val id = if (s.id == 0L) dao.insert(s) else { dao.update(s); s.id }
        onChanged()
        return id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) { dao.setEnabled(id, enabled); onChanged() }
    suspend fun delete(id: Long) { dao.delete(id); onChanged() }
    suspend fun markFired(id: Long, triggerAt: Long, playlistIndex: Int) = dao.markFired(id, triggerAt, playlistIndex)

    /** The only way the default hourly schedule's interval changes (Settings › Hourly interval). */
    suspend fun setDefaultHourlyInterval(minutes: Int) {
        val s = dao.findDefaultHourly() ?: return
        dao.update(s.copy(intervalMinutes = minutes.coerceIn(1, 1440)))
        onChanged()
    }

    suspend fun defaultHourly(): ScheduleEntity? = dao.findDefaultHourly()

    companion object {
        fun nextTrigger(s: ScheduleEntity, quiet: QuietHours, after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? =
            ScheduleEngine.nextTrigger(s.toSpec(), after, quiet)

        /** Earliest upcoming trigger across all enabled schedules, honouring the master switch. */
        fun nextOverall(all: List<ScheduleEntity>, cfg: AppSettings, after: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Pair<ScheduleEntity, ZonedDateTime>? {
            if (!cfg.masterEnabled) return null
            return all.mapNotNull { s -> nextTrigger(s, cfg.quietHours, after)?.let { s to it } }.minByOrNull { it.second.toInstant() }
        }
    }
}

class PlaylistRepository(private val db: AwakenDatabase) {
    private val dao get() = db.playlists()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()
    fun observeItems(): Flow<List<PlaylistItemEntity>> = dao.observeAllItems()
    suspend fun getPlaylists() = dao.getPlaylists()
    suspend fun itemsFor(id: Long) = dao.itemsFor(id)

    suspend fun create(name: String, mode: OrderMode, mediaIds: List<Long>): Long {
        val id = dao.insert(PlaylistEntity(name = name.trim().ifEmpty { "Playlist" }.take(60), orderMode = mode.name))
        dao.replaceItems(id, mediaIds)
        return id
    }

    suspend fun update(id: Long, name: String, mode: OrderMode, mediaIds: List<Long>) {
        dao.update(id, name.trim().ifEmpty { "Playlist" }.take(60), mode.name)
        dao.replaceItems(id, mediaIds)
    }

    suspend fun delete(id: Long) = dao.delete(id)
}

class HistoryRepository(private val db: AwakenDatabase) {
    private val dao get() = db.history()

    fun observeToday(): Flow<List<HistoryEntity>> =
        dao.observeSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    fun observeRecent(limit: Int = 300): Flow<List<HistoryEntity>> = dao.observeRecent(limit)

    suspend fun log(scheduleId: Long?, scheduleName: String, mediaTitle: String, status: String, detail: String = ""): Long {
        val id = dao.insert(HistoryEntity(scheduleId = scheduleId, scheduleName = scheduleName, mediaTitle = mediaTitle,
            firedAt = System.currentTimeMillis(), status = status, detail = detail))
        dao.trim(1000)
        return id
    }

    suspend fun setStatus(id: Long, status: String, detail: String = "") = dao.updateStatus(id, status, detail)
    suspend fun clear() = dao.clear()
}

/** What a trigger should actually play. */
data class ResolvedPlayback(val items: List<MediaEntity>, val lastPlaylistIndex: Int)

class MediaResolver(
    private val media: MediaRepository,
    private val playlists: PlaylistRepository,
) {
    /**
     * schedule.playlistId → playlist order (sequential/random) picking [ScheduleEntity.repeatCount] items;
     * schedule.mediaId → that file repeated; otherwise the default media. Returns null if nothing playable.
     */
    suspend fun resolve(s: ScheduleEntity): ResolvedPlayback? {
        val loops = s.repeatCount.coerceIn(1, 50)
        val pid = s.playlistId
        if (pid != null) {
            val ids = playlists.itemsFor(pid).map { it.mediaId }
            val byId = media.getByIds(ids).associateBy { it.id }
            val items = ids.mapNotNull { byId[it] }
            if (items.isNotEmpty()) {
                val mode = runCatching { OrderMode.valueOf(s.orderMode) }.getOrDefault(OrderMode.SEQUENTIAL)
                var last = s.lastPlaylistIndex.takeIf { it in items.indices }
                val picks = buildList {
                    repeat(loops) {
                        val i = PlaybackOrder.nextIndex(mode, items.size, last)
                        add(items[i]); last = i
                    }
                }
                return ResolvedPlayback(picks, last ?: -1)
            }
        }
        val single = s.mediaId?.let { media.get(it) } ?: media.defaultMedia() ?: return null
        return ResolvedPlayback(List(loops) { single }, -1)
    }
}
