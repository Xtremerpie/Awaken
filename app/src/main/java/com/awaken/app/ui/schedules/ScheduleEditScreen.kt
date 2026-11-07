package com.awaken.app.ui.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.awaken.app.data.db.DaysMask
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.repo.ScheduleRepository
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.di.AppContainer
import com.awaken.app.ui.components.ConfirmDialog
import com.awaken.app.ui.components.DayChips
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.SwitchRow
import com.awaken.app.ui.components.TimeDialog
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.awakenViewModel
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.core.OrderMode
import com.awaken.core.ScheduleEngine
import com.awaken.core.ScheduleKind
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleEditViewModel(private val c: AppContainer) : ViewModel() {
    val media = c.media.observeAll()
    val playlists = c.playlists.observePlaylists()
    val settings = c.settings.settings

    /** id > 0 loads an existing schedule; 0 = new recurring; -1 = new alarm clock. */
    suspend fun load(id: Long): ScheduleEntity {
        if (id > 0) c.schedules.get(id)?.let { return it }
        val cfg = c.settings.current()
        return if (id == -1L) ScheduleEntity(
            name = "Alarm", kind = ScheduleKind.ALARM_CLOCK.name, startMinutes = 7 * 60, respectQuietHours = false,
            snoozeMinutes = cfg.defaultSnoozeMinutes, durationSec = cfg.defaultDurationSec, gradualVolume = true,
        ) else ScheduleEntity(
            name = "Reminder", kind = ScheduleKind.RECURRING.name, startMinutes = 9 * 60, endMinutes = 18 * 60,
            intervalMinutes = 60, snoozeMinutes = cfg.defaultSnoozeMinutes, durationSec = cfg.defaultDurationSec,
        )
    }

    fun save(s: ScheduleEntity, done: () -> Unit) = viewModelScope.launch { c.schedules.save(s); done() }
    fun delete(id: Long, done: () -> Unit) = viewModelScope.launch { c.schedules.delete(id); done() }
}

@Composable
fun ScheduleEditScreen(id: Long, onClose: () -> Unit) {
    val vm = awakenViewModel(key = "schedule-edit-$id") { ScheduleEditViewModel(it) }
    val media by vm.media.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by vm.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var draft by remember { mutableStateOf<ScheduleEntity?>(null) }
    LaunchedEffect(id) { draft = vm.load(id) }
    val d = draft

    ScreenColumn(if (id > 0) "Edit schedule" else if (id == -1L) "New alarm" else "New schedule", onClose) {
        if (d == null) { Text("Loading…"); return@ScreenColumn }
        EditorBody(d, { draft = it }, media.map { it.id to it.displayName }, playlists.map { it.id to it.name }, settings, vm, id, onClose)
    }
}

