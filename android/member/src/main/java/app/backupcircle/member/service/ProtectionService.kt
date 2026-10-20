package app.backupcircle.member.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.backupcircle.member.R
import app.backupcircle.member.detect.CallSignals
import app.backupcircle.member.memberGraph
import app.backupcircle.member.ui.MainActivity
import app.backupcircle.member.ui.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one foreground service (special use type). It keeps the process alive so the detector,
 * the WebSocket and the offline event queue keep working with the screen off, and shows the
 * low-importance "Backup is protecting you" notification.
 */
class ProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var signals: CallSignals

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val graph = memberGraph
        graph.repository.start()
        graph.runner.start()
        signals = CallSignals(this, graph.runner)
        signals.start()
        // Phone permission may be granted later; register the listeners as soon as it is.
        scope.launch { graph.repository.health.collect { if (it.phone) signals.start() } }
        // Keep the notification in the chosen language.
        scope.launch {
            graph.store.state.map { it.language }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        signals.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val text = localized(this, memberGraph.store.current.language)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(0xFF1E88FF.toInt())
            .setContentTitle(text.getString(R.string.notif_protecting))
            .setContentText(text.getString(R.string.notif_tap))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1

        fun createChannel(context: Context) {
            val name = localized(context, context.memberGraph.store.current.language).getString(R.string.notif_channel)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProtectionService::class.java))
            } catch (_: Exception) {
                // Android may refuse a start from the background; the next launch or boot starts it.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}

/** Restarts protection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.memberGraph.store.current.permissionsDone) ProtectionService.start(context)
    }
}
