package app.backupcircle.guardian.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.guardian.BuildConfig
import app.backupcircle.guardian.data.MemberState
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.Bullet
import app.backupcircle.guardian.ui.InfoDialog
import app.backupcircle.guardian.ui.TextInputDialog
import app.backupcircle.guardian.ui.onboarding.openAppNotificationSettings
import kotlinx.coroutines.launch

/** G10: bottom sheet with a drag handle, a centred title and outline-icon rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, onOpen: (HomeSheet) -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            SettingsRow(Icons.Outlined.Person, "My name") { onOpen(HomeSheet.MyName) }
            SettingsRow(Icons.Outlined.Campaign, "Notification settings") { openAppNotificationSettings(context) }
            SettingsRow(Icons.Outlined.Group, "People you protect") { onOpen(HomeSheet.People) }
            SettingsRow(Icons.Outlined.Wifi, "Server connection") { onOpen(HomeSheet.Server) }
            SettingsRow(Icons.AutoMirrored.Outlined.HelpOutline, "Help") { onOpen(HomeSheet.Help) }
            SettingsRow(Icons.Outlined.Info, "About") { onOpen(HomeSheet.About) }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = BackupColors.TextSecondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, color = BackupColors.TextPrimary)
    }
}

private enum class PersonEdit { Rename, Phone }

/** People you protect: rename, phone number, unlink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeopleSheet(members: List<MemberState>, onDismiss: () -> Unit) {
    val repo = LocalContext.current.guardianGraph.repository
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Pair<MemberState, PersonEdit>?>(null) }
    var removing by remember { mutableStateOf<MemberState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text("People you protect", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (members.isEmpty()) Text("Nobody yet. Use Add person on the dashboard.", color = BackupColors.TextSecondary)
            members.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = BackupColors.Border)
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleLarge)
                    Text(m.phone ?: "No phone number saved", color = BackupColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = m to PersonEdit.Rename }) { Text("Rename") }
                        TextButton(onClick = { editing = m to PersonEdit.Phone }) { Text("Phone number") }
                        TextButton(onClick = { removing = m }) { Text("Remove", color = BackupColors.Danger) }
                    }
                }
            }
            error?.let { Text(it, color = BackupColors.Danger) }
        }
    }
    editing?.let { (member, what) ->
        TextInputDialog(
            title = if (what == PersonEdit.Rename) "What do you call them?" else "${member.name}'s phone number",
            initial = if (what == PersonEdit.Rename) member.displayName.orEmpty() else member.phone.orEmpty(),
            label = if (what == PersonEdit.Rename) "Name" else "Phone number",
            phone = what == PersonEdit.Phone,
            onDismiss = { editing = null },
            onConfirm = { value ->
                if (what == PersonEdit.Rename) repo.updateMember(member.id, displayName = value)
                else repo.updateMember(member.id, phone = value)
                editing = null
            },
        )
    }
    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop protecting ${member.name}?", fontWeight = FontWeight.Bold) },
            text = { Text("You will no longer get alerts from ${member.name}'s phone. You can connect again with a new code.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        try {
                            repo.unlink(member.id)
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Remove", color = BackupColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            containerColor = Color.White,
        )
    }
}

/** Server connection: status, address, rediscover. */
@Composable
internal fun ServerDialog(onDismiss: () -> Unit) {
    val graph = LocalContext.current.guardianGraph
    val scope = rememberCoroutineScope()
    val address by graph.locator.address.collectAsStateWithLifecycle()
    val connection by graph.repository.connection.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server connection", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (connection) {
                        ConnState.Connected -> "Connected"
                        ConnState.Connecting -> "Connecting…"
                        ConnState.Disconnected -> "Not connected"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (connection == ConnState.Connected) BackupColors.Success else BackupColors.WarningText,
                )
                Text("Address: ${address ?: "not found yet"}")
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for the server on this Wi-Fi…")
                    }
                }
                result?.let { Text(it, color = BackupColors.TextSecondary) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                result = null
                scope.launch {
                    val found = graph.locator.rediscover()
                    graph.ws.reconnectNow()
                    result = if (found != null) "Found the server at $found." else "No server found. Check that the laptop server is running and this phone is on the same Wi-Fi."
                    busy = false
                }
            }) { Text("Rediscover server", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Color.White,
    )
}

@Composable
internal fun HelpDialog(onDismiss: () -> Unit) {
    InfoDialog("How alerts work", onDismiss) {
        Column {
            Bullet("Backup on their phone notices when they answer a call from a number that is not in their contacts.")
            Bullet("If that call gets long, you get a heads-up. If they open a payment app during it or soon after, this phone rings with an alert.")
            Bullet("Call them, or tap \"It's safe, I checked\". Backup never records calls or reads messages.")
        }
    }
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    InfoDialog("About", onDismiss) {
        Text("Backup Circle ${BuildConfig.VERSION_NAME}\nDemo build. Works on the same Wi-Fi as the Backup demo server.")
    }
}
