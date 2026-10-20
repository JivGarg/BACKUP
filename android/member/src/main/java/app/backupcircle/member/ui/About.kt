package app.backupcircle.member.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.net.GuardianSummary
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.member.BuildConfig
import app.backupcircle.member.R
import app.backupcircle.member.memberGraph
import kotlinx.coroutines.launch

/** M7: what the Backup can and cannot see, the two sharing switches and the Backup list. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.memberGraph
    val scope = rememberCoroutineScope()
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GuardianSummary?>(null) }

    Screen(background = androidx.compose.ui.graphics.SolidColor(Color.White)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium, color = BackupColors.TextPrimary)
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(BackupColors.PrimarySoft).padding(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = BackupColors.Primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.about_info), style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextPrimary)
            }
            Spacer(Modifier.height(12.dp))
            SwitchRow(stringResource(R.string.about_switch_calls), prefs.shareCalls) { scope.launch { graph.store.setShareCalls(it) } }
            HorizontalDivider(color = BackupColors.Border)
            SwitchRow(stringResource(R.string.about_switch_payments), prefs.sharePayments) { scope.launch { graph.store.setSharePayments(it) } }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(BackupColors.SuccessSoft).padding(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = BackupColors.Success, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.about_cannot), style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextPrimary)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.about_backup_list), style = MaterialTheme.typography.titleLarge)
            prefs.guardians.forEach { g ->
                val phone = prefs.guardianPhones[g.guardianId]
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.home_connected), style = MaterialTheme.typography.bodyMedium, color = BackupColors.Success)
                    }
                    if (phone != null) Text(phone, style = MaterialTheme.typography.bodyLarge, color = BackupColors.TextSecondary)
                    RowButton(
                        if (phone == null) stringResource(R.string.about_add_number, g.name) else stringResource(R.string.about_change_number),
                        onClick = { editing = g },
                    )
                }
                HorizontalDivider(color = BackupColors.Border)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = BackupColors.TextSecondary,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { g ->
        PhoneDialog(
            title = stringResource(R.string.about_number_title, g.name),
            initial = prefs.guardianPhones[g.guardianId].orEmpty(),
            onDismiss = { editing = null },
            onSave = { number ->
                scope.launch { graph.store.setGuardianPhone(g.guardianId, number) }
                editing = null
            },
        )
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = BackupColors.TextPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = BackupColors.Primary),
        )
    }
}

@Composable
private fun PhoneDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(30) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_number_hint), style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, modifier = Modifier.heightIn(min = 64.dp)) {
                Text(stringResource(R.string.about_save), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 64.dp)) {
                Text(stringResource(R.string.about_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = Color.White,
    )
}
