package com.awaken.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.awaken.app.data.db.HistoryEntity
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.repo.ScheduleRepository
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.di.AppContainer
import com.awaken.app.playback.PlaybackBus
import com.awaken.app.playback.PlaybackService
import com.awaken.app.ui.components.AnalogClock
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.components.SwitchRow
import com.awaken.app.ui.components.rememberHaptics
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.LocalHapticsEnabled
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.nav.Routes
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.awakenViewModel
import com.awaken.app.ui.nav.rememberPermissionState
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.core.ScheduleKind
import com.awaken.core.remote.DashboardWidget
import com.awaken.core.remote.FeatureFlag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeState(
    val settings: AppSettings = AppSettings(),
    val schedules: List<ScheduleEntity> = emptyList(),
    val media: List<MediaEntity> = emptyList(),
    val today: List<HistoryEntity> = emptyList(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<HomeState> = combine(
        c.settings.settings, c.schedules.observeAll(), c.media.observeAll(), c.history.observeToday(),
    ) { s, sch, m, h -> HomeState(s, sch, m, h) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())

    fun setMaster(v: Boolean) = viewModelScope.launch { c.settings.setMasterEnabled(v); c.applySettingsChange() }
    fun setScheduleEnabled(id: Long, v: Boolean) = viewModelScope.launch { c.schedules.setEnabled(id, v) }
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val vm = awakenViewModel { HomeViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val perms = rememberPermissionState()
    val p = LocalPalette.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var next by remember(st.schedules, st.settings) { mutableStateOf(ScheduleRepository.nextOverall(st.schedules, st.settings)) }
    LaunchedEffect(st.schedules, st.settings) {
        while (true) {
            now = System.currentTimeMillis()
            val n = next
            if (n != null && now >= n.second.toInstant().toEpochMilli()) next = ScheduleRepository.nextOverall(st.schedules, st.settings)
            kotlinx.coroutines.delay(1000)
        }
    }

    val greeting = remote.texts["home.greeting"] ?: when (LocalDateTime.now().hour) {
        in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Good night"
    }
    val layout = remote.dashboardLayout.filter { w ->
        when (w) {
            DashboardWidget.HISTORY -> remote.flag(FeatureFlag.HISTORY)
            DashboardWidget.ANNOUNCEMENT -> remote.flag(FeatureFlag.ANNOUNCEMENTS)
            else -> true
        }
    }
    val defaultMedia = st.media.firstOrNull { it.id == st.settings.defaultMediaId } ?: st.media.firstOrNull { it.bundled } ?: st.media.firstOrNull()
    val hourly = st.schedules.firstOrNull { it.isDefaultHourly }

    ScreenColumn(greeting) {
        remote.texts["home.subtitle"]?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (!perms.reliable) {
            GlassCard(onClick = { onNavigate(Routes.ALARM_SETTINGS) }, accent = true) {
                Column {
                    Text("Playback may be delayed or blocked", style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildList {
                            if (!perms.exactAlarms) add("exact alarms are off")
                            if (!perms.notifications) add("notifications are off")
                            if (!perms.batteryUnrestricted) add("battery optimisation is on")
                        }.joinToString(", ").replaceFirstChar { it.uppercase() } + ". Tap to fix.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        layout.forEach { w ->
            when (w) {
                DashboardWidget.ANNOUNCEMENT -> remote.announcements.firstOrNull()?.let { a ->
                    GlassCard(accent = a.severity == "warning") {
                        Column {
                            Text(a.title, style = MaterialTheme.typography.titleMedium)
                            if (a.body.isNotEmpty()) Text(a.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                DashboardWidget.CLOCK -> ClockCard(now)
                DashboardWidget.COUNTDOWN -> CountdownCard(next, st.settings, now)
                DashboardWidget.NEXT_PLAYBACK -> NextPlaybackCard(next, st.media, defaultMedia)
                DashboardWidget.HOURLY_STATUS -> hourly?.let { HourlyCard(it, next, vm::setScheduleEnabled) }
                DashboardWidget.CURRENT_MEDIA -> CurrentMediaCard(defaultMedia) { onNavigate(Routes.LIBRARY) }
                DashboardWidget.QUICK_CONTROLS -> QuickControls(defaultMedia, st.settings.masterEnabled, vm::setMaster)
                DashboardWidget.HISTORY -> TodayHistory(st.today) { onNavigate(Routes.HISTORY) }
            }
        }
    }
}

@Composable
private fun ClockCard(now: Long) {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    GlassCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AnalogClock()
            Spacer(Modifier.height(10.dp))
            Text(zdt.format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.getDefault())), style = MaterialTheme.typography.displaySmall)
            Text(zdt.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CountdownCard(next: Pair<ScheduleEntity, ZonedDateTime>?, cfg: AppSettings, now: Long) {
    val p = LocalPalette.current
    GlassCard(accent = next != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NEXT ALARM IN", style = MaterialTheme.typography.labelSmall, color = p.primary)
            if (next == null) {
                Text("—", style = MaterialTheme.typography.displayMedium)
                Text(if (!cfg.masterEnabled) "AWAKEN is switched off" else "No schedule is enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val remaining = next.second.toInstant().toEpochMilli() - now
                Text(Fmt.countdown(remaining), style = MaterialTheme.typography.displayMedium)
                val window = 3_600_000f
                LinearProgressIndicator(progress = { (1f - remaining / window).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun NextPlaybackCard(next: Pair<ScheduleEntity, ZonedDateTime>?, media: List<MediaEntity>, default: MediaEntity?) {
    SectionLabel("Next scheduled playback")
    GlassCard {
        if (next == null) {
            Text("Nothing scheduled", style = MaterialTheme.typography.titleMedium)
        } else {
            val (s, at) = next
            val title = (s.mediaId?.let { id -> media.firstOrNull { it.id == id } } ?: default)?.displayName ?: "No media"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (s.playlistId != null) "Playlist" else title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), style = MaterialTheme.typography.titleLarge)
                    Text(at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HourlyCard(s: ScheduleEntity, next: Pair<ScheduleEntity, ZonedDateTime>?, setEnabled: (Long, Boolean) -> Unit) {
    SectionLabel("Hourly playback")
    GlassCard {
        Column {
            SwitchRow(
                s.name,
                "${Fmt.interval(s.intervalMinutes)}  ·  ${Fmt.minutes(s.startMinutes)}–${if (s.endMinutes < 0) "11:59 PM" else Fmt.minutes(s.endMinutes)}",
                s.enabled, { setEnabled(s.id, it) },
            )
            if (s.enabled && next?.first?.id == s.id) {
                Text("Next: ${next.second.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CurrentMediaCard(media: MediaEntity?, onChange: () -> Unit) {
    SectionLabel("Selected media")
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(media?.displayName ?: "None selected", style = MaterialTheme.typography.titleMedium)
                if (media != null) Text("${if (media.isVideo) "Video" else "Audio"}  ·  ${Fmt.duration(media.durationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GhostButton("Change", onChange)
        }
    }
}

@Composable
private fun QuickControls(media: MediaEntity?, master: Boolean, setMaster: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val tap = rememberHaptics(LocalHapticsEnabled.current)
    val playing by PlaybackBus.nowPlaying.collectAsStateWithLifecycle()
    SectionLabel("Quick controls")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GoldButton("Play now", { tap(); media?.let { PlaybackService.playMedia(ctx, listOf(it.id)) } }, Modifier.weight(1f), Icons.Rounded.PlayArrow, enabled = media != null)
                GhostButton("Stop", { tap(); PlaybackService.requestStop(ctx) }, Modifier.weight(1f), Icons.Rounded.Stop, enabled = playing != null)
            }
            playing?.let { Text("Playing: ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SwitchRow("AWAKEN enabled", "Master switch for every schedule", master, setMaster)
        }
    }
}

@Composable
private fun TodayHistory(items: List<HistoryEntity>, onAll: () -> Unit) {
    SectionLabel("Today's playback")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) {
                Text("Nothing has played yet today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                items.take(5).forEach { HistoryRow(it) }
            }
            GhostButton("Full history", onAll, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun HistoryRow(h: HistoryEntity) {
    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(h.firedAt), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    val color = when (h.status) {
        "COMPLETED", "STARTED" -> LocalPalette.current.primary
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.scheduleName + if (h.mediaTitle.isNotEmpty()) "  ·  ${h.mediaTitle}" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (h.detail.isNotEmpty()) Text(h.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(time, style = MaterialTheme.typography.bodySmall)
            StatusPill(h.status, color)
        }
    }
}
