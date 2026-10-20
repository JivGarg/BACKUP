package app.backupcircle.guardian.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.net.EventView
import app.backupcircle.guardian.R
import app.backupcircle.guardian.guardianGraph
import app.backupcircle.guardian.ui.MainActivity
import app.backupcircle.guardian.ui.alert.AlertActivity
import app.backupcircle.guardian.ui.alertTitle

class AlertNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ALERTS = "scam_alerts"
        const val CHANNEL_CALLS = "call_activity"
        const val CHANNEL_CONNECTION = "connection"
        const val CONNECTION_ID = 1
        private const val ALERT_BASE = 10_000
        private const val CALL_BASE = 5_000
        private val VIBRATION = longArrayOf(0, 1200, 500, 1200, 500, 1200, 500, 1200, 500, 1200)
    }

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val alerts = NotificationChannel(CHANNEL_ALERTS, "Scam alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings like an alarm when someone you protect may be talking to a scammer"
            // Alarm usage plays on the alarm stream, so it still rings when the phone is on silent.
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val calls = NotificationChannel(CHANNEL_CALLS, "Call activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When someone you protect is on a call with an unknown number"
        }
        val connection = NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows that Backup Circle is connected"
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(alerts, calls, connection))
    }

    private fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun alertId(eventId: Long) = ALERT_BASE + (eventId % 100_000).toInt()
    private fun callId(memberId: String) = CALL_BASE + (memberId.hashCode() and 0xFFF)

    private fun alertBuilder(event: EventView, name: String, body: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context,
            alertId(event.id),
            AlertActivity.intent(context, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val call = PendingIntent.getActivity(
            context,
            alertId(event.id) + 2,
            AlertActivity.intent(context, event.id, autoCall = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val safe = PendingIntent.getBroadcast(
            context,
            alertId(event.id),
            Intent(context, AlertActionReceiver::class.java).putExtra(AlertActionReceiver.EXTRA_EVENT_ID, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(if (event.isHigh) 0xFFF23D57.toInt() else 0xFFFFB020.toInt())
            .setContentTitle(alertTitle(event, name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "Call $name", call)
            .addAction(0, "It's safe, I checked", safe)
            .setAutoCancel(false)
    }

    /** Rings (alarm sound, long vibration) and opens G8 full screen over the lock screen. */
    fun showAlert(event: EventView, name: String, body: String) {
        val fullScreen = PendingIntent.getActivity(
            context,
            alertId(event.id) + 1,
            AlertActivity.intent(context, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = alertBuilder(event, name, body)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()
        // Insistent: the sound repeats until the alert is handled or silenced after 60 s.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        post(alertId(event.id), notification)
    }

    /** After 60 s: stop the sound but keep the alert in the shade. */
    fun silenceAlert(event: EventView, name: String, body: String) {
        manager.cancel(alertId(event.id))
        post(alertId(event.id), alertBuilder(event, name, body).setSilent(true).build())
    }

    fun cancelAlert(eventId: Long) = manager.cancel(alertId(eventId))

    fun showCallActivity(memberId: String, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context,
            callId(memberId),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            callId(memberId),
            NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setColor(0xFFFFB020.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelCallActivity(memberId: String) = manager.cancel(callId(memberId))

    fun cancelAll() = manager.cancelAll()

    fun connectionNotification(state: ConnState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            ConnState.Connected -> "Backup Circle is connected"
            ConnState.Connecting -> "Backup Circle is connecting…"
            ConnState.Disconnected -> "Backup Circle is reconnecting…"
        }
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun updateConnection(state: ConnState) = post(CONNECTION_ID, connectionNotification(state))
}

/** "It's safe, I checked" straight from the notification. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        if (eventId >= 0) context.guardianGraph.repository.resolve(eventId, "safe")
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
