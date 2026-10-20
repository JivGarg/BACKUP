package app.backupcircle.member.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.member.R

val SoftGradient = Brush.verticalGradient(listOf(BackupColors.GradientTop, BackupColors.Background))

/** One main action per screen: a big pill, at least 64dp tall, with large bold text. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = BackupColors.Primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun BigOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(50),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = BackupColors.Primary)
    }
}

@Composable
fun Screen(
    modifier: Modifier = Modifier,
    background: Brush = SoftGradient,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(background).safeDrawingPadding(),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

/** Server errors in the phone's language (the server's own messages are English). */
@Composable
fun errorText(e: ApiException?): String = when {
    e == null -> ""
    e.isNetwork || e.code == "no_server" -> androidx.compose.ui.res.stringResource(R.string.error_no_server)
    else -> androidx.compose.ui.res.stringResource(R.string.error_generic)
}
