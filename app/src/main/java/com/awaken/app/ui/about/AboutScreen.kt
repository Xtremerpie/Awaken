package com.awaken.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.awaken.app.BuildConfig
import com.awaken.app.ui.components.AwakenLogo
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.theme.LocalPalette

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val remote = LocalRemote.current
    val p = LocalPalette.current

    ScreenColumn("About", onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AwakenLogo(size = 120.dp)
            Text("AWAKEN", style = MaterialTheme.typography.displaySmall, color = p.primary)
            Text(
                "Version ${container.updates.installedVersionName} (${container.updates.installedVersionCode})",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(BuildConfig.APPLICATION_ID, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassCard {
            Text(
                remote.texts["about.blurb"] ?: "A personal audio and alarm platform: scheduled audio and video, hourly reminders and alarm clocks that you control.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("Your data")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bullet("Schedules, playlists, history and settings stay on this device.")
                Bullet("No account, no subscription, no advertising.")
                Bullet("Your media is never uploaded. It is read through Android's file picker, and only the files you choose.")
                Bullet("The only network use is checking the update and configuration addresses you can see in the Update center, and downloading an update or featured file you tap.")
            }
        }

        SectionLabel("What Android allows")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bullet("AWAKEN cannot play after you force-stop it, until you open it again.")
                Bullet("Some phone makers stop background apps on their own. Alarm settings shows what you can change.")
                Bullet("Without the exact-alarm permission, Android may deliver schedules a little late.")
                Bullet("If the phone is off, a trigger is missed. It is logged in History rather than played late.")
            }
        }

        SectionLabel("Updates")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bullet("Remote configuration only changes things the installed app already knows how to do. It never downloads or runs code.")
                Bullet("New features arrive as signed app updates. AWAKEN checks size, checksum, package name, version and signing certificate, then hands the file to Android's own installer. It reports an update as installed only after Android does.")
            }
        }

        SectionLabel("Open-source components")
        GlassCard {
            Text(
                "Kotlin, AndroidX, Jetpack Compose and Material 3, Media3 / ExoPlayer, Room and DataStore. All Apache License 2.0.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
