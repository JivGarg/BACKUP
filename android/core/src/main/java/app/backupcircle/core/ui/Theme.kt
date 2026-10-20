package app.backupcircle.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BackupColors {
    val Background = Color(0xFFEEF2F7)
    val GradientTop = Color(0xFFDCE6F2)
    val Surface = Color(0xFFFFFFFF)
    val Primary = Color(0xFF1E88FF)
    val Danger = Color(0xFFF23D57)
    val Warning = Color(0xFFFFB020)
    val Success = Color(0xFF22C55E)
    val TextPrimary = Color(0xFF1F2933)
    val TextSecondary = Color(0xFF8A97A6)
    val Border = Color(0xFFE3EAF2)

    // Soft tints derived from the tokens, for info cards and chips.
    val PrimarySoft = Color(0xFFDDEBFF)
    val WarningSoft = Color(0xFFFFF1D6)
    val DangerSoft = Color(0xFFFDE3E7)
    val SuccessSoft = Color(0xFFDDF6E6)
    val WarningText = Color(0xFF8A5A00)
}

private val colors = lightColorScheme(
    primary = BackupColors.Primary,
    onPrimary = Color.White,
    primaryContainer = BackupColors.PrimarySoft,
    onPrimaryContainer = BackupColors.TextPrimary,
    secondary = BackupColors.Primary,
    onSecondary = Color.White,
    secondaryContainer = BackupColors.PrimarySoft,
    onSecondaryContainer = BackupColors.TextPrimary,
    background = BackupColors.Background,
    onBackground = BackupColors.TextPrimary,
    surface = BackupColors.Surface,
    onSurface = BackupColors.TextPrimary,
    surfaceVariant = BackupColors.Background,
    onSurfaceVariant = BackupColors.TextSecondary,
    surfaceContainerLowest = BackupColors.Surface,
    surfaceContainerLow = BackupColors.Surface,
    surfaceContainer = BackupColors.Surface,
    surfaceContainerHigh = BackupColors.Surface,
    surfaceContainerHighest = BackupColors.Background,
    error = BackupColors.Danger,
    onError = Color.White,
    outline = BackupColors.Border,
    outlineVariant = BackupColors.Border,
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.3).toInt()) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, color = Color.Unspecified)

/** Guardian app: normal Material sizes, body text at least 16sp. */
private val normalType = Typography(
    displaySmall = style(34, FontWeight.Bold),
    headlineLarge = style(30, FontWeight.Bold),
    headlineMedium = style(26, FontWeight.Bold),
    headlineSmall = style(22, FontWeight.Bold),
    titleLarge = style(20, FontWeight.SemiBold),
    titleMedium = style(17, FontWeight.SemiBold),
    titleSmall = style(16, FontWeight.SemiBold),
    bodyLarge = style(17),
    bodyMedium = style(16),
    bodySmall = style(14),
    labelLarge = style(16, FontWeight.SemiBold),
    labelMedium = style(14, FontWeight.Medium),
    labelSmall = style(12, FontWeight.Medium),
)

/** Member app: body text at least 18sp and headlines at least 30sp. */
private val largeType = Typography(
    displaySmall = style(40, FontWeight.Bold),
    headlineLarge = style(34, FontWeight.Bold),
    headlineMedium = style(30, FontWeight.Bold),
    headlineSmall = style(26, FontWeight.Bold),
    titleLarge = style(24, FontWeight.Bold),
    titleMedium = style(21, FontWeight.SemiBold),
    titleSmall = style(19, FontWeight.SemiBold),
    bodyLarge = style(20),
    bodyMedium = style(18),
    bodySmall = style(18),
    labelLarge = style(21, FontWeight.Bold),
    labelMedium = style(18, FontWeight.SemiBold),
    labelSmall = style(18, FontWeight.Medium),
)

val BackupShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BackupTheme(large: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = if (large) largeType else normalType,
        shapes = BackupShapes,
        content = content,
    )
}
