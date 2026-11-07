package com.awaken.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.awaken.app.data.db.MediaEntity
import com.awaken.app.data.db.PlaylistEntity
import com.awaken.app.data.db.PlaylistItemEntity
import com.awaken.app.data.repo.ImportReport
import com.awaken.app.data.repo.MediaRepository
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.di.AppContainer
import com.awaken.app.playback.PlaybackService
import com.awaken.app.ui.components.ConfirmDialog
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
import com.awaken.core.OrderMode
import com.awaken.core.remote.FeatureFlag
import com.awaken.core.remote.FeaturedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val media: List<MediaEntity> = emptyList(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val items: List<PlaylistItemEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<LibraryState> = combine(
        c.media.observeAll(), c.playlists.observePlaylists(), c.playlists.observeItems(), c.settings.settings,
    ) { m, p, i, s -> LibraryState(m, p, i, s) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    private val _missing = MutableStateFlow<Set<Long>>(emptySet())
    val missing: StateFlow<Set<Long>> = _missing
    private val _report = MutableStateFlow<ImportReport?>(null)
    val report: StateFlow<ImportReport?> = _report
    private val _busy = MutableStateFlow("")
    val busy: StateFlow<String> = _busy
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    fun refreshMissing(items: List<MediaEntity>) = viewModelScope.launch {
        _missing.value = items.filter { !c.media.isAccessible(it) }.map { it.id }.toSet()
    }

    fun import(uris: List<android.net.Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _busy.value = "Checking ${uris.size} file(s)…"
        _report.value = c.media.importUris(uris)
        _busy.value = ""
    }

    fun clearReport() { _report.value = null }
    fun clearMessage() { _message.value = "" }
    fun rename(id: Long, name: String) = viewModelScope.launch { c.media.rename(id, name) }
    fun delete(m: MediaEntity) = viewModelScope.launch { c.media.delete(m) }
    fun setDefault(id: Long) = viewModelScope.launch { c.settings.setDefaultMediaId(id) }
    fun createPlaylist(name: String, mode: OrderMode, ids: List<Long>) = viewModelScope.launch { c.playlists.create(name, mode, ids) }
    fun updatePlaylist(id: Long, name: String, mode: OrderMode, ids: List<Long>) = viewModelScope.launch { c.playlists.update(id, name, mode, ids) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { c.playlists.delete(id) }

    fun downloadFeatured(f: FeaturedMedia) = viewModelScope.launch {
        _busy.value = "Downloading ${f.title}…"
        try {
            c.remote.downloadFeatured(f) { }
            _message.value = "Added '${f.title}' to your library."
        } catch (e: Exception) {
            _message.value = "Could not add '${f.title}': ${e.message}"
        }
        _busy.value = ""
    }
}

@Composable
fun LibraryScreen() {
    val vm = awakenViewModel { LibraryViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val ctx = LocalContext.current
    val tap = rememberHaptics(LocalHapticsEnabled.current)
    var tab by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<MediaEntity?>(null) }
    var deleting by remember { mutableStateOf<MediaEntity?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }

    LaunchedEffect(st.media) { vm.refreshMissing(st.media) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris) }

    ScreenColumn("Media library") {
        GoldButton("Import audio / video", { tap(); picker.launch(MediaRepository.PICKER_MIME_TYPES) }, Modifier.fillMaxWidth(), Icons.Rounded.Add)
        if (busy.isNotEmpty()) Text(busy, color = LocalPalette.current.primary)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)

        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = LocalPalette.current.primary) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Media (${st.media.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Playlists (${st.playlists.size})") })
        }

        if (tab == 0) {
            if (st.media.isEmpty()) {
                EmptyState("No media yet", remote.texts["library.empty"] ?: "Import MP3, WAV, M4A, AAC, MP4 and other audio or video files. They stay on your device.")
            }
            st.media.forEach { m ->
                MediaCard(
                    m, isDefault = m.id == st.settings.defaultMediaId, isMissing = m.id in missing,
                    onPlay = { tap(); PlaybackService.playMedia(ctx, listOf(m.id)) },
                    onDefault = { tap(); vm.setDefault(m.id) },
                    onRename = { renaming = m }, onDelete = { deleting = m },
                )
            }
            if (remote.flag(FeatureFlag.FEATURED_CONTENT) && remote.featuredMedia.isNotEmpty()) {
                SectionLabel("Featured downloads")
                remote.featuredMedia.forEach { f ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.title, style = MaterialTheme.typography.titleMedium)
                                Text("${if (f.isVideo) "Video" else "Audio"} · ${Fmt.size(f.sizeBytes)} · checked against a checksum", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GhostButton("Add", { vm.downloadFeatured(f) }, icon = Icons.Rounded.CloudDownload, enabled = busy.isEmpty())
                        }
                    }
                }
            }
        } else {
            if (remote.flag(FeatureFlag.PLAYLISTS)) {
                GhostButton("New playlist", { creatingPlaylist = true }, Modifier.fillMaxWidth(), Icons.Rounded.Add, enabled = st.media.isNotEmpty())
            }
            if (st.playlists.isEmpty()) EmptyState("No playlists", "Group several files, then assign a playlist to a schedule with sequential or random order.")
            st.playlists.forEach { pl ->
                val ids = st.items.filter { it.playlistId == pl.id }.sortedBy { it.position }.map { it.mediaId }
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.titleMedium)
                            Text("${ids.size} item(s) · ${if (pl.orderMode == "RANDOM") "random" else "in order"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ tap(); PlaybackService.playMedia(ctx, if (pl.orderMode == "RANDOM") ids.shuffled() else ids) }, enabled = ids.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, "Play playlist") }
                        IconButton({ editingPlaylist = pl }) { Icon(Icons.Rounded.Edit, "Edit playlist") }
                        IconButton({ deletingPlaylist = pl }) { Icon(Icons.Rounded.Delete, "Delete playlist") }
                    }
                }
            }
        }
    }

    renaming?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(m.id, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { m ->
        ConfirmDialog("Delete '${m.displayName}'?", "It is removed from AWAKEN and from any playlist or schedule using it. The original file on your device is not touched.", "Delete", { deleting = null }) { vm.delete(m); deleting = null }
    }
    deletingPlaylist?.let { pl ->
        ConfirmDialog("Delete playlist '${pl.name}'?", "Schedules using it will fall back to the default media.", "Delete", { deletingPlaylist = null }) { vm.deletePlaylist(pl.id); deletingPlaylist = null }
    }
    report?.let { r ->
        AlertDialog(
            onDismissRequest = vm::clearReport, title = { Text("Import finished") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.imported.size} added.")
                    r.rejected.forEach { (name, why) -> Text("• $name: $why", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(vm::clearReport) { Text("OK") } },
        )
    }
    if (creatingPlaylist) {
        PlaylistDialog(null, emptyList(), st.media, { creatingPlaylist = false }) { n, mode, ids -> vm.createPlaylist(n, mode, ids); creatingPlaylist = false }
    }
    editingPlaylist?.let { pl ->
        val ids = st.items.filter { it.playlistId == pl.id }.sortedBy { it.position }.map { it.mediaId }
        PlaylistDialog(pl, ids, st.media, { editingPlaylist = null }) { n, mode, newIds -> vm.updatePlaylist(pl.id, n, mode, newIds); editingPlaylist = null }
    }
}

@Composable
private fun MediaCard(
    m: MediaEntity, isDefault: Boolean, isMissing: Boolean,
    onPlay: () -> Unit, onDefault: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    GlassCard(accent = isDefault) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (m.isVideo) Icons.Rounded.Movie else Icons.Rounded.MusicNote, null, tint = p.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("${if (m.isVideo) "Video" else "Audio"} · ${Fmt.duration(m.durationMs)} · ${Fmt.size(m.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDefault) StatusPill("DEFAULT", p.primary)
            }
            if (isMissing) Text("This file can't be opened any more. Re-import it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onPlay, enabled = !isMissing) { Icon(Icons.Rounded.PlayArrow, "Preview") }
                IconButton(onDefault) { Icon(if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Make default", tint = if (isDefault) p.primary else LocalContentColorFallback()) }
                IconButton(onRename) { Icon(Icons.Rounded.Edit, "Rename") }
                IconButton(onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun LocalContentColorFallback(): Color = androidx.compose.material3.LocalContentColor.current

@Composable
private fun PlaylistDialog(
    existing: PlaylistEntity?, initialIds: List<Long>, media: List<MediaEntity>,
    onDismiss: () -> Unit, onSave: (String, OrderMode, List<Long>) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mode by remember { mutableStateOf(if (existing?.orderMode == "RANDOM") OrderMode.RANDOM else OrderMode.SEQUENTIAL) }
    val selected = remember { mutableStateListOf<Long>().apply { addAll(initialIds) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New playlist" else "Edit playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == OrderMode.SEQUENTIAL, { mode = OrderMode.SEQUENTIAL }, { Text("Sequential") })
                    FilterChip(mode == OrderMode.RANDOM, { mode = OrderMode.RANDOM }, { Text("Random") })
                }
                media.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(m.id in selected, { on -> if (on) selected.add(m.id) else selected.remove(m.id) })
                        Text(m.displayName, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(name, mode, selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
