package com.awaken.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.awaken.core.ScheduleKind
import com.awaken.core.ScheduleSpec
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** content:// URI with a persisted read permission, or file:// for the bundled default. */
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAt: Long,
    val bundled: Boolean = false,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val orderMode: String = "SEQUENTIAL",
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaEntity::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class PlaylistItemEntity(val playlistId: Long, val position: Int, val mediaId: Long)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String = ScheduleKind.RECURRING.name,
    val enabled: Boolean = true,
    val startMinutes: Int = 0,
    /** -1 = no end time (runs until 23:59). */
    val endMinutes: Int = -1,
    val intervalMinutes: Int = 60,
    /** Bit 0 = Monday … bit 6 = Sunday. 0 = every day. */
    val daysMask: Int = 0,
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
    /** Single media item; ignored when [playlistId] is set. Both null → the default media. */
    val mediaId: Long? = null,
    val playlistId: Long? = null,
    val orderMode: String = "SEQUENTIAL",
    /** 0..1, or -1 to use the default volume from Settings. */
    val volume: Float = -1f,
    /** Max playback seconds per trigger; 0 = play the item(s) in full. */
    val durationSec: Int = 0,
    /** How many items play per trigger. */
    val repeatCount: Int = 1,
    val snoozeMinutes: Int = 10,
    val respectQuietHours: Boolean = true,
    val gradualVolume: Boolean = false,
    val lastFiredTriggerAt: Long = 0,
    val lastPlaylistIndex: Int = -1,
    /** The schedule created on first install. */
    val isDefaultHourly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    @get:Ignore
    val scheduleKind: ScheduleKind get() = runCatching { ScheduleKind.valueOf(kind) }.getOrDefault(ScheduleKind.RECURRING)

    fun toSpec() = ScheduleSpec(
        id = id,
        kind = scheduleKind,
        enabled = enabled,
        start = LocalTime.of(startMinutes / 60, startMinutes % 60),
        end = if (endMinutes < 0) null else LocalTime.of(endMinutes / 60, endMinutes % 60),
        intervalMinutes = intervalMinutes,
        days = DaysMask.toSet(daysMask),
        startDate = startEpochDay?.let(LocalDate::ofEpochDay),
        endDate = endEpochDay?.let(LocalDate::ofEpochDay),
        respectQuietHours = respectQuietHours,
    )
}

object DaysMask {
    fun toSet(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    fun fromSet(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
}

@Entity(tableName = "history", indices = [Index("firedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long?,
    val scheduleName: String,
    val mediaTitle: String,
    val firedAt: Long,
    /** STARTED, COMPLETED, STOPPED, SNOOZED, SKIPPED, INTERRUPTED, FAILED */
    val status: String,
    val detail: String = "",
)
