package app.backupcircle.member.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backupcircle.core.ui.BackupCard
import app.backupcircle.core.ui.BackupColors
import app.backupcircle.core.ui.IconBadge
import app.backupcircle.core.ui.ShieldIllustration
import app.backupcircle.core.ui.ShieldMark
import app.backupcircle.core.ui.hiddenLongPress
import app.backupcircle.member.R
import app.backupcircle.member.data.Autostart
import app.backupcircle.member.data.PermissionHealth
import app.backupcircle.member.data.openAppDetails
import app.backupcircle.member.data.openSettingsScreen
import app.backupcircle.member.memberGraph
import app.backupcircle.member.service.ProtectionService
import kotlinx.coroutines.launch

/** M1: two huge buttons; only on first launch. */
@Composable
fun LanguageScreen(onChosen: (String) -> Unit) {
    val graph = LocalContext.current.memberGraph
    val scope = rememberCoroutineScope()
    fun choose(code: String) {
        scope.launch {
            graph.store.setLanguage(code)
            onChosen(code)
        }
    }
    Screen {
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            ShieldIllustration(size = 120.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.lang_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = BackupColors.TextPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(36.dp))
            LanguageButton(stringResource(R.string.lang_english)) { choose("en") }
            Spacer(Modifier.height(18.dp))
            LanguageButton(stringResource(R.string.lang_hindi)) { choose("hi") }
        }
    }
}

@Composable
fun LanguageButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .border(2.dp, BackupColors.Border, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = BackupColors.Primary)
    }
}

/** M2 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onDemoPanel: () -> Unit) {
    val graph = LocalContext.current.memberGraph
    val scope = rememberCoroutineScope()
    Screen {
        Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            ShieldIllustration(size = 240.dp, modifier = Modifier.hiddenLongPress(onTrigger = onDemoPanel))
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = BackupColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            BigButton(stringResource(R.string.welcome_start), onClick = {
                scope.launch {
                    graph.store.setStarted()
                    onStart()
                }
            })
        }
    }
}

/** M3: one card per permission; rechecked every time the screen comes back. */
@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val graph = context.memberGraph
    val scope = rememberCoroutineScope()
    val prefs by graph.store.state.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf(PermissionHealth.check(context)) }
    fun recheck() {
        health = PermissionHealth.check(context)
        graph.repository.recheckHealth()
    }
    LifecycleResumeEffect(Unit) {
        recheck()
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recheck()
        if (!granted) openAppDetails(context)
    }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        recheck()
        // Denied twice: Android stops asking, so the only way left is the app's settings page.
        if (result.values.any { !it }) openAppDetails(context)
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck() }

    Screen {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.perm_header),
                style = MaterialTheme.typography.headlineMedium,
                color = BackupColors.TextPrimary,
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.perm_required))
            PermissionCard(
                Icons.Rounded.Notifications,
                stringResource(R.string.perm_notifications_title),
                stringResource(R.string.perm_notifications_why),
                health.notifications,
            ) {
                if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openAppDetails(context)
            }
            PermissionCard(
                Icons.Rounded.Phone,
                stringResource(R.string.perm_phone_title),
                stringResource(R.string.perm_phone_why),
                health.phone,
            ) { phoneLauncher.launch(PermissionHealth.PHONE_PERMISSIONS) }
            PermissionCard(
                Icons.Rounded.VerifiedUser,
                stringResource(R.string.perm_screening_title),
                stringResource(R.string.perm_screening_why),
                health.callScreening,
            ) {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                    roleLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                } else {
                    openSettingsScreen(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
            }
            PermissionCard(
                Icons.Rounded.Apps,
                stringResource(R.string.perm_usage_title),
                stringResource(R.string.perm_usage_why),
                health.usageAccess,
                hint = stringResource(R.string.perm_usage_hint),
            ) { openUsageAccess(context) }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.perm_recommended))
            PermissionCard(
                Icons.Rounded.BatteryChargingFull,
                stringResource(R.string.perm_battery_title),
                stringResource(R.string.perm_battery_why),
                health.battery,
            ) {
                openSettingsScreen(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }
            if (Autostart.needed) {
                PermissionCard(
                    Icons.Rounded.RestartAlt,
                    stringResource(R.string.perm_autostart_title),
                    stringResource(R.string.perm_autostart_why),
                    prefs.autostartOpened,
                ) {
                    scope.launch { graph.store.setAutostartOpened() }
                    Autostart.open(context)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(20.dp)) {
            if (!health.requiredOk) {
                Text(
                    stringResource(R.string.perm_continue_locked),
                    color = BackupColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
            BigButton(
                stringResource(R.string.perm_continue),
                enabled = health.requiredOk,
                onClick = {
                    scope.launch {
                        graph.store.setPermissionsDone()
                        ProtectionService.start(context)
                        onContinue()
                    }
                },
            )
        }
    }
}

private fun openUsageAccess(context: Context) {
    // Some Android versions open Backup's own page when given the package; others need the list.
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        openSettingsScreen(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = BackupColors.TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    why: String,
    granted: Boolean,
    hint: String? = null,
    onAllow: () -> Unit,
) {
    BackupCard(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(BackupColors.Primary, BackupColors.PrimarySoft, size = 56.dp) {
                Icon(icon, contentDescription = null, tint = BackupColors.Primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = BackupColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(why, style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextSecondary)
                if (hint != null && !granted) {
                    Spacer(Modifier.height(4.dp))
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = BackupColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = BackupColors.Success, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.perm_allowed), style = MaterialTheme.typography.titleLarge, color = BackupColors.Success)
            }
        } else {
            BigButton(stringResource(R.string.perm_allow), onClick = onAllow)
        }
    }
}
