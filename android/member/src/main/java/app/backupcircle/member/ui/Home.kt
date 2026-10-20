package app.backupcircle.member.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.ShieldIllustration
import app.backupcircle.core.ui.ShieldMark
import app.backupcircle.core.ui.hiddenLongPress
import app.backupcircle.member.R
import app.backupcircle.member.data.PermissionHealth
import app.backupcircle.member.memberGraph
import kotlinx.coroutines.launch

/** M6: status card, Backups, help card; red Call pill and a small toolbar pinned at the bottom. */
@Composable
fun HomeScreen(onFix: () -> Unit, onAbout: () -> Unit, onAddBackup: () -> Unit, onDemoPanel: () -> Unit) {
    val context = LocalContext.current
    val graph = context.memberGraph
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    val health by graph.repository.health.collectAsStateWithLifecycle()
    var chooseLanguage by remember { mutableStateOf(false) }
    val first = prefs.guardians.firstOrNull()

    Screen {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            StatusCard(health, onFix, onDemoPanel)
            Spacer(Modifier.height(16.dp))
            BackupCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_your_backups), style = MaterialTheme.typography.titleLarge, color = BackupColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (prefs.guardians.isEmpty()) {
                    Text(stringResource(R.string.home_no_backups), style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextSecondary)
                }
                prefs.guardians.forEach { g ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(BackupColors.PrimarySoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(g.name.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BackupColors.Primary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            g.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(Modifier.size(12.dp).clip(CircleShape).background(BackupColors.Success))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_connected), style = MaterialTheme.typography.bodyMedium, color = BackupColors.Success, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BackupCard(Modifier.fillMaxWidth(), onClick = onAbout) {
                Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = BackupColors.Primary, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.home_help), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = null, tint = BackupColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Pinned bottom bar, as in the reference: red pill on the left, white toolbar on the right.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val phone = first?.let { prefs.guardianPhones[it.guardianId] }
                    val uri = if (phone.isNullOrBlank()) Uri.parse("tel:") else Uri.parse("tel:" + Uri.encode(phone))
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, uri)) }
                },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BackupColors.Danger, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 80.dp).shadow(6.dp, RoundedCornerShape(28.dp)),
            ) {
                Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    first?.let { stringResource(R.string.home_call, it.name) } ?: stringResource(R.string.home_call, ""),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                ToolbarIcon(Icons.Rounded.Settings, stringResource(R.string.home_settings), onAbout)
                ToolbarIcon(Icons.Rounded.PersonAdd, stringResource(R.string.home_add_backup), onAddBackup)
                ToolbarIcon(Icons.Rounded.Translate, stringResource(R.string.home_language)) { chooseLanguage = true }
            }
        }
    }

    if (chooseLanguage) {
        LanguageDialog(onDismiss = { chooseLanguage = false })
    }
}

@Composable
private fun StatusCard(health: PermissionHealth, onFix: () -> Unit, onDemoPanel: () -> Unit) {
    val ok = health.requiredOk
    BackupCard(
        Modifier.fillMaxWidth(),
        color = if (ok) Color.White else BackupColors.WarningSoft,
        borderColor = if (ok) BackupColors.Border else BackupColors.Warning,
        contentPadding = 22.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Long-press this shield for 3 seconds to open the presenters' demo panel.
            ShieldIllustration(
                size = 96.dp,
                color = if (ok) BackupColors.Primary else BackupColors.Warning,
                mark = if (ok) ShieldMark.Check else ShieldMark.Alert,
                glow = false,
                modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (ok) R.string.home_protected else R.string.home_paused),
                    style = MaterialTheme.typography.headlineMedium,
                    color = BackupColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ok) stringResource(R.string.home_protected_sub) else stringResource(pausedReason(health)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (ok) BackupColors.TextSecondary else BackupColors.TextPrimary,
                )
            }
        }
        if (!ok) {
            Spacer(Modifier.height(16.dp))
            BigButton(stringResource(R.string.home_fix), onClick = onFix, color = BackupColors.WarningText)
        }
    }
}

private fun pausedReason(h: PermissionHealth): Int = when {
    !h.phone -> R.string.reason_phone
    !h.callScreening -> R.string.reason_screening
    !h.usageAccess -> R.string.reason_usage
    else -> R.string.reason_notifications
}

@Composable
private fun ToolbarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(icon, contentDescription = label, tint = BackupColors.TextPrimary, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val graph = LocalContext.current.memberGraph
    val scope = rememberCoroutineScope()
    fun choose(code: String) {
        scope.launch {
            graph.store.setLanguage(code)
            onDismiss()
            applyLanguage(code)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_dialog_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LanguageButton(stringResource(R.string.lang_english)) { choose("en") }
                LanguageButton(stringResource(R.string.lang_hindi)) { choose("hi") }
            }
        },
        confirmButton = {},
        containerColor = BackupColors.Background,
    )
}

/** Clickable row helper with a 64dp touch target. */
@Composable
fun RowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = BackupColors.Primary,
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 18.dp),
    )
}
