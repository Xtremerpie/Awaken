package com.awaken.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.awaken.app.AppLog
import com.awaken.app.AwakenApp
import com.awaken.app.MainActivity
import com.awaken.app.R
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.di.AppContainer
import com.awaken.app.notifications.Notifications
import com.awaken.core.PlaybackPlanner
import com.awaken.core.ScheduleKind
import com.awaken.core.VolumeRamp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground media service. Everything that plays — scheduled triggers and manual playback — goes through
 * here, so there is exactly one player, one audio-focus owner and one place that prevents overlapping playback.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY_SCHEDULE = "com.awaken.app.PLAY_SCHEDULE"
        const val ACTION_PLAY_MEDIA = "com.awaken.app.PLAY_MEDIA"
        const val ACTION_STOP = "com.awaken.app.STOP_PLAYBACK"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_SNOOZE = "snooze"
        const val EXTRA_MEDIA_IDS = "media_ids"
        const val EXTRA_START_INDEX = "start_index"

        const val CMD_SET_VOLUME = "awaken.set_volume"
        const val CMD_SET_MUTE = "awaken.set_mute"
        const val CMD_STOP = "awaken.stop"
        const val ARG_VOLUME = "volume"
        const val ARG_MUTE = "mute"

        private const val TICK_MS = 100L
        private const val MAX_PAUSE_SCHEDULED_MS = 2 * 60_000L
        private const val MAX_PAUSE_MANUAL_MS = 30 * 60_000L

        /** Called from AlarmReceiver, which is allowed to start a foreground service from an exact alarm. */
        fun startSchedule(context: Context, scheduleId: Long, triggerAt: Long, snooze: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY_SCHEDULE)
                    .putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_TRIGGER_AT, triggerAt).putExtra(EXTRA_SNOOZE, snooze),
            )
        }

        /** Manual playback from the UI (library preview, home "play now"). */
        fun playMedia(context: Context, mediaIds: List<Long>, startIndex: Int = 0) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY_MEDIA)
                    .putExtra(EXTRA_MEDIA_IDS, mediaIds.toLongArray()).putExtra(EXTRA_START_INDEX, startIndex),
            )
        }

        fun requestStop(context: Context, snoozed: Boolean = false) {
            try {
                context.startService(
                    Intent(context, PlaybackService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SNOOZE, snoozed)
                )
            } catch (e: Exception) {
                AppLog.w("Stop request could not reach the service (probably not running)", e)
            }
        }
    }

    private class Run(
        val scheduleId: Long?,
        val name: String,
        val scheduled: Boolean,
        val historyId: Long,
        val items: Map<String, MediaEntity>,
        val totalMs: Long,
        val capped: Boolean,
        val fadeInMs: Long,
        val fadeOutMs: Long,
        val gradualMs: Long,
        val customVolume: Boolean,
    ) {
        var elapsedMs = 0L
        var pausedMs = 0L
    }

    private lateinit var container: AppContainer
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var run: Run? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as AwakenApp).container
        Notifications.ensureChannels(this)

        val tap = LevelTap()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(tap))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setCallback(sessionCallback).setSessionActivity(open).build()

        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(Notifications.PLAYBACK_ID)
            .setChannelId(Notifications.CHANNEL_PLAYBACK)
            .setChannelName(R.string.channel_playback)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat_awaken)
        setMediaNotificationProvider(provider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_SCHEDULE -> {
                goForeground("Preparing…")
                val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1)
                val trig = intent.getLongExtra(EXTRA_TRIGGER_AT, 0)
                scope.launch { runCatching { handleSchedule(id, trig, intent.getBooleanExtra(EXTRA_SNOOZE, false)) }.onFailure { fail("Unexpected error", it) } }
                return START_NOT_STICKY
            }
            ACTION_PLAY_MEDIA -> {
                goForeground("Preparing…")
                val ids = intent.getLongArrayExtra(EXTRA_MEDIA_IDS)?.toList().orEmpty()
                val start = intent.getIntExtra(EXTRA_START_INDEX, 0)
                scope.launch { runCatching { handleManual(ids, start) }.onFailure { fail("Unexpected error", it) } }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (run != null) finish(if (intent.getBooleanExtra(EXTRA_SNOOZE, false)) "SNOOZED" else "STOPPED") else stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Keep playing when the app is swiped away: a scheduled alarm must not die with the task. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (run == null && !player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        PlaybackBus.setNowPlaying(null)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scheduled + manual starts

    private suspend fun handleSchedule(id: Long, triggerAt: Long, snooze: Boolean) {
        val s = container.schedules.get(id)
        if (s == null) { AppLog.w("Schedule $id vanished before playback"); finishIdle(); return }
        val cur = run
        if (cur != null && cur.scheduleId == id && !snooze && player.playbackState != Player.STATE_IDLE) {
            AppLog.w("Duplicate playback prevented for '${s.name}'")
            return
        }
        val cfg = container.settings.current()
        val resolved = container.resolver.resolve(s)
        if (resolved == null) {
            container.history.log(id, s.name, "", "FAILED", "No media is available. Import media or pick one in the schedule.")
            Notifications.postFailure(this, "'${s.name}' has no media to play.")
            finishIdle(); return
        }
        val playable = resolved.items.filter { container.media.isAccessible(it) }
        if (playable.isEmpty()) {
            val title = resolved.items.first().displayName
            container.history.log(id, s.name, title, "FAILED", "The media file is missing or access was revoked. Re-import it.")
            Notifications.postFailure(this, "'$title' can't be opened. Re-import it in the Media Library.")
            finishIdle(); return
        }
        container.schedules.markFired(id, triggerAt, resolved.lastPlaylistIndex)

        val isAlarm = s.scheduleKind == ScheduleKind.ALARM_CLOCK
        val volume = if (s.volume >= 0f) s.volume else cfg.defaultVolume
        begin(
            items = playable, name = s.name, scheduleId = id, scheduled = true, alarmUsage = isAlarm,
            volume = volume, customVolume = s.volume >= 0f, durationSec = s.durationSec,
            gradual = s.gradualVolume || cfg.gradualVolume, vibrate = cfg.vibration, startIndex = 0,
        )
        Notifications.postAlert(this, id, s.name, "Playing ${playable.first().displayName}", s.snoozeMinutes, isAlarm)
    }

    private suspend fun handleManual(ids: List<Long>, startIndex: Int) {
        val byId = container.media.getByIds(ids).associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }.filter { container.media.isAccessible(it) }
        if (items.isEmpty()) {
            Notifications.postFailure(this, "That media can't be opened. Re-import it in the Media Library.")
            finishIdle(); return
        }
        val cfg = container.settings.current()
        begin(
            items = items, name = "Manual play", scheduleId = null, scheduled = false, alarmUsage = false,
            volume = cfg.defaultVolume, customVolume = false, durationSec = 0,
            gradual = false, vibrate = false, startIndex = startIndex.coerceIn(0, items.lastIndex),
        )
    }

    private suspend fun begin(
        items: List<MediaEntity>, name: String, scheduleId: Long?, scheduled: Boolean, alarmUsage: Boolean,
        volume: Float, customVolume: Boolean, durationSec: Int, gradual: Boolean, vibrate: Boolean, startIndex: Int,
    ) {
        val cfg = container.settings.current()
        run?.let { prev ->
            container.appScope.launch { container.history.setStatus(prev.historyId, "INTERRUPTED", "Replaced by '$name'") }
        }
        tickJob?.cancel()

        val natural = items.sumOf { it.durationMs }
        val total = PlaybackPlanner.totalDurationMs(natural, 1, durationSec)
        val capped = durationSec > 0 && durationSec * 1000L < natural
        val historyId = container.history.log(scheduleId, name, items.first().displayName, "STARTED")

        PlaybackBus.setUserVolume(volume)
        PlaybackBus.setMuted(cfg.muted)
        run = Run(
            scheduleId, name, scheduled, historyId, items.associateBy { it.id.toString() }, total, capped,
            cfg.fadeInMs.toLong(), cfg.fadeOutMs.toLong(), if (gradual) cfg.gradualSeconds * 1000L else 0L, customVolume,
        )

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (alarmUsage) C.USAGE_ALARM else C.USAGE_MEDIA)
                .setContentType(if (items.first().isVideo) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.volume = 0f // the ramp raises it; never start at full volume
        player.setMediaItems(items.map(::toItem), startIndex, C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true

        PlaybackBus.setNowPlaying(
            NowPlaying(scheduleId, name, items[startIndex].displayName, items[startIndex].isVideo, scheduled, System.currentTimeMillis(), total)
        )
        if (vibrate) vibrateOnce()
        startTicker()
        AppLog.i("Playing '${items.first().displayName}' for '$name' (scheduled=$scheduled, capped=$capped, total=${total}ms)")
    }

    private fun toItem(m: MediaEntity): MediaItem = MediaItem.Builder()
        .setMediaId(m.id.toString())
        .setUri(Uri.parse(m.uri))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(m.displayName).setArtist("AWAKEN").build())
        .build()

    // ---------------------------------------------------------------- ticking, volume, ending

    private fun startTicker() {
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val r = run ?: break
                if (player.isPlaying) { r.elapsedMs += TICK_MS; r.pausedMs = 0 }
                else if (player.playbackState != Player.STATE_BUFFERING) r.pausedMs += TICK_MS
                applyVolume(r)
                if (r.capped && r.elapsedMs >= r.totalMs) { finish("COMPLETED"); break }
                val limit = if (r.scheduled) MAX_PAUSE_SCHEDULED_MS else MAX_PAUSE_MANUAL_MS
                if (r.pausedMs >= limit) { finish("INTERRUPTED", "Paused or interrupted for too long"); break }
            }
        }
    }

    private fun applyVolume(r: Run) {
        val gain = VolumeRamp.gain(r.elapsedMs, r.totalMs, r.fadeInMs, r.fadeOutMs, r.gradualMs)
        player.volume = if (PlaybackBus.muted.value) 0f else PlaybackBus.userVolume.value * gain
    }

    private fun finish(status: String, detail: String = "") {
        val r = run
        run = null
        tickJob?.cancel()
        if (r != null) container.appScope.launch { container.history.setStatus(r.historyId, status, detail) }
        player.stop()
        player.clearMediaItems()
        PlaybackBus.setNowPlaying(null)
        Notifications.cancelAlert(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLog.i("Playback finished: $status ${detail}".trim())
    }

    /** A start request that ended before playback began. Never tears down playback that is already running. */
    private fun finishIdle() {
        if (run == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String, t: Throwable?) {
        AppLog.e(message, t)
        val r = run
        if (r != null) container.appScope.launch { container.history.setStatus(r.historyId, "FAILED", "$message: ${t?.message ?: ""}") }
        Notifications.postFailure(this, message)
        run = null
        tickJob?.cancel()
        player.stop()
        PlaybackBus.setNowPlaying(null)
        Notifications.cancelAlert(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this, Notifications.PLAYBACK_ID, Notifications.playbackPlaceholder(this, text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun vibrateOnce() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 200, 350), -1))
        } catch (e: Exception) {
            AppLog.w("Vibration unavailable", e)
        }
    }

    // ---------------------------------------------------------------- listeners

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && run != null) finish("COMPLETED")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val r = run ?: return
            val m = mediaItem?.mediaId?.let { r.items[it] } ?: return
            val np = PlaybackBus.nowPlaying.value ?: return
            PlaybackBus.setNowPlaying(np.copy(title = m.displayName, isVideo = m.isVideo))
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e("Player error ${error.errorCodeName}", error)
            val r = run ?: return
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                container.appScope.launch { container.history.setStatus(r.historyId, "FAILED", "Playback error: ${error.errorCodeName}") }
                Notifications.postFailure(this@PlaybackService, "Playback failed (${error.errorCodeName}).")
                run = null
                tickJob?.cancel()
                player.stop()
                PlaybackBus.setNowPlaying(null)
                Notifications.cancelAlert(this@PlaybackService)
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_MUTE, Bundle.EMPTY))
                .add(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_VOLUME -> {
                    val v = args.getFloat(ARG_VOLUME, PlaybackBus.userVolume.value).coerceIn(0f, 1f)
                    PlaybackBus.setUserVolume(v)
                    if (run?.customVolume != true) container.appScope.launch { container.settings.setDefaultVolume(v) }
                    run?.let(::applyVolume)
                }
                CMD_SET_MUTE -> {
                    val m = args.getBoolean(ARG_MUTE, false)
                    PlaybackBus.setMuted(m)
                    container.appScope.launch { container.settings.setMuted(m) }
                    run?.let(::applyVolume)
                }
                CMD_STOP -> if (run != null) finish("STOPPED") else { player.stop(); stopSelf() }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
