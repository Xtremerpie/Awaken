package com.awaken.app.ui.splash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaken.app.ui.components.AwakenLogo
import com.awaken.app.ui.components.glow
import com.awaken.app.ui.components.rememberEntrance
import com.awaken.app.ui.nav.LocalRemote
import com.awaken.app.ui.theme.LocalPalette
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(ready: Boolean, fullSplash: Boolean = true, onFinished: () -> Unit) {
    val p = LocalPalette.current
    val progress = rememberEntrance(1300)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    var minShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if (fullSplash) 1600 else 300); minShown = true }
    LaunchedEffect(ready, minShown) { if (ready && minShown) onFinished() }

    val tagline = LocalRemote.current.branding?.tagline ?: "Rise on your own schedule."
    Box(Modifier.fillMaxSize().glow(p.secondary, radius = 420.dp, alpha = 0.10f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AwakenLogo(size = 168.dp, progress = progress, pulse = pulse)
            Spacer(Modifier.height(20.dp))
            Text(
                "AWAKEN",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, letterSpacing = 12.sp),
                color = p.primary,
                modifier = Modifier.alpha(progress),
            )
            Spacer(Modifier.height(8.dp))
            Text(tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(progress))
            Spacer(Modifier.size(24.dp))
        }
    }
}
