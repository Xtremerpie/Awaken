package com.awaken.app.di

import android.app.Application
import androidx.room.Room
import com.awaken.app.AppLog
import com.awaken.app.data.backup.BackupManager
import com.awaken.app.data.db.AwakenDatabase
import com.awaken.app.data.remote.RemoteConfigRepository
import com.awaken.app.data.repo.FirstRunSeeder
import com.awaken.app.data.repo.HistoryRepository
import com.awaken.app.data.repo.MediaRepository
import com.awaken.app.data.repo.MediaResolver
import com.awaken.app.data.repo.PlaylistRepository
import com.awaken.app.data.repo.ScheduleRepository
import com.awaken.app.data.settings.SettingsStore
import com.awaken.app.notifications.Notifications
import com.awaken.app.playback.PlaybackService
import com.awaken.app.scheduling.AlarmScheduler
import com.awaken.app.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hand-written dependency container (constructor injection, one instance per process). Chosen over a
 * code-generating DI framework to keep the build small and every dependency explicit and testable.
 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AwakenDatabase = Room.databaseBuilder(app, AwakenDatabase::class.java, "awaken.db").build()
    val settings = SettingsStore(app)
    val media = MediaRepository(app, db, settings)
    val playlists = PlaylistRepository(db)
    val schedules = ScheduleRepository(db, settings)
    val history = HistoryRepository(db)
    val resolver = MediaResolver(media, playlists)
    val alarmScheduler = AlarmScheduler(app, schedules, settings)
    val updates = UpdateRepository(app, settings)
    val remote = RemoteConfigRepository(app, settings, media)
    val firstRun = FirstRunSeeder(app, db, settings, media)
    val backup = BackupManager(app, db, settings)

    private val _ready = MutableStateFlow(false)
    /** True once first-run seeding and alarm registration are done. The splash screen waits for this. */
    val ready: StateFlow<Boolean> = _ready

    init {
        schedules.onChanged = { alarmScheduler.rescheduleAll() }
        appScope.launch {
            try {
                firstRun.ensureSeeded()
                alarmScheduler.rescheduleAll()
                remote.loadCached()
            } catch (e: Exception) {
                AppLog.e("Start-up initialisation failed", e)
            } finally {
                _ready.value = true
            }
            remote.refresh()
        }
    }

    /** Settings that change when schedules fire (master switch, quiet hours) must re-arm the alarms. */
    suspend fun applySettingsChange() = alarmScheduler.rescheduleAll()

    /** Factory reset: stops playback, removes every alarm, wipes data and settings, then re-seeds the defaults. */
    suspend fun resetApplication() = withContext(Dispatchers.IO) {
        PlaybackService.requestStop(app)
        schedules.getAll().forEach { alarmScheduler.cancel(it.id) }
        db.schedules().deleteAll()
        db.playlists().deleteAll()
        db.media().deleteAll()
        db.history().clear()
        File(app.filesDir, "media").deleteRecursively()
        File(app.cacheDir, "updates").deleteRecursively()
        AppLog.clear()
        settings.clearAll()
        Notifications.cancelAlert(app)
        firstRun.ensureSeeded()
        alarmScheduler.rescheduleAll()
        remote.loadCached()
    }
}