@Composable
private fun EditorBody(
    d: ScheduleEntity, update: (ScheduleEntity) -> Unit,
    media: List<Pair<Long, String>>, playlists: List<Pair<Long, String>>,
    settings: AppSettings, vm: ScheduleEditViewModel, id: Long, onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val isAlarm = d.scheduleKind == ScheduleKind.ALARM_CLOCK
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickFromDate by remember { mutableStateOf(false) }
    var pickToDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val source = when { d.playlistId != null -> 2; d.mediaId != null -> 1; else -> 0 }
    val spec = d.toSpec()
    val next = ScheduleRepository.nextTrigger(d.copy(enabled = true), settings.quietHours)

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(d.name, { update(d.copy(name = it.take(60))) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            Text("Type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(isAlarm, { update(d.copy(kind = ScheduleKind.ALARM_CLOCK.name, respectQuietHours = false)) }, { Text("Alarm clock") })
                FilterChip(!isAlarm, { update(d.copy(kind = ScheduleKind.RECURRING.name, respectQuietHours = true)) }, { Text("Recurring") })
            }
            SwitchRow("Enabled", null, d.enabled, { update(d.copy(enabled = it)) })
        }
    }

    SectionLabel("When")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isAlarm) {
                GhostButton("Time: ${Fmt.minutes(d.startMinutes)}", { pickStart = true }, Modifier.fillMaxWidth())
            } else {
                GhostButton("From: ${Fmt.minutes(d.startMinutes)}", { pickStart = true }, Modifier.fillMaxWidth())
                SwitchRow("Run until a set time", "Otherwise until the end of the day", d.endMinutes >= 0, { on -> update(d.copy(endMinutes = if (on) (d.startMinutes + 60).coerceAtMost(1439) else -1)) })
                if (d.endMinutes >= 0) GhostButton("Until: ${Fmt.minutes(d.endMinutes)}", { pickEnd = true }, Modifier.fillMaxWidth())
                Text("Repeat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m -> FilterChip(d.intervalMinutes == m, { update(d.copy(intervalMinutes = m)) }, { Text(Fmt.interval(m).removePrefix("every ")) }) }
                }
                OutlinedTextField(
                    d.intervalMinutes.toString(), { t -> t.filter(Char::isDigit).take(4).toIntOrNull()?.let { update(d.copy(intervalMinutes = it)) } },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("Custom interval (minutes)") },
                )
            }
            Text("Days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DayChips(DaysMask.toSet(d.daysMask)) { update(d.copy(daysMask = DaysMask.fromSet(it))) }
            Text(daysSummary(d.daysMask), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(d.startEpochDay?.let { "From ${LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))}" } ?: "Start date", { pickFromDate = true }, Modifier.weight(1f))
                GhostButton(d.endEpochDay?.let { "To ${LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))}" } ?: "End date", { pickToDate = true }, Modifier.weight(1f))
            }
            if (d.startEpochDay != null || d.endEpochDay != null) TextButton({ update(d.copy(startEpochDay = null, endEpochDay = null)) }) { Text("Clear dates") }
            if (!isAlarm) SwitchRow("Respect quiet hours", if (settings.quietEnabled) "Skips ${Fmt.minutes(settings.quietStartMinutes)}–${Fmt.minutes(settings.quietEndMinutes)}" else "Quiet hours are off in Settings", d.respectQuietHours, { update(d.copy(respectQuietHours = it)) })
            Text(
                next?.format(DateTimeFormatter.ofPattern("'Next trigger: 'EEE d MMM, h:mm a", Locale.getDefault())) ?: "This schedule has no upcoming trigger.",
                color = p.primary, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    SectionLabel("What plays")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(source == 0, { update(d.copy(mediaId = null, playlistId = null)) }, { Text("Default") })
                FilterChip(source == 1, { update(d.copy(mediaId = media.firstOrNull()?.first, playlistId = null)) }, { Text("One file") }, enabled = media.isNotEmpty())
                FilterChip(source == 2, { update(d.copy(playlistId = playlists.firstOrNull()?.first, mediaId = null)) }, { Text("Playlist") }, enabled = playlists.isNotEmpty())
            }
            when (source) {
                1 -> media.forEach { (mid, name) -> PickRow(name, d.mediaId == mid) { update(d.copy(mediaId = mid)) } }
                2 -> {
                    playlists.forEach { (pid, name) -> PickRow(name, d.playlistId == pid) { update(d.copy(playlistId = pid)) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(d.orderMode == "SEQUENTIAL", { update(d.copy(orderMode = OrderMode.SEQUENTIAL.name)) }, { Text("Sequential") })
                        FilterChip(d.orderMode == "RANDOM", { update(d.copy(orderMode = OrderMode.RANDOM.name)) }, { Text("Random") })
                    }
                }
                else -> Text("Uses whichever media is set as default in the Library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SectionLabel("Playback")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow("Use default volume", "${(settings.defaultVolume * 100).toInt()}% from Settings", d.volume < 0f, { on -> update(d.copy(volume = if (on) -1f else settings.defaultVolume)) })
            if (d.volume >= 0f) {
                Slider(d.volume, { update(d.copy(volume = it)) }, colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary))
                Text("Volume ${(d.volume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                d.durationSec.toString(), { t -> update(d.copy(durationSec = t.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Max duration in seconds (0 = play in full)") },
            )
            Stepper("Times to play per trigger", d.repeatCount, 1, 20) { update(d.copy(repeatCount = it)) }
            Stepper("Snooze (minutes)", d.snoozeMinutes, 1, 60) { update(d.copy(snoozeMinutes = it)) }
            SwitchRow("Gradual volume", "Start quietly and rise (length set in Settings)", d.gradualVolume, { update(d.copy(gradualVolume = it)) })
        }
    }

    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    GoldButton("Save", {
        val problems = ScheduleEngine.validate(spec)
        if (problems.isNotEmpty()) error = problems.joinToString(" ") else {
            error = ""
            vm.save(d.copy(name = d.name.trim().ifEmpty { "Schedule" }), onClose)
        }
    }, Modifier.fillMaxWidth())
    if (id > 0) GhostButton("Delete schedule", { confirmDelete = true }, Modifier.fillMaxWidth(), Icons.Rounded.Delete)

    if (pickStart) TimeDialog(LocalTime.of(d.startMinutes / 60, d.startMinutes % 60), if (isAlarm) "Alarm time" else "Start time", { pickStart = false }) { update(d.copy(startMinutes = it.hour * 60 + it.minute)); pickStart = false }
    if (pickEnd) TimeDialog(LocalTime.of(d.endMinutes / 60, d.endMinutes % 60), "End time", { pickEnd = false }) { update(d.copy(endMinutes = it.hour * 60 + it.minute)); pickEnd = false }
    if (pickFromDate) DateDialog(d.startEpochDay?.let(LocalDate::ofEpochDay), { pickFromDate = false }) { update(d.copy(startEpochDay = it.toEpochDay())); pickFromDate = false }
    if (pickToDate) DateDialog(d.endEpochDay?.let(LocalDate::ofEpochDay), { pickToDate = false }) { update(d.copy(endEpochDay = it.toEpochDay())); pickToDate = false }
    if (confirmDelete) ConfirmDialog("Delete '${d.name}'?", "This schedule will no longer fire.", "Delete", { confirmDelete = false }) { vm.delete(d.id, onClose) }
}

@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, maxLines = 1)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton({ onChange((value - 1).coerceAtLeast(min)) }) { Icon(Icons.Rounded.Remove, "Less") }
        Text("$value", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
        IconButton({ onChange((value + 1).coerceAtMost(max)) }) { Icon(Icons.Rounded.Add, "More") }
    }
}

@Composable
private fun DateDialog(initial: LocalDate?, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ state.selectedDateMillis?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
