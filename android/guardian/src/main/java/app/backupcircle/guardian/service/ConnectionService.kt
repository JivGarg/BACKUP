package app.backupcircle.guardian.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.notify.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (special use) that keeps the process, and so the WebSocket, alive
 * while the screen is off. Incoming alerts wake the phone and ring within seconds.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val graph = guardianGraph
        val notification = graph.notifier.connectionNotification(graph.repository.connection.value)
        ServiceCompat.startForeground(
            this,
            AlertNotifier.CONNECTION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        graph.repository.start()
        scope.launch {
            graph.repository.connection.collect { graph.notifier.updateConnection(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background on some versions; the next app launch starts it.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

/** Restarts the connection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.guardianGraph.store.current
        if (prefs.name != null && prefs.onboardingDone) ConnectionService.start(context)
    }
}
