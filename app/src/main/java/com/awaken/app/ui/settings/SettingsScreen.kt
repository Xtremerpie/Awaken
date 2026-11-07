package com.awaken.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.DndBehavior
import com.awaken.app.data.settings.StartupBehavior
import com.awaken.app.di.AppContainer
import com.awaken.app.ui.components.ConfirmDialog
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.SwitchRow
import com.awaken.app.ui.components.TimeDialog
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.nav.Routes
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.awakenViewModel
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.app.ui.theme.Palettes
import com.awaken.core.remote.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    val hourly = c.schedules.observeAll()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private fun apply(block: suspend () -> Unit) = viewModelScope.launch { block() }
    /** For settings that decide *when* things fire: update the value, then re-arm every alarm. */
    private fun applyAndReschedule(block: suspend () -> Unit) = viewModelScope.launch { block(); c.applySettingsChange() }

    fun setMaster(v: Boolean) = applyAndReschedule { c.settings.setMasterEnabled(v) }
    fun setVolume(v: Float) = apply { c.settings.setDefaultVolume(v) }
    fun setDuration(v: Int) = apply { c.settings.setDefaultDurationSec(v) }
    fun setHourlyInterval(m: Int) = viewModelScope.launch { c.schedules.setDefaultHourlyInterval(m) }
    fun setFadeIn(v: Int) = apply { c.settings.setFadeInMs(v) }
    fun setFadeOut(v: Int) = apply { c.settings.setFadeOutMs(v) }
    fun setGradual(v: Boolean) = apply { c.settings.setGradualVolume(v) }
    fun setGradualSeconds(v: Int) = apply { c.settings.setGradualSeconds(v) }
    fun setQuiet(v: Boolean) = applyAndReschedule { c.settings.setQuietEnabled(v) }
    fun setQuietStart(m: Int) = applyAndReschedule { c.settings.setQuietStart(m) }
    fun setQuietEnd(m: Int) = applyAndReschedule { c.settings.setQuietEnd(m) }
    fun setDnd(v: DndBehavior) = apply { c.settings.setDnd(v) }
    fun setTheme(id: String) = apply { c.settings.setThemeId(id) }
    fun setVibration(v: Boolean) = apply { c.settings.setVibration(v) }
    fun setHaptics(v: Boolean) = apply { c.settings.setHaptics(v) }
    fun setStartup(v: StartupBehavior) = apply { c.settings.setStartup(v) }
    fun setShowSplash(v: Boolean) = apply { c.settings.setShowSplash(v) }
    fun setAutoCheck(v: Boolean) = apply { c.settings.setAutoCheckUpdates(v) }
    fun toggleHourly(id: Long, on: Boolean) = viewModelScope.launch { c.schedules.setEnabled(id, on) }

    fun export(uri: android.net.Uri) = viewModelScope.launch {
        _status.value = try { c.backup.export(uri); "Backup saved." } catch (e: Exception) { "Backup failed: ${e.message}" }
    }

    fun restore(uri: android.net.Uri) = viewModelScope.launch {
        _status.value = try {
            val summary = c.backup.restore(uri)
            c.applySettingsChange()
            summary
        } catch (e: Exception) { "Restore failed, nothing was changed: ${e.message}" }
    }

    fun reset() = viewModelScope.launch {
        _status.value = try { c.resetApplication(); "AWAKEN was reset to its first-install state." } catch (e: Exception) { "Reset failed: ${e.message}" }
    }
}

