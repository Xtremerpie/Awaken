package com.awaken.app.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.awaken.app.di.AppContainer
import com.awaken.core.remote.RemoteConfig
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalRemote = compositionLocalOf { RemoteConfig.EMPTY }
val LocalHapticsEnabled = compositionLocalOf { true }

/** MVVM glue: builds a ViewModel from the hand-written container, scoped to the current nav entry. */
@Composable
inline fun <reified VM : ViewModel> awakenViewModel(key: String? = null, crossinline build: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel<VM>(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(c) as T
        },
    )
}

/** Standard scrolling screen with an optional back arrow, a light title and consistent gutters. */
@Composable
fun ScreenColumn(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp))
            actions()
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        Spacer(Modifier.height(32.dp))
    }
}

object Fmt {
    private val time12 = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    fun time(t: LocalTime): String = time12.format(t)
    fun minutes(m: Int): String = time(LocalTime.of(m / 60, m % 60))

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun countdown(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            d > 0 -> "%dd %02dh %02dm".format(d, h, m)
            h > 0 -> "%d:%02d:%02d".format(h, m, sec)
            else -> "%02d:%02d".format(m, sec)
        }
    }

    fun interval(minutes: Int): String = when {
        minutes % 60 == 0 && minutes >= 60 -> if (minutes == 60) "every hour" else "every ${minutes / 60} hours"
        else -> "every $minutes min"
    }
}
