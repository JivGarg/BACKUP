package app.backupcircle.guardian.ui.alert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.BackupTheme
import app.backupcircle.core.ui.ShieldIllustration
import app.backupcircle.core.ui.ShieldMark
import app.backupcircle.core.util.Format
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.TextInputDialog
import app.backupcircle.guardian.ui.alertTitle
import app.backupcircle.guardian.ui.dial
import app.backupcircle.guardian.ui.maskedText
import app.backupcircle.guardian.ui.resolutionText

/**
 * G8: full-screen alert. Shown over the lock screen and turns the screen on
 * (manifest attributes plus the calls below). Either button resolves the alert,
 * which also stops the alarm sound.
 */
class AlertActivity : ComponentActivity() {
    private var eventId by mutableLongStateOf(-1L)
    private var autoCall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        read(intent)
        setContent {
            BackupTheme {
                AlertScreen(eventId, autoCall, onAutoCallHandled = { autoCall = false }, onClose = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        autoCall = intent.getBooleanExtra(EXTRA_AUTO_CALL, false)
    }

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_AUTO_CALL = "auto_call"

        fun intent(context: Context, eventId: Long, autoCall: Boolean = false): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_AUTO_CALL, autoCall)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun AlertScreen(eventId: Long, autoCall: Boolean, onAutoCallHandled: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = context.guardianGraph.repository
    val events by repo.events.collectAsStateWithLifecycle()
    val members by repo.members.collectAsStateWithLifecycle()
    val event = events.firstOrNull { it.id == eventId }
    var askPhone by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf(false) }

    LaunchedEffect(eventId, event == null) {
        if (event == null && eventId >= 0) runCatching { repo.sync() }
    }

    val high = event?.isHigh ?: true
    val background = if (high) {
        Brush.verticalGradient(listOf(Color(0xFFF23D57), Color(0xFFB4122F)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFC24D), Color(0xFFFFA21A)))
    }
    val fg = if (high) Color.White else BackupColors.TextPrimary
    val member = event?.let { e -> members.firstOrNull { it.id == e.memberId } }
    val name = member?.name ?: "Your person"

    fun callNow(phone: String) {
        if (event != null) repo.resolve(event.id, "called")
        dial(context, phone, onDone = onClose)
    }

    LaunchedEffect(autoCall, event?.id) {
        if (autoCall && event != null) {
            onAutoCallHandled()
            val phone = member?.phone
            if (phone.isNullOrBlank()) askPhone = true else callNow(phone)
        }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        ) {
            ShieldIllustration(
                size = 120.dp,
                color = Color.White,
                mark = ShieldMark.Alert,
                markColor = if (high) BackupColors.Danger else BackupColors.Warning,
            )
            if (event == null) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = fg)
                Spacer(Modifier.height(12.dp))
                Text("Loading the alert…", color = fg, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onClose) { Text("Close", color = fg) }
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Text(alertTitle(event, name), style = MaterialTheme.typography.headlineLarge, color = fg)
            Spacer(Modifier.height(24.dp))
            val duration = Format.duration(event.callDurationSeconds)
            Detail(
                Icons.Rounded.PhoneInTalk,
                if (event.callActive) "On a call with an unknown number (${maskedText(event.maskedNumber)}) for $duration"
                else "Was on a call with an unknown number (${maskedText(event.maskedNumber)}) for $duration",
                fg,
            )
            Detail(Icons.Rounded.Payments, "Just opened ${event.appLabel}", fg)
            Detail(Icons.Rounded.AccessTime, Format.clock(context, repo.toLocal(event.occurredAt)), fg)
            Spacer(Modifier.height(28.dp))

            val resolved = resolutionText(event)
            if (resolved != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.2f)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = fg)
                    Spacer(Modifier.width(10.dp))
                    Text(resolved, color = fg, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onClose,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(60.dp).border(2.dp, fg, RoundedCornerShape(50)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                ) { Text("Close", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = { val phone = member?.phone; if (phone.isNullOrBlank()) askPhone = true else callNow(phone) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = if (high) BackupColors.Danger else BackupColors.TextPrimary),
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                ) { Text("Call $name", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        repo.resolve(event.id, "safe")
                        onClose()
                    },
                    shape = RoundedCornerShape(50),
                    border = null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                    modifier = Modifier.fillMaxWidth().height(60.dp).border(2.dp, fg, RoundedCornerShape(50)),
                ) { Text("It's safe, I checked", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { why = !why }) {
                Text("Why am I seeing this?", color = fg, fontWeight = FontWeight.SemiBold)
            }
            if (why) {
                Text(
                    "You get this alert when $name is on a long call with an unknown number and opens a payment app during it or soon after, which is how many phone scams end.",
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    if (askPhone && member != null) {
        TextInputDialog(
            title = "$name's phone number",
            initial = "",
            label = "Phone number",
            confirm = "Save and call",
            phone = true,
            message = "Save it once and the Call button will use it.",
            onDismiss = { askPhone = false },
            onConfirm = { number ->
                repo.updateMember(member.id, phone = number)
                askPhone = false
                callNow(number)
            },
        )
    }
}

@Composable
private fun Detail(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal)
    }
}
