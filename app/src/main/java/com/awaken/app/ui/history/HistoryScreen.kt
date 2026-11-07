package com.awaken.app.ui.history

import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awaken.app.AppLog
import com.awaken.app.ui.components.ConfirmDialog
import com.awaken.app.ui.components.EmptyState
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.home.HistoryRow
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.ScreenColumn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by container.history.observeRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    var log by remember { mutableStateOf(AppLog.read()) }
    var confirmClear by remember { mutableStateOf(false) }

    ScreenColumn("Playback history", onBack) {
        if (items.isEmpty()) EmptyState("No history yet", "Every scheduled or manual playback is recorded here, including skipped and failed ones.")
        items.groupBy { Instant.ofEpochMilli(it.firedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, rows) ->
            SectionLabel(dayLabel(day))
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { rows.forEach { HistoryRow(it) } }
            }
        }
        if (items.isNotEmpty()) GhostButton("Clear history", { confirmClear = true }, Modifier.fillMaxWidth())

        SectionLabel("Diagnostics")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A small error log kept only on this device. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    log.lines().takeLast(40).joinToString("\n").ifBlank { "No log entries." },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Refresh", { log = AppLog.read() }, Modifier.weight(1f))
                    GhostButton("Copy", {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AWAKEN log", log))
                    }, Modifier.weight(1f))
                    GhostButton("Clear", { AppLog.clear(); log = "" }, Modifier.weight(1f))
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog("Clear history?", "All playback records will be removed.", "Clear", { confirmClear = false }) {
        scope.launch { container.history.clear() }; confirmClear = false
    }
}

private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
}
