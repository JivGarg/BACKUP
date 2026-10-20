package app.backupcircle.member.data

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.backupcircle.core.net.HealthKeys

/** What the detector needs, as reported to the guardian dashboard. */
data class PermissionHealth(
    val notifications: Boolean,
    val phone: Boolean,
    val callScreening: Boolean,
    val usageAccess: Boolean,
    val battery: Boolean,
) {
    val requiredOk: Boolean get() = notifications && phone && callScreening && usageAccess

    fun toMap(): Map<String, Boolean> = mapOf(
        HealthKeys.NOTIFICATIONS to notifications,
        HealthKeys.PHONE to phone,
        HealthKeys.CALL_SCREENING to callScreening,
        HealthKeys.USAGE_ACCESS to usageAccess,
        HealthKeys.BATTERY to battery,
    )

    companion object {
        val PHONE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )

        fun check(context: Context): PermissionHealth = PermissionHealth(
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            phone = PHONE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            callScreening = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true,
            usageAccess = hasUsageAccess(context),
            battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )

        /** Usage access is an app-op, not a runtime permission; the user grants it in Settings. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }
    }
}

/** Xiaomi, Redmi, POCO, Oppo, Vivo and Realme phones stop background apps unless autostart is allowed. */
object Autostart {
    private val brands = setOf("xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "iqoo")

    val needed: Boolean
        get() = Build.MANUFACTURER.lowercase() in brands || Build.BRAND.lowercase() in brands

    private val screens = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    )

    /** Tries each known autostart screen; falls back to the app's details page. */
    fun open(context: Context) {
        for (component in screens) {
            try {
                context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        openAppDetails(context)
    }
}

fun openAppDetails(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

fun openSettingsScreen(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        openAppDetails(context)
    }
}
