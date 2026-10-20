package app.backupcircle.guardian.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.IconBadge
import app.backupcircle.core.ui.ShieldIllustration
import app.backupcircle.core.ui.ShieldMark
import app.backupcircle.core.ui.hiddenLongPress
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.service.ConnectionService
import app.backupcircle.guardian.ui.ErrorText
import app.backupcircle.guardian.ui.PrimaryButton
import kotlinx.coroutines.launch

private val softGradient = Brush.verticalGradient(listOf(BackupColors.GradientTop, BackupColors.Background))

/** G1 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onDemoPanel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(softGradient)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            ShieldIllustration(
                size = 220.dp,
                mark = ShieldMark.People,
                modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "Be there when it matters",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = BackupColors.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Backup Circle tells you when someone you love may be talking to a scammer, so you can call them in time.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = BackupColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton("Get started", onClick = onStart)
        }
    }
}

/** G2 */
@Composable
fun NameScreen(onBack: () -> Unit, onRegistered: () -> Unit, onDemoPanel: () -> Unit) {
    val graph = LocalContext.current.guardianGraph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(graph.store.current.name.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        if (name.isBlank() || loading) return
        loading = true
        error = null
        scope.launch {
            try {
                graph.repository.register(name.trim())
                ConnectionService.start(context)
                onRegistered()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(softGradient).safeDrawingPadding().imePadding().padding(24.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.height(16.dp))
        Text(
            "What should your family call you?",
            style = MaterialTheme.typography.headlineLarge,
            color = BackupColors.TextPrimary,
            modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "They will see this name on their phone, for example \"You're connected to Rohan\".",
            color = BackupColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            placeholder = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
                unfocusedBorderColor = BackupColors.Border,
            ),
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        ErrorText(error)
        Spacer(Modifier.weight(1f))
        PrimaryButton(if (error != null) "Try again" else "Continue", onClick = ::submit, enabled = name.isNotBlank(), loading = loading)
    }
}

private data class AlertPermissionState(val notifications: Boolean, val fullScreen: Boolean, val battery: Boolean)

private fun checkAlertPermissions(context: Context): AlertPermissionState {
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    return AlertPermissionState(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        // Android 14+ lets users turn off full-screen alerts per app.
        fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
        battery = pm.isIgnoringBatteryOptimizations(context.packageName),
    )
}

/** True when any alert permission is missing, for the dashboard banner. */
fun alertPermissionGap(context: Context): Boolean {
    val s = checkAlertPermissions(context)
    return !(s.notifications && s.fullScreen && s.battery)
}

/** G3 */
@Composable
fun AlertPermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.guardianGraph
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(checkAlertPermissions(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(refresh) {
        state = checkAlertPermissions(context)
        onPauseOrDispose { }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = checkAlertPermissions(context)
        if (!it) openAppNotificationSettings(context)
    }

    Column(
        Modifier.fillMaxSize().background(softGradient).safeDrawingPadding().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text("Make sure alerts reach you", style = MaterialTheme.typography.headlineLarge, color = BackupColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "When it matters, this phone should ring loudly, even when it is locked.",
                color = BackupColors.TextSecondary,
            )
            Spacer(Modifier.height(20.dp))
            PermissionCard(
                icon = Icons.Rounded.NotificationsActive,
                title = "Notifications",
                why = "So scam alerts and call updates can reach you.",
                granted = state.notifications,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openAppNotificationSettings(context)
                },
            )
            PermissionCard(
                icon = Icons.Rounded.Fullscreen,
                title = "Full-screen alerts",
                why = "So an alert can light up the screen and show over the lock screen, like an alarm.",
                granted = state.fullScreen,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        openSettings(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                    }
                },
            )
            PermissionCard(
                icon = Icons.Rounded.BatteryChargingFull,
                title = "Run in the background",
                why = "So Backup Circle stays connected when your screen is off.",
                granted = state.battery,
                onAllow = {
                    openSettings(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                },
            )
            if (!(state.notifications && state.fullScreen && state.battery)) {
                Text(
                    "You can continue now and fix the rest later. The dashboard will remind you.",
                    color = BackupColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(
            "Continue",
            onClick = {
                scope.launch {
                    graph.store.setOnboardingDone()
                    ConnectionService.start(context)
                    onDone()
                }
            },
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, why: String, granted: Boolean, onAllow: () -> Unit) {
    BackupCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(BackupColors.Primary, BackupColors.PrimarySoft) {
                Icon(icon, contentDescription = null, tint = BackupColors.Primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = BackupColors.TextPrimary)
                Text(why, color = BackupColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = BackupColors.Success)
                Spacer(Modifier.width(8.dp))
                Text("Allowed", color = BackupColors.Success, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = BackupColors.Primary),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Allow", fontWeight = FontWeight.SemiBold) }
        }
    }
}

fun openAppNotificationSettings(context: Context) {
    openSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) {
        }
    }
}
