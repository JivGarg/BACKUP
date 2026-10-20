package app.backupcircle.member.detect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.backupcircle.member.memberGraph
import app.backupcircle.member.service.ProtectionService

object ContactLookup {
    /** True when the number is saved in the phone's contacts. Nothing leaves the phone. */
    fun isKnown(context: Context, number: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Primary path for the caller's number. Once Backup holds the "Caller ID & spam" role
 * (RoleManager.ROLE_CALL_SCREENING), Android calls onScreenCall for incoming calls before
 * they ring. We only look the number up; the call is always allowed, never silenced or hidden.
 */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        try {
            if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
                val number = details.handle?.schemeSpecificPart
                val hidden = number.isNullOrBlank() || details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
                val unknown = hidden || !ContactLookup.isKnown(this, number!!)
                memberGraph.runner.onIncoming(if (hidden) null else number, unknown, DetectionPath.SCREENING)
                ProtectionService.start(this)
            }
        } finally {
            respondToCall(details, CallResponse.Builder().build())
        }
    }
}

/**
 * Call state (idle / ringing / in a call) from TelephonyCallback on Android 12+ and
 * PhoneStateListener before that, plus the phone-state broadcast as the fallback source
 * of the incoming number (it carries the number only to apps with the call-log permission).
 */
class CallSignals(private val context: Context, private val runner: DetectorRunner) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var callback: Any? = null
    private var receiver: BroadcastReceiver? = null
    private var lastReal: CallState? = null

    private fun hasPhonePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly; registers whatever is not registered yet and is allowed. */
    fun start() {
        if (!hasPhonePermission()) return
        if (callback == null) registerCallState()
        if (receiver == null) registerBroadcast()
    }

    fun stop() {
        try {
            when (val cb = callback) {
                is TelephonyCallback -> if (Build.VERSION.SDK_INT >= 31) telephony?.unregisterTelephonyCallback(cb)
                is PhoneStateListener -> @Suppress("DEPRECATION") telephony?.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
        callback = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        lastReal = null
    }

    private fun onRealState(state: Int) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> CallState.IDLE
        }
        val previous = lastReal
        lastReal = mapped
        // The first callback just reports the current state at registration time.
        if (previous == null || previous == mapped) return
        runner.onCallState(mapped)
    }

    private fun registerCallState() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onRealState(state)
                }
                telephony?.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onRealState(state)
                }
                @Suppress("DEPRECATION")
                telephony?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callback = listener
            }
        } catch (_: SecurityException) {
            callback = null
        }
    }

    private fun registerBroadcast() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
                // Sent twice on Android 10+: once without the number, once with it for apps
                // that hold READ_CALL_LOG. Only the copy with a number is useful.
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (number.isNullOrBlank()) return
                runner.onIncoming(number, !ContactLookup.isKnown(ctx, number), DetectionPath.PHONE_STATE)
            }
        }
        ContextCompat.registerReceiver(
            context,
            r,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = r
    }
}
