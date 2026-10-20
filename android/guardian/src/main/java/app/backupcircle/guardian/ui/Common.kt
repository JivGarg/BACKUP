package app.backupcircle.guardian.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.backupcircle.core.ui.BackupColors
import kotlinx.coroutines.delay

/** Current time, refreshed every second, for live timers. */
@Composable
fun rememberNow(intervalMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = BackupColors.Primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ErrorText(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .background(BackupColors.DangerSoft, RoundedCornerShape(16.dp))
            .padding(14.dp),
        color = BackupColors.TextPrimary,
    )
}

/** A text field dialog, used for names and phone numbers. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String = "Save",
    phone: Boolean = false,
    message: String? = null,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var submit by remember { mutableStateOf(false) }
    LaunchedEffect(submit) {
        if (!submit) return@LaunchedEffect
        saving = true
        error = null
        try {
            onConfirm(value.trim())
        } catch (e: Exception) {
            error = e.message ?: "Something went wrong. Try again."
        }
        saving = false
        submit = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message != null) Text(message, color = BackupColors.TextSecondary)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (phone) KeyboardType.Phone else KeyboardType.Text),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error!!, color = BackupColors.Danger)
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank() && !saving, onClick = { submit = true }) {
                Text(if (saving) "Saving…" else confirm, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color.White,
    )
}

@Composable
fun InfoDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { content() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", fontWeight = FontWeight.SemiBold) } },
        containerColor = Color.White,
    )
}

@Composable
fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text("•", fontWeight = FontWeight.Bold, color = BackupColors.Primary)
        Spacer(Modifier.size(10.dp))
        Text(text)
    }
}

/**
 * Opens the dialer with the number filled in. Over the lock screen it asks the guardian
 * to unlock first; [onDone] runs once the dialer is open (or unlocking was cancelled).
 */
fun dial(context: Context, phone: String?, onDone: () -> Unit = {}) {
    val uri = if (phone.isNullOrBlank()) Uri.parse("tel:") else Uri.parse("tel:" + Uri.encode(phone))
    val open = {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
        onDone()
    }
    val activity = context as? Activity
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    if (activity != null && keyguard != null && keyguard.isKeyguardLocked) {
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
            override fun onDismissCancelled() = onDone()
            override fun onDismissError() = open()
        })
    } else {
        open()
    }
}
