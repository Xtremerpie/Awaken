package com.awaken.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaken.app.ui.theme.LocalPalette
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Haptics respect the Settings switch. Usage: `val tap = rememberHaptics(enabled); tap()`. */
@Composable
fun rememberHaptics(enabled: Boolean): () -> Unit {
    val h = LocalHapticFeedback.current
    return remember(enabled, h) { { if (enabled) h.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

fun Modifier.glow(color: Color, radius: Dp = 160.dp, alpha: Float = 0.22f): Modifier = drawBehind {
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius.toPx(),
        )
    )
}

/** Frosted-glass look: a faint light gradient fill and a hairline gradient border over the black background. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    val border = if (accent) Brush.linearGradient(listOf(p.primary.copy(.8f), p.secondary.copy(.25f)))
    else Brush.linearGradient(listOf(Color.White.copy(.20f), Color.White.copy(.04f)))
    val fill = if (accent) Brush.linearGradient(listOf(p.primary.copy(.14f), p.secondary.copy(.05f)))
    else Brush.verticalGradient(listOf(Color.White.copy(.08f), Color.White.copy(.025f)))
    Box(
        modifier
            .fillMaxWidth()
            .background(fill, shape)
            .border(BorderStroke(1.dp, border), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(18.dp)
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = LocalPalette.current.primary.copy(alpha = .85f),
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = LocalPalette.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color(0xFF1A0E00),
            disabledContainerColor = Color(0xFF2A241D), disabledContentColor = Color(0xFF7A6F60)),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = LocalPalette.current
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, p.primary.copy(alpha = if (enabled) .6f else .2f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.primary),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.background(color.copy(alpha = .16f), CircleShape).border(1.dp, color.copy(alpha = .5f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, color = color, style = MaterialTheme.typography.labelSmall) }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF1A0E00), checkedTrackColor = p.primary,
                uncheckedThumbColor = Color(0xFF9A8F80), uncheckedTrackColor = Color(0xFF241F19), uncheckedBorderColor = Color(0xFF4A4036)),
        )
    }
}

@Composable
fun DayChips(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { d ->
            val on = d in selected
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - d else selected + d) },
                label = { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), maxLines = 1) },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.primary.copy(.9f), selectedLabelColor = Color(0xFF1A0E00)),
            )
        }
    }
}

/** Material 3 time picker in a dialog. */
@Composable
fun TimeDialog(initial: LocalTime, title: String, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton({ onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** The AWAKEN mark: a sun rising over a horizon with level-meter rays. [progress] 0..1 animates it in. */
@Composable
fun AwakenLogo(modifier: Modifier = Modifier, size: Dp = 120.dp, progress: Float = 1f, pulse: Float = 0f) {
    val p = LocalPalette.current
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)
        val cx = 54f; val cy = 66f
        val rise = progress.coerceIn(0f, 1f)
        val sunR = 18f * s
        // glow
        drawCircle(Brush.radialGradient(listOf(p.primary.copy(.30f + .1f * pulse), Color.Transparent), pt(cx, cy), 60f * s), 60f * s, pt(cx, cy))
        // sun (clipped to above the horizon by drawing an arc)
        drawArc(
            Brush.verticalGradient(listOf(p.primary, p.secondary), startY = (cy - 18f) * s, endY = cy * s),
            startAngle = 180f, sweepAngle = 180f * rise, useCenter = true,
            topLeft = pt(cx - 18f, cy - 18f), size = Size(sunR * 2, sunR * 2),
        )
        // horizon
        drawLine(p.primary, pt(28f, 70.5f), pt(80f, 70.5f), 3f * s, StrokeCap.Round)
        drawLine(p.secondary.copy(.6f), pt(42f, 77f), pt(66f, 77f), 2.5f * s, StrokeCap.Round)
        drawLine(p.secondary.copy(.33f), pt(48f, 83f), pt(60f, 83f), 2.5f * s, StrokeCap.Round)
        // rays: angle from horizontal, base radius, length
        val rays = listOf(Triple(90f, 22f, 14f), Triple(60f, 22f, 11f), Triple(120f, 22f, 11f), Triple(30f, 22f, 8f), Triple(150f, 22f, 8f))
        rays.forEachIndexed { i, (deg, r0, len) ->
            val grow = ((rise - i * 0.1f) / 0.5f).coerceIn(0f, 1f)
            val a = Math.toRadians(deg.toDouble())
            val dx = cos(a).toFloat(); val dy = -sin(a).toFloat()
            val l = len * (grow + pulse * 0.12f)
            drawLine(
                if (i == 0) p.primary else if (i < 3) p.primary.copy(.9f) else p.secondary,
                pt(cx + dx * r0, cy + dy * r0), pt(cx + dx * (r0 + l), cy + dy * (r0 + l)), 3.5f * s, StrokeCap.Round,
            )
        }
    }
}

/** Animated analog clock with a gliding second hand and a glowing face. */
@Composable
fun AnalogClock(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val p = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { androidx.compose.runtime.withFrameMillis { now = System.currentTimeMillis() } } }
    val breathe = rememberInfiniteTransition(label = "breathe")
    val glowAlpha by breathe.animateFloat(0.16f, 0.30f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "g")
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(p.primary.copy(glowAlpha), Color.Transparent), c, r * 1.25f), r * 1.25f, c)
        drawCircle(Brush.linearGradient(listOf(Color.White.copy(.10f), Color.White.copy(.02f))), r * .94f, c)
        drawCircle(Brush.sweepGradient(listOf(p.primary, p.secondary, p.primary.copy(.2f), p.primary)), r * .94f, c, style = Stroke(2.dp.toPx()))
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val a = Math.toRadians(i * 6.0 - 90)
            val r1 = r * (if (major) .78f else .85f); val r2 = r * .90f
            drawLine(
                if (major) p.primary else Color.White.copy(.25f),
                Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2),
                if (major) 3.dp.toPx() else 1.dp.toPx(), StrokeCap.Round,
            )
        }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val ms = cal.get(java.util.Calendar.MILLISECOND)
        val sec = cal.get(java.util.Calendar.SECOND) + ms / 1000f
        val min = cal.get(java.util.Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(java.util.Calendar.HOUR) % 12) + min / 60f
        fun hand(angleDeg: Float, len: Float, w: Float, color: Color) {
            val a = Math.toRadians(angleDeg - 90.0)
            drawLine(color, c, Offset(c.x + cos(a).toFloat() * r * len, c.y + sin(a).toFloat() * r * len), w, StrokeCap.Round)
        }
        hand(hr * 30f, .46f, 6.dp.toPx(), Color(0xFFF4EDE2))
        hand(min * 6f, .68f, 4.dp.toPx(), Color(0xFFF4EDE2))
        hand(sec * 6f, .78f, 2.dp.toPx(), p.secondary)
        drawCircle(p.primary, 6.dp.toPx(), c)
        drawCircle(Color(0xFF050505), 2.5.dp.toPx(), c)
    }
}

/** Live audio visualizer bars driven by the playback tap. */
@Composable
fun VisualizerBars(levels: FloatArray, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = (levels.getOrElse(i) { 0f }.coerceIn(0f, 1f) * size.height).coerceAtLeast(3.dp.toPx())
            val x = i * (w + gap)
            drawRoundBar(x, size.height - h, w, h, Brush.verticalGradient(listOf(p.primary, p.secondary), startY = size.height - h, endY = size.height))
        }
    }
}

private fun DrawScope.drawRoundBar(x: Float, y: Float, w: Float, h: Float, brush: Brush) {
    drawRoundRect(brush, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AwakenLogo(size = 72.dp, progress = 1f)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, modifier = modifier.padding(horizontal = 4.dp), fontSize = 30.sp)

/** Splash-friendly one-shot animation value 0→1. */
@Composable
fun rememberEntrance(durationMs: Int = 1400): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(durationMs)) }
    return a.value
}
