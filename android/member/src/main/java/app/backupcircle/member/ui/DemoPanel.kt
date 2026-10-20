package app.backupcircle.member.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.util.Format
import app.backupcircle.member.R
import app.backupcircle.member.data.DEFAULT_WATCHED_APPS
import app.backupcircle.member.data.WatchedApp
import app.backupcircle.member.detect.CallState
import app.backupcircle.member.detect.DetectionPath
import app.backupcircle.member.memberGraph
import app.backupcircle.member.service.ProtectionService
import kotlinx.coroutines.launch

/** Hidden presenter panel (long-press the shield for 3 seconds). */
@Composable
fun DemoPanelScreen(onBack: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val graph = context.memberGraph
    val scope = rememberCoroutineScope()
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    val diag by graph.runner.diagnostics.collectAsStateWithLifecycle()
    val health by graph.repository.health.collectAsStateWithLifecycle()
    val connection by graph.repository.connection.collectAsStateWithLifecycle()
    val address by graph.locator.address.collectAsStateWithLifecycle()
    val pending by graph.queue.pending.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(address.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val searching = stringResource(R.string.demo_searching)
    val notFound = stringResource(R.string.demo_not_found)
    val badAddress = stringResource(R.string.demo_bad_address)
    val foundFormat = stringResource(R.string.demo_found, "%s")
    val savedFormat = stringResource(R.string.demo_saved, "%s")

    Screen {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(30.dp))
                }
                Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineSmall)
            }

            Section(stringResource(R.string.demo_threshold)) {
                Options(listOf(30, 60, 120, 300), prefs.thresholdSeconds, label = { s ->
                    if (s < 60) stringResource(R.string.demo_seconds, s) else stringResource(R.string.demo_minutes, s / 60)
                }) { scope.launch { graph.store.setThreshold(it) } }
            }
            Section(stringResource(R.string.demo_window)) {
                Options(listOf(5, 15, 30), prefs.windowMinutes, label = { stringResource(R.string.demo_minutes, it) }) {
                    scope.launch { graph.store.setWindow(it) }
                }
            }

            Section(stringResource(R.string.demo_watched)) {
                prefs.watched.forEach { app ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = BackupColors.TextSecondary)
                        }
                        IconButton(
                            onClick = { scope.launch { graph.store.setWatched(prefs.watched - app) } },
                            modifier = Modifier.size(56.dp),
                        ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.demo_remove)) }
                    }
                    HorizontalDivider(color = BackupColors.Border)
                }
                Row {
                    RowButton(stringResource(R.string.demo_add_app), onClick = { picking = true })
                    RowButton(stringResource(R.string.demo_reset_list), onClick = { scope.launch { graph.store.setWatched(DEFAULT_WATCHED_APPS) } })
                }
            }

            Section(stringResource(R.string.demo_diagnostics)) {
                val yes = stringResource(R.string.demo_yes)
                val no = stringResource(R.string.demo_no)
                val none = stringResource(R.string.demo_none)
                Line(stringResource(R.string.demo_server), address ?: none)
                Line(
                    stringResource(R.string.demo_connection),
                    stringResource(
                        when (connection) {
                            ConnState.Connected -> R.string.demo_connected
                            ConnState.Connecting -> R.string.demo_connecting
                            ConnState.Disconnected -> R.string.demo_disconnected
                        },
                    ),
                )
                Line(
                    stringResource(R.string.demo_last_path),
                    when (diag.lastPath) {
                        DetectionPath.SCREENING -> stringResource(R.string.demo_path_screening)
                        DetectionPath.PHONE_STATE -> stringResource(R.string.demo_path_broadcast)
                        DetectionPath.SIMULATION -> stringResource(R.string.demo_path_simulation)
                        null -> none
                    },
                )
                Line(stringResource(R.string.demo_last_unknown), diag.lastUnknown?.let { if (it) yes else no } ?: none)
                Line(
                    stringResource(R.string.demo_last_state),
                    stringResource(
                        when (diag.lastCallState) {
                            CallState.IDLE -> R.string.state_idle
                            CallState.RINGING -> R.string.state_ringing
                            CallState.OFFHOOK -> R.string.state_offhook
                        },
                    ),
                )
                val risk = diag.risk
                Line(
                    stringResource(R.string.demo_risk),
                    when {
                        risk == null -> stringResource(R.string.demo_risk_none)
                        risk.active -> stringResource(R.string.demo_risk_call)
                        else -> stringResource(R.string.demo_risk_window, Format.clock(context, risk.windowUntil ?: 0))
                    },
                )
                Line(stringResource(R.string.demo_pending), pending.toString())
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.demo_permissions), style = MaterialTheme.typography.titleMedium)
                Line(stringResource(R.string.perm_notifications_title), if (health.notifications) "✓" else "✗")
                Line(stringResource(R.string.perm_phone_title), if (health.phone) "✓" else "✗")
                Line(stringResource(R.string.perm_screening_title), if (health.callScreening) "✓" else "✗")
                Line(stringResource(R.string.perm_usage_title), if (health.usageAccess) "✓" else "✗")
                Line(stringResource(R.string.perm_battery_title), if (health.battery) "✓" else "✗")
            }

            Section(stringResource(R.string.demo_simulate)) {
                BigButton(stringResource(R.string.demo_sim_answer), onClick = { graph.runner.simulateUnknownCallAnswered() })
                Spacer(Modifier.height(10.dp))
                BigButton(stringResource(R.string.demo_sim_end), onClick = { graph.runner.simulateCallEnded() })
                Spacer(Modifier.height(10.dp))
                BigButton(stringResource(R.string.demo_sim_payment), onClick = { graph.runner.simulatePaymentAppOpened() }, color = BackupColors.Danger)
            }

            Section(stringResource(R.string.demo_server_address)) {
                Text(stringResource(R.string.demo_emulator_hint), style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextSecondary)
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
                Row {
                    RowButton(stringResource(R.string.demo_save_address), onClick = {
                        if (busy) return@RowButton
                        busy = true
                        scope.launch {
                            graph.locator.setManual(input)
                                .onSuccess { message = savedFormat.format(it); graph.ws.reconnectNow() }
                                .onFailure { message = badAddress }
                            busy = false
                        }
                    })
                    RowButton(stringResource(R.string.demo_rediscover), onClick = {
                        if (busy) return@RowButton
                        busy = true
                        message = searching
                        scope.launch {
                            val found = graph.locator.rediscover()
                            graph.ws.reconnectNow()
                            message = found?.let { foundFormat.format(it) } ?: notFound
                            found?.let { input = it }
                            busy = false
                        }
                    })
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            BigButton(stringResource(R.string.demo_reset_phone), onClick = { confirmReset = true }, color = BackupColors.Danger)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (picking) {
        AppPicker(
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                if (prefs.watched.none { it.packageName == app.packageName }) {
                    scope.launch { graph.store.setWatched(prefs.watched + app) }
                }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text(stringResource(R.string.demo_reset_confirm), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        ProtectionService.stop(context)
                        graph.repository.stop()
                        graph.runner.reset()
                        graph.queue.clear()
                        graph.store.clearAll()
                        clearLanguage()
                        onReset()
                    }
                }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.demo_reset), color = BackupColors.Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.about_cancel))
                }
            },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    BackupCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = BackupColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun <T> Options(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelMedium,
                color = if (on) Color.White else BackupColors.TextPrimary,
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) BackupColors.Primary else Color.White)
                    .border(1.dp, if (on) BackupColors.Primary else BackupColors.Border, RoundedCornerShape(50))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

/** Every launchable app on the phone, so testers can watch any app (e.g. Chrome on an emulator). */
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (WatchedApp) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { WatchedApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.demo_pick_app), style = MaterialTheme.typography.headlineSmall) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onPick(app) }.padding(vertical = 8.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = BackupColors.TextSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_cancel)) } },
        containerColor = Color.White,
    )
}
