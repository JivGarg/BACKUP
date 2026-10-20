package app.backupcircle.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** White rounded card with a 1dp border, as in the reference screenshots. */
@Composable
fun BackupCard(
    modifier: Modifier = Modifier,
    color: Color = BackupColors.Surface,
    borderColor: Color = BackupColors.Border,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    var base = modifier.clip(shape).background(color).border(1.dp, borderColor, shape)
    if (onClick != null) base = base.clickable(onClick = onClick)
    Column(base.padding(contentPadding), content = content)
}

/** Fully rounded status chip with a dot. */
@Composable
fun StatusPill(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = BackupColors.TextPrimary)
    }
}

/** Light blue-grey gradient with a soft white wave near the bottom (code screens). */
@Composable
fun WaveBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to BackupColors.GradientTop,
                        0.5f to Color(0xFFC9D4E1),
                        1f to BackupColors.GradientTop,
                    ),
                )
                val wave = Path().apply {
                    moveTo(0f, size.height * 0.64f)
                    cubicTo(
                        size.width * 0.30f, size.height * 0.50f,
                        size.width * 0.72f, size.height * 0.48f,
                        size.width, size.height * 0.56f,
                    )
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave, BackupColors.Background)
            },
        content = content,
    )
}

enum class ShieldMark { Check, People, Alert, None }

/** Original shield illustration with a soft glow, drawn from basic shapes. */
@Composable
fun ShieldIllustration(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    color: Color = BackupColors.Primary,
    mark: ShieldMark = ShieldMark.Check,
    glow: Boolean = true,
    markColor: Color = Color.White,
) {
    Canvas(modifier.size(size)) {
        if (glow) {
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.10f), Color.Transparent),
                    center = center,
                    radius = this.size.minDimension * 0.5f,
                ),
                radius = this.size.minDimension * 0.5f,
            )
        }
        val inset = if (glow) this.size.minDimension * 0.2f else 0f
        val box = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        translateBy(inset) {
            drawPath(shieldPath(box), color)
            drawShieldMark(box, mark, markColor)
        }
    }
}

private inline fun DrawScope.translateBy(offset: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(offset, offset)
    block()
    drawContext.transform.translate(-offset, -offset)
}

fun shieldPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w * 0.5f, h * 0.03f)
        lineTo(w * 0.85f, h * 0.16f)
        quadraticTo(w * 0.92f, h * 0.19f, w * 0.92f, h * 0.27f)
        lineTo(w * 0.92f, h * 0.48f)
        cubicTo(w * 0.92f, h * 0.74f, w * 0.74f, h * 0.90f, w * 0.5f, h * 0.98f)
        cubicTo(w * 0.26f, h * 0.90f, w * 0.08f, h * 0.74f, w * 0.08f, h * 0.48f)
        lineTo(w * 0.08f, h * 0.27f)
        quadraticTo(w * 0.08f, h * 0.19f, w * 0.15f, h * 0.16f)
        close()
    }
}

private fun DrawScope.drawShieldMark(box: Size, mark: ShieldMark, white: Color) {
    val w = box.width
    val h = box.height
    when (mark) {
        ShieldMark.Check -> {
            val path = Path().apply {
                moveTo(w * 0.32f, h * 0.51f)
                lineTo(w * 0.45f, h * 0.64f)
                lineTo(w * 0.69f, h * 0.38f)
            }
            drawPath(path, white, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        ShieldMark.People -> {
            drawCircle(white, radius = w * 0.075f, center = Offset(w * 0.39f, h * 0.40f))
            drawCircle(white, radius = w * 0.075f, center = Offset(w * 0.61f, h * 0.40f))
            for (cx in listOf(0.39f, 0.61f)) {
                val body = Path().apply {
                    moveTo(w * (cx - 0.15f), h * 0.67f)
                    cubicTo(w * (cx - 0.15f), h * 0.55f, w * (cx - 0.07f), h * 0.51f, w * cx, h * 0.51f)
                    cubicTo(w * (cx + 0.07f), h * 0.51f, w * (cx + 0.15f), h * 0.55f, w * (cx + 0.15f), h * 0.67f)
                    close()
                }
                drawPath(body, white)
            }
        }
        ShieldMark.Alert -> {
            drawLine(white, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.5f, h * 0.56f), strokeWidth = w * 0.10f, cap = StrokeCap.Round)
            drawCircle(white, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.70f))
        }
        ShieldMark.None -> Unit
    }
}

/**
 * Hidden presenter gesture: fires only after the finger stays down for [durationMs]
 * (3 seconds), so it never triggers by accident.
 */
fun Modifier.hiddenLongPress(durationMs: Long = 3_000, onTrigger: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onTrigger)
    val haptics = LocalHapticFeedback.current
    pointerInput(durationMs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var timedOut = true
            withTimeoutOrNull(durationMs) {
                waitForUpOrCancellation()
                timedOut = false
            }
            if (timedOut) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latest()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

/** Round icon badge used at the start of cards and rows. */
@Composable
fun IconBadge(color: Color, background: Color, size: Dp = 48.dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = BackupColors.TextPrimary,
    )
}
