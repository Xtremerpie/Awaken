package com.awaken.app.ui.player

import android.content.ComponentName
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.awaken.app.playback.PlaybackBus
import com.awaken.app.playback.PlaybackService
import com.awaken.app.ui.components.AwakenLogo
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.components.VisualizerBars
import com.awaken.app.ui.components.glow
import com.awaken.app.ui.components.rememberHaptics
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.LocalHapticsEnabled
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.core.remote.FeatureFlag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Connects to the playback service's media session while something is playing. */
@Composable
private fun rememberController(active: Boolean): State<MediaController?> {
    val ctx = LocalContext.current
    val holder = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(active) {
        val future = if (active) {
            MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        } else null
        future?.addListener({ runCatching { holder.value = future.get() } }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            holder.value = null
            future?.let { MediaController.releaseFuture(it) }
        }
    }
    return holder
}

@Composable
fun PlayerScreen() {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tap = rememberHaptics(LocalHapticsEnabled.current)
    val remote = LocalRemote.current
    val p = LocalPalette.current

    val now by PlaybackBus.nowPlaying.collectAsStateWithLifecycle()
    val volume by PlaybackBus.userVolume.collectAsStateWithLifecycle()
    val muted by PlaybackBus.muted.collectAsStateWithLifecycle()
    val levels by PlaybackBus.levels.collectAsStateWithLifecycle()
    val controller by rememberController(now != null)

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var repeat by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                if (!scrubbing) position = c.currentPosition.coerceAtLeast(0)
                duration = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                playing = c.isPlaying
                speed = c.playbackParameters.speed
                repeat = c.repeatMode
            }
            delay(250)
        }
    }

    fun command(action: String, args: Bundle) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    ScreenColumn("Player") {
        val np = now
        if (np == null) {
            GlassCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AwakenLogo(size = 96.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing is playing", style = MaterialTheme.typography.titleLarge)
                    Text("Start your default media, or preview any file from the Library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    GoldButton("Play default media", {
                        tap()
                        scope.launch { container.media.defaultMedia()?.let { PlaybackService.playMedia(ctx, listOf(it.id)) } }
                    }, Modifier.fillMaxWidth(), Icons.Rounded.PlayArrow)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Text(np.scheduleName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(if (np.scheduled) "SCHEDULED" else "MANUAL", p.primary)
            }

            if (np.isVideo) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                    AndroidView(
                        factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                        update = { it.player = controller },
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().glow(p.secondary, 220.dp, .18f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AwakenLogo(size = 96.dp, pulse = levels.maxOrNull() ?: 0f)
                        if (remote.flag(FeatureFlag.VISUALIZER)) VisualizerBars(levels, Modifier.padding(top = 12.dp))
                    }
                }
            }

            val total = duration.coerceAtLeast(1)
            Column {
                Slider(
                    value = if (scrubbing) scrubValue else (position.toFloat() / total).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = true; scrubValue = it },
                    onValueChangeFinished = { controller?.seekTo((scrubValue * total).toLong()); scrubbing = false },
                    enabled = controller != null && duration > 0,
                    colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.duration(if (scrubbing) (scrubValue * total).toLong() else position), style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.duration(duration), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ tap(); controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                IconButton(
                    { tap(); controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    Modifier.size(72.dp).background(p.primary, RoundedCornerShape(36.dp)),
                ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp), tint = Color(0xFF1A0E00)) }
                IconButton({ tap(); controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
                IconButton({ tap(); PlaybackService.requestStop(ctx) }) { Icon(Icons.Rounded.Stop, "Stop", Modifier.size(32.dp)) }
            }

            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({
                            tap(); command(PlaybackService.CMD_SET_MUTE, Bundle().apply { putBoolean(PlaybackService.ARG_MUTE, !muted) })
                        }) { Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Unmute" else "Mute") }
                        Slider(
                            value = volume,
                            onValueChange = { command(PlaybackService.CMD_SET_VOLUME, Bundle().apply { putFloat(PlaybackService.ARG_VOLUME, it) }) },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary),
                        )
                        Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            FilterChip(speed == s, { tap(); controller?.setPlaybackSpeed(s) }, { Text("${s}x") })
                        }
                    }
                    Text("Repeat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(repeat == Player.REPEAT_MODE_OFF, { tap(); controller?.repeatMode = Player.REPEAT_MODE_OFF }, { Text("Off") })
                        FilterChip(repeat == Player.REPEAT_MODE_ONE, { tap(); controller?.repeatMode = Player.REPEAT_MODE_ONE }, { Text("Loop") }, leadingIcon = { Icon(Icons.Rounded.RepeatOne, null, Modifier.size(16.dp)) })
                        FilterChip(repeat == Player.REPEAT_MODE_ALL, { tap(); controller?.repeatMode = Player.REPEAT_MODE_ALL }, { Text("All") }, leadingIcon = { Icon(Icons.Rounded.Repeat, null, Modifier.size(16.dp)) })
                    }
                }
            }
            Text(
                "The notification and lock screen show the same controls. Fades and gradual volume are set in Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
