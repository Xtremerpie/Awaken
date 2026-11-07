package com.awaken.app.ui.schedules

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.awaken.app.data.db.DaysMask
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.db.PlaylistEntity
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.repo.ScheduleRepository
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.di.AppContainer
import com.awaken.app.ui.components.EmptyState
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.components.rememberHaptics
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.LocalHapticsEnabled
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.awakenViewModel
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.core.ScheduleKind
import com.awaken.core.remote.FeatureFlag
import com.awaken.core.remote.SuggestedSchedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class SchedulesState(
    val schedules: List<ScheduleEntity> = emptyList(),
    val media: List<MediaEntity> = emptyList(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class SchedulesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<SchedulesState> = combine(
        c.schedules.observeAll(), c.media.observeAll(), c.playlists.observePlaylists(), c.settings.settings,
    ) { s, m, p, cfg -> SchedulesState(s, m, p, cfg) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SchedulesState())

    fun setEnabled(id: Long, v: Boolean) = viewModelScope.launch { c.schedules.setEnabled(id, v) }

    /** Adds a remote-suggested schedule only because the user tapped Add. */
    fun addSuggested(s: SuggestedSchedule) = viewModelScope.launch {
        c.schedules.save(
            ScheduleEntity(
                name = s.name, kind = s.kind.name, enabled = true,
                startMinutes = s.start.hour * 60 + s.start.minute,
                endMinutes = s.end?.let { it.hour * 60 + it.minute } ?: -1,
                intervalMinutes = s.intervalMinutes, daysMask = DaysMask.fromSet(s.days),
                respectQuietHours = s.kind == ScheduleKind.RECURRING,
            )
        )
    }
}

fun daysSummary(mask: Int): String {
    val days = DaysMask.toSet(mask)
    return when {
        days.isEmpty() || days.size == 7 -> "Every day"
        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
        days == DayOfWeek.entries.take(5).toSet() -> "Weekdays"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}

fun scheduleTimeSummary(s: ScheduleEntity): String =
    if (s.scheduleKind == ScheduleKind.ALARM_CLOCK) Fmt.minutes(s.startMinutes)
    else "${Fmt.interval(s.intervalMinutes)}, ${Fmt.minutes(s.startMinutes)}–${if (s.endMinutes < 0) "end of day" else Fmt.minutes(s.endMinutes)}"

@Composable
fun SchedulesScreen(onEdit: (Long) -> Unit) {
    val vm = awakenViewModel { SchedulesViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val tap = rememberHaptics(LocalHapticsEnabled.current)
    val p = LocalPalette.current

    ScreenColumn("Schedules") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldButton("New schedule", { tap(); onEdit(0) }, Modifier.weight(1f), Icons.Rounded.Add)
            GhostButton("New alarm", { tap(); onEdit(-1) }, Modifier.weight(1f), Icons.Rounded.Alarm)
        }
        if (!st.settings.masterEnabled) {
            GlassCard(accent = true) { Text("AWAKEN is switched off in Settings, so no schedule will fire.", style = MaterialTheme.typography.bodyMedium) }
        }
        if (st.schedules.isEmpty()) EmptyState("No schedules", remote.texts["schedules.empty"] ?: "Create an alarm or a recurring reminder.")

        st.schedules.forEach { s ->
            val mediaName = when {
                s.playlistId != null -> "Playlist: " + (st.playlists.firstOrNull { it.id == s.playlistId }?.name ?: "missing")
                s.mediaId != null -> st.media.firstOrNull { it.id == s.mediaId }?.displayName ?: "missing media"
                else -> "Default media"
            }
            val next = ScheduleRepository.nextTrigger(s, st.settings.quietHours)
            GlassCard(onClick = { onEdit(s.id) }, accent = s.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = MaterialTheme.typography.titleLarge)
                            StatusPill(if (s.scheduleKind == ScheduleKind.ALARM_CLOCK) "ALARM" else "RECURRING", p.primary)
                        }
                        Text(scheduleTimeSummary(s), style = MaterialTheme.typography.bodyMedium)
                        Text("${daysSummary(s.daysMask)}  ·  $mediaName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.enabled) Text(
                            next?.format(DateTimeFormatter.ofPattern("'Next:' EEE d MMM, h:mm a", Locale.getDefault())) ?: "No upcoming trigger",
                            style = MaterialTheme.typography.bodySmall, color = p.primary,
                        )
                    }
                    Switch(
                        s.enabled, { tap(); vm.setEnabled(s.id, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF1A0E00), checkedTrackColor = p.primary),
                    )
                }
            }
        }

        if (remote.flag(FeatureFlag.SUGGESTED_SCHEDULES) && remote.suggestedSchedules.isNotEmpty()) {
            SectionLabel("Suggested")
            remote.suggestedSchedules.forEach { sug ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sug.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (sug.kind == ScheduleKind.ALARM_CLOCK) "Alarm at ${Fmt.time(sug.start)}" else "${Fmt.interval(sug.intervalMinutes)} from ${Fmt.time(sug.start)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GhostButton("Add", { vm.addSuggested(sug) })
                    }
                }
            }
        }
    }
}
