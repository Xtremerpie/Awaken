package com.awaken.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY addedAt DESC, id DESC")
    suspend fun getAll(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT * FROM media WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): MediaEntity?

    @Query("SELECT * FROM media WHERE bundled = 1 LIMIT 1")
    suspend fun findBundled(): MediaEntity?

    @Insert
    suspend fun insert(item: MediaEntity): Long

    @Query("UPDATE media SET displayName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM media")
    suspend fun deleteAll()
}

@Dao
abstract class PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    abstract fun observeAllItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    abstract suspend fun getAllItems(): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    abstract suspend fun itemsFor(id: Long): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun getById(id: Long): PlaylistEntity?

    @Insert
    abstract suspend fun insert(p: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    abstract suspend fun clearItems(id: Long)

    @Query("UPDATE playlists SET name = :name, orderMode = :mode WHERE id = :id")
    abstract suspend fun update(id: Long, name: String, mode: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM playlists")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceItems(playlistId: Long, mediaIds: List<Long>) {
        clearItems(playlistId)
        insertItems(mediaIds.mapIndexed { i, m -> PlaylistItemEntity(playlistId, i, m) })
    }
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY kind, startMinutes, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE id = :id")
    fun observeById(id: Long): Flow<ScheduleEntity?>

    @Query("SELECT * FROM schedules WHERE isDefaultHourly = 1 LIMIT 1")
    suspend fun findDefaultHourly(): ScheduleEntity?

    @Insert
    suspend fun insert(s: ScheduleEntity): Long

    @Update
    suspend fun update(s: ScheduleEntity)

    @Query("UPDATE schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE schedules SET lastFiredTriggerAt = :t, lastPlaylistIndex = :index WHERE id = :id")
    suspend fun markFired(id: Long, t: Long, index: Int)

    @Query("UPDATE schedules SET mediaId = NULL WHERE mediaId = :mediaId")
    suspend fun clearMedia(mediaId: Long)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE firedAt >= :since ORDER BY firedAt DESC")
    fun observeSince(since: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY firedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(h: HistoryEntity): Long

    @Query("UPDATE history SET status = :status, detail = :detail WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, detail: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Keep the table bounded: drop everything but the newest [keep] rows. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY firedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(
    entities = [MediaEntity::class, PlaylistEntity::class, PlaylistItemEntity::class, ScheduleEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AwakenDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun playlists(): PlaylistDao
    abstract fun schedules(): ScheduleDao
    abstract fun history(): HistoryDao
}
