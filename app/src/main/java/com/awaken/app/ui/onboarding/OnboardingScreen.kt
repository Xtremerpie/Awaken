package com.awaken.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.awaken.app.ui.components.AwakenLogo
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.Permissions
import com.awaken.app.ui.nav.rememberPermissionState
import com.awaken.app.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 3 }
    val p = LocalPalette.current

    fun finish() { scope.launch { container.settings.setOnboardingDone(true); onFinish() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { finish() }) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> Page("Your day, on schedule", "AWAKEN plays the sounds and videos you choose, exactly when you want them: a gentle chime every hour, a morning alarm, a break reminder.") {
                    AwakenLogo(size = 150.dp)
                }
                1 -> Page("Plays every hour, out of the box", "Your first video is already set as the default and an hourly schedule is switched on. Change the interval, set quiet hours and days, or turn it off in Schedules or Settings.") {
                    GlassCard(accent = true) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hourly playback", style = MaterialTheme.typography.titleLarge)
                            Text("every hour · default media", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            StatusPill("ENABLED", p.primary)
                        }
                    }
                }
                else -> ReliabilityPage()
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                Spacer(Modifier.size(4.dp))
                androidx.compose.foundation.layout.Box(
                    Modifier.size(if (pager.currentPage == i) 22.dp else 8.dp, 8.dp)
                        .background(if (pager.currentPage == i) p.primary else Color(0xFF3A3229), CircleShape)
                )
                Spacer(Modifier.size(4.dp))
            }
        }
        GoldButton(
            text = if (pager.currentPage == 2) "Get started" else "Next",
            onClick = { if (pager.currentPage == 2) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Page(title: String, body: String, visual: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        visual()
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReliabilityPage() {
    val ctx = LocalContext.current
    val perms = rememberPermissionState()
    val p = LocalPalette.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Make it reliable", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Android decides whether apps may run with the screen off. These three switches give AWAKEN the best chance. You can change them any time in Alarm settings.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PermRow("Notifications", "Playback controls and failure alerts", perms.notifications, p.primary) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Spacer(Modifier.height(8.dp))
        PermRow("Exact alarms", "Fire on the minute instead of approximately", perms.exactAlarms, p.primary) {
            Permissions.launch(ctx, Permissions.exactAlarmIntent(ctx))
        }
        Spacer(Modifier.height(8.dp))
        PermRow("Unrestricted battery", "Stops the system pausing AWAKEN in the background", perms.batteryUnrestricted, p.primary) {
            Permissions.launch(ctx, Permissions.batteryIntent(ctx))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Honest limits: if you force-stop AWAKEN, or the phone's maker blocks background apps, nothing can play until you open the app again.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermRow(title: String, sub: String, granted: Boolean, accent: Color, onGrant: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) StatusPill("ON", accent) else GhostButton("Allow", onGrant)
        }
    }
}
