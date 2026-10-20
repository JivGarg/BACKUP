package app.backupcircle.guardian.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.service.ConnectionService
import kotlinx.coroutines.launch

/** Hidden presenter panel (long-press the "Backup Circle" header for 3 seconds). */
@Composable
fun DemoPanelScreen(onBack: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val graph = context.guardianGraph
    val scope = rememberCoroutineScope()
    val address by graph.locator.address.collectAsStateWithLifecycle()
    val status by graph.locator.status.collectAsStateWithLifecycle()
    val connection by graph.repository.connection.collectAsStateWithLifecycle()
    val lines by graph.log.lines.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(address.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(BackupColors.Background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Demo panel", style = MaterialTheme.typography.headlineSmall)
        }
        BackupCard(Modifier.fillMaxWidth()) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "WebSocket: " + when (connection) {
                    ConnState.Connected -> "connected"
                    ConnState.Connecting -> "connecting"
                    ConnState.Disconnected -> "disconnected"
                },
            )
            Text("Server: ${address ?: "none"} (${status.name.lowercase()})")
        }
        BackupCard(Modifier.fillMaxWidth()) {
            Text("Server address", style = MaterialTheme.typography.titleMedium)
            Text("Emulator: 10.0.2.2:8000", color = BackupColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("192.168.1.20:8000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && input.isNotBlank(), onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        graph.locator.setManual(input)
                            .onSuccess { message = "Saved. Connecting to $it…"; graph.ws.reconnectNow() }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Save address") }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    message = "Looking for the server…"
                    scope.launch {
                        val found = graph.locator.rediscover()
                        graph.ws.reconnectNow()
                        message = found?.let { "Found the server at $it" } ?: "No server found by discovery"
                        found?.let { input = it }
                        busy = false
                    }
                }) { Text("Rediscover server") }
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        }
        BackupCard(Modifier.fillMaxWidth()) {
            Text("Connection log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (lines.isEmpty()) Text("Nothing yet", color = BackupColors.TextSecondary)
            lines.reversed().forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = BackupColors.TextPrimary)
            }
        }
        BackupCard(Modifier.fillMaxWidth()) {
            Text("Reset this phone", style = MaterialTheme.typography.titleMedium)
            Text("Forget the name, the people you protect and all events on this phone.", color = BackupColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.buttonColors(containerColor = BackupColors.Danger),
            ) { Text("Reset this phone", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(1.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset this phone?", fontWeight = FontWeight.Bold) },
            text = { Text("Backup Circle will start again from the welcome screen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        ConnectionService.stop(context)
                        graph.repository.resetPhone()
                        onReset()
                    }
                }) { Text("Reset", color = BackupColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            containerColor = Color.White,
        )
    }
}