@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val vm = awakenViewModel { SettingsViewModel(it) }
    val cfg by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val schedules by vm.hourly.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by vm.status.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val p = LocalPalette.current
    val hidden = remote.hiddenSettings
    val hourly = schedules.firstOrNull { it.isDefaultHourly }

    var pickQuietStart by remember { mutableStateOf(false) }
    var pickQuietEnd by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var customInterval by remember(hourly?.intervalMinutes) { mutableStateOf(hourly?.intervalMinutes?.toString() ?: "") }
    var durationText by remember(cfg.defaultDurationSec) { mutableStateOf(cfg.defaultDurationSec.toString()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restore) }

    ScreenColumn("Settings") {
        GlassCard {
            Column {
                NavRow("Alarm settings & reliability") { onNavigate(Routes.ALARM_SETTINGS) }
                NavRow("Update center") { onNavigate(Routes.UPDATE) }
                NavRow("Playback history") { onNavigate(Routes.HISTORY) }
                NavRow("About AWAKEN") { onNavigate(Routes.ABOUT) }
            }
        }

        if (status.isNotEmpty()) GlassCard(accent = true) { Text(status) }

        SwitchCard("AWAKEN enabled", "Master switch. Off means no schedule fires.", cfg.masterEnabled, vm::setMaster)

        if (SettingsSection.PLAYBACK !in hidden) {
            SectionLabel("Playback")
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Default volume  ${(cfg.defaultVolume * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Slider(cfg.defaultVolume, vm::setVolume, colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary))
                    OutlinedTextField(
                        durationText, { t -> durationText = t.filter(Char::isDigit).take(5); vm.setDuration(durationText.toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Default playback duration for new schedules (seconds, 0 = full)") },
                    )
                    Text("Hourly interval", style = MaterialTheme.typography.titleMedium)
                    if (hourly == null) {
                        Text("The default hourly schedule was deleted. Create recurring schedules in Schedules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60, 90, 120, 180).forEach { m ->
                                FilterChip(hourly.intervalMinutes == m, { vm.setHourlyInterval(m) }, { Text(Fmt.interval(m).removePrefix("every ")) })
                            }
                        }
                        OutlinedTextField(
                            customInterval, { t -> customInterval = t.filter(Char::isDigit).take(4); customInterval.toIntOrNull()?.let { if (it in 1..1440) vm.setHourlyInterval(it) } },
                            Modifier.fillMaxWidth(), singleLine = true, label = { Text("Custom interval (minutes)") },
                        )
                        SwitchRow("Hourly playback on", "Start time, end time and days are edited in Schedules", hourly.enabled, { on -> vm.toggleHourly(hourly.id, on) })
                    }
                    Text("Fade-in  ${"%.1f".format(cfg.fadeInMs / 1000f)} s", style = MaterialTheme.typography.titleMedium)
                    Slider(cfg.fadeInMs / 1000f, { vm.setFadeIn((it * 1000).toInt()) }, valueRange = 0f..8f, colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary))
                    Text("Fade-out  ${"%.1f".format(cfg.fadeOutMs / 1000f)} s", style = MaterialTheme.typography.titleMedium)
                    Slider(cfg.fadeOutMs / 1000f, { vm.setFadeOut((it * 1000).toInt()) }, valueRange = 0f..8f, colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary))
                    SwitchRow("Gradual volume increase", "Start quiet and rise over ${cfg.gradualSeconds} s", cfg.gradualVolume, vm::setGradual)
                    if (cfg.gradualVolume) {
                        Slider(cfg.gradualSeconds.toFloat(), { vm.setGradualSeconds(it.toInt()) }, valueRange = 5f..120f, colors = SliderDefaults.colors(thumbColor = p.primary, activeTrackColor = p.primary))
                    }
                    SwitchRow("Vibration", "Vibrate briefly when a schedule fires", cfg.vibration, vm::setVibration)
                }
            }
        }

        if (SettingsSection.QUIET_HOURS !in hidden) {
            SectionLabel("Quiet hours & Do Not Disturb")
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow("Quiet hours", "Recurring schedules stay silent in this window", cfg.quietEnabled, vm::setQuiet)
                    if (cfg.quietEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("From ${Fmt.minutes(cfg.quietStartMinutes)}", { pickQuietStart = true }, Modifier.weight(1f))
                            GhostButton("To ${Fmt.minutes(cfg.quietEndMinutes)}", { pickQuietEnd = true }, Modifier.weight(1f))
                        }
                    }
                    Text("When Do Not Disturb is on", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(cfg.dnd == DndBehavior.SKIP_WHEN_DND, { vm.setDnd(DndBehavior.SKIP_WHEN_DND) }, { Text("Skip playback") })
                        FilterChip(cfg.dnd == DndBehavior.PLAY_ANYWAY, { vm.setDnd(DndBehavior.PLAY_ANYWAY) }, { Text("Play anyway") })
                    }
                }
            }
        }

        if (SettingsSection.APPEARANCE !in hidden) {
            SectionLabel("Appearance & startup")
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (Palettes.builtIn.map { it.id to it.name } + remote.themes.map { it.id to it.name }).forEach { (id, name) ->
                            FilterChip(cfg.themeId == id, { vm.setTheme(id) }, { Text(name) })
                        }
                    }
                    SwitchRow("Haptic feedback", null, cfg.hapticsEnabled, vm::setHaptics)
                    SwitchRow("Show splash screen", null, cfg.showSplash, vm::setShowSplash)
                    Text("Open on", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(cfg.startup == StartupBehavior.DASHBOARD, { vm.setStartup(StartupBehavior.DASHBOARD) }, { Text("Home") })
                        FilterChip(cfg.startup == StartupBehavior.LIBRARY, { vm.setStartup(StartupBehavior.LIBRARY) }, { Text("Library") })
                        FilterChip(cfg.startup == StartupBehavior.SCHEDULES, { vm.setStartup(StartupBehavior.SCHEDULES) }, { Text("Schedules") })
                    }
                    Text("Schedules are always restored after a reboot, regardless of this setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionLabel("Updates")
        GlassCard {
            Column {
                SwitchRow("Check for updates automatically", "Preferences for the update server are in the Update center", cfg.autoCheckUpdates, { on -> vm.setAutoCheck(on) })
                NavRow("Open Update center") { onNavigate(Routes.UPDATE) }
            }
        }

        if (SettingsSection.BACKUP !in hidden) {
            SectionLabel("Backup & restore")
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saves schedules, playlists, settings and references to your media into a file you choose. Media files themselves are not copied: they must still be on the device after a restore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Back up", { exportLauncher.launch("awaken-backup-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json") }, Modifier.weight(1f))
                        GhostButton("Restore", { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f))
                    }
                }
            }
        }

        SectionLabel("Reset")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Removes every schedule, playlist, imported item, history entry and setting, then restores the first-install defaults. Your original files are not touched.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GhostButton("Reset application", { confirmReset = true }, Modifier.fillMaxWidth(), Icons.Rounded.Delete)
            }
        }
    }

    if (pickQuietStart) TimeDialog(LocalTime.of(cfg.quietStartMinutes / 60, cfg.quietStartMinutes % 60), "Quiet hours start", { pickQuietStart = false }) { vm.setQuietStart(it.hour * 60 + it.minute); pickQuietStart = false }
    if (pickQuietEnd) TimeDialog(LocalTime.of(cfg.quietEndMinutes / 60, cfg.quietEndMinutes % 60), "Quiet hours end", { pickQuietEnd = false }) { vm.setQuietEnd(it.hour * 60 + it.minute); pickQuietEnd = false }
    if (confirmReset) ConfirmDialog("Reset AWAKEN?", "This cannot be undone.", "Reset", { confirmReset = false }) { vm.reset(); confirmReset = false }
}

@Composable
private fun SwitchCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    GlassCard(accent = checked) { SwitchRow(title, subtitle, checked, onChecked) }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(Modifier), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.TextButton(onClick, Modifier.weight(1f)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = LocalPalette.current.primary)
        }
    }
}
