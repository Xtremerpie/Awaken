package com.awaken.app.data.repo

import android.content.Context
import com.awaken.app.AppLog
import com.awaken.app.R
import com.awaken.app.data.db.AwakenDatabase
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.settings.SettingsStore
import com.awaken.core.ScheduleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First installation: imports the bundled Video-26260.mp4 as the default media and creates an enabled
 * hourly schedule that plays it. Idempotent: every step checks what already exists.
 */
class FirstRunSeeder(
    private val context: Context,
    private val db: AwakenDatabase,
    private val settings: SettingsStore,
    private val media: MediaRepository,
) {
    private val lock = Mutex()

    companion object {
        const val DEFAULT_FILE = "default_video_26260.mp4"
        const val DEFAULT_TITLE = "Video-26260"
        fun mediaDir(context: Context) = File(context.filesDir, "media").apply { mkdirs() }
    }

    suspend fun ensureSeeded() = lock.withLock {
        withContext(Dispatchers.IO) {
            var bundled = db.media().findBundled()
            if (bundled == null && !settings.current().seeded) {
                val dest = File(mediaDir(context), DEFAULT_FILE)
                if (!dest.exists() || dest.length() == 0L) {
                    context.resources.openRawResource(R.raw.video_26260).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                }
                bundled = media.importLocalFile(dest, DEFAULT_TITLE, bundled = true)
                AppLog.i("Imported bundled default media (id ${bundled.id})")
            }
            if (bundled != null && settings.current().defaultMediaId < 0) settings.setDefaultMediaId(bundled.id)

            if (!settings.current().seeded) {
                if (db.schedules().findDefaultHourly() == null) {
                    db.schedules().insert(
                        ScheduleEntity(
                            name = "Hourly playback",
                            kind = ScheduleKind.RECURRING.name,
                            enabled = true,
                            startMinutes = 0,
                            endMinutes = -1,
                            intervalMinutes = 60,
                            mediaId = null, // null = whatever the default media is, so changing it updates the hourly schedule
                            respectQuietHours = true,
                            isDefaultHourly = true,
                        )
                    )
                    AppLog.i("Created default hourly schedule")
                }
                settings.setSeeded(true)
            }
        }
    }
}
