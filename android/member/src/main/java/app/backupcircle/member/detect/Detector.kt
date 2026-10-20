package app.backupcircle.member.detect

import app.backupcircle.core.net.EventTypes
import app.backupcircle.core.util.Format
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

enum class CallState { IDLE, RINGING, OFFHOOK }

/** Which signal told us about the last incoming call. */
enum class DetectionPath { SCREENING, PHONE_STATE, SIMULATION }

data class DetectorConfig(
    val thresholdSeconds: Int,
    val windowMinutes: Int,
    /** package name -> label shown to the guardian */
    val watched: Map<String, String>,
    val shareCalls: Boolean,
    val sharePayments: Boolean,
)

/** The unknown call being watched, from the moment it is answered until its after-call window closes. */
@Serializable
data class RiskCall(
    val callId: String,
    val maskedNumber: String,
    val answeredAt: Long,
    val longSent: Boolean = false,
    val mediumSent: Boolean = false,
    val highSent: Boolean = false,
    val endedAt: Long? = null,
    val windowUntil: Long? = null,
) {
    val active: Boolean get() = endedAt == null
}

data class IncomingInfo(val number: String?, val unknown: Boolean, val path: DetectionPath, val at: Long)

/**
 * The one detector state machine. Real signals (call screening, phone-state broadcast,
 * call-state listener, usage events) and the demo panel's simulation buttons all call
 * the same methods, so both behave identically downstream.
 *
 * Rules:
 *  - An answered ringing call from an unknown number starts a risk call: call_started.
 *  - Contacts, missed calls and outgoing calls send nothing.
 *  - At the threshold: long_unknown_call, once.
 *  - When it ends: call_ended; if it reached the threshold, an after-call window stays open.
 *  - A watched app in the foreground during the call before the threshold: medium alert;
 *    after the threshold or during the window: high alert. At most one of each per call.
 */
class Detector(
    private val config: () -> DetectorConfig,
    private val emit: (type: String, occurredAt: Long, payload: JsonObject, elapsedBase: Long?) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var phoneState: CallState = CallState.IDLE
        private set
    var incoming: IncomingInfo? = null
        private set
    var risk: RiskCall? = null
        private set

    // Diagnostics for the demo panel.
    var lastPath: DetectionPath? = null
        private set
    var lastUnknown: Boolean? = null
        private set

    /** Restores a risk call saved before the app was restarted. */
    fun restore(saved: RiskCall?) {
        risk = saved
    }

    /** The number of the ringing call, from call screening, the phone-state broadcast or a simulation. */
    fun onIncoming(number: String?, unknown: Boolean, path: DetectionPath, now: Long) {
        val current = incoming
        // Call screening already judged this ring; the broadcast only fills gaps.
        if (current != null && current.path == DetectionPath.SCREENING && path == DetectionPath.PHONE_STATE &&
            now - current.at < RING_INFO_MAX_AGE_MS
        ) return
        incoming = IncomingInfo(number, unknown, path, now)
        lastPath = path
        lastUnknown = unknown
    }

    fun onCallState(state: CallState, now: Long) {
        val previous = phoneState
        phoneState = state
        when (state) {
            CallState.RINGING -> Unit
            // RINGING -> OFFHOOK means an incoming call was answered. IDLE -> OFFHOOK is outgoing.
            CallState.OFFHOOK -> if (previous == CallState.RINGING) onAnswered(now)
            CallState.IDLE -> {
                val r = risk
                if (r != null && r.active) onEnded(r, now)
                incoming = null
            }
        }
    }

    private fun onAnswered(now: Long) {
        val info = incoming?.takeIf { now - it.at < RING_INFO_MAX_AGE_MS }
        // Neither path could read the number: it was hidden or private, which counts as unknown.
        val unknown = info?.unknown ?: true
        lastUnknown = unknown
        if (info == null) lastPath = null
        if (!unknown) return
        if (risk?.active == true) return // already watching a call (call waiting)
        val call = RiskCall(callId = newId(), maskedNumber = Format.maskNumber(info?.number), answeredAt = now)
        risk = call
        if (config().shareCalls) {
            emit(
                EventTypes.CALL_STARTED,
                now,
                payload("call_id" to call.callId, "masked_number" to call.maskedNumber, "elapsed_seconds" to 0),
                now,
            )
        }
    }

    private fun onEnded(call: RiskCall, now: Long) {
        val cfg = config()
        val duration = (now - call.answeredAt) / 1000
        if (cfg.shareCalls) {
            emit(EventTypes.CALL_ENDED, now, payload("call_id" to call.callId, "duration_seconds" to duration), null)
        }
        risk = if (duration >= cfg.thresholdSeconds) {
            call.copy(endedAt = now, windowUntil = now + cfg.windowMinutes * 60_000L)
        } else {
            null
        }
    }

    /** Called every second while a risk call or its after-call window is open. */
    fun tick(now: Long) {
        val call = risk ?: return
        val cfg = config()
        if (call.active) {
            val elapsed = (now - call.answeredAt) / 1000
            if (!call.longSent && elapsed >= cfg.thresholdSeconds) {
                risk = call.copy(longSent = true)
                if (cfg.shareCalls) {
                    emit(
                        EventTypes.LONG_UNKNOWN_CALL,
                        now,
                        payload("call_id" to call.callId, "elapsed_seconds" to elapsed, "threshold_seconds" to cfg.thresholdSeconds),
                        call.answeredAt,
                    )
                }
            }
        } else if (call.windowUntil == null || now >= call.windowUntil) {
            risk = null
        }
    }

    /** A watched (or any) app came to the foreground. */
    fun onAppForeground(packageName: String, now: Long) {
        val call = risk ?: return
        val cfg = config()
        val label = cfg.watched[packageName] ?: return
        val level: String
        val callSeconds: Long
        if (call.active) {
            callSeconds = (now - call.answeredAt) / 1000
            level = if (callSeconds >= cfg.thresholdSeconds) "high" else "medium"
        } else {
            val until = call.windowUntil ?: return
            if (now > until) return
            callSeconds = ((call.endedAt ?: now) - call.answeredAt) / 1000
            level = "high"
        }
        if (level == "high" && call.highSent) return
        if (level == "medium" && call.mediumSent) return
        risk = if (level == "high") call.copy(highSent = true) else call.copy(mediumSent = true)
        if (cfg.sharePayments) {
            emit(
                EventTypes.RISK_ALERT,
                now,
                payload(
                    "call_id" to call.callId,
                    "level" to level,
                    "app_package" to packageName,
                    "app_label" to label,
                    "call_duration_seconds" to callSeconds,
                    "call_active" to call.active,
                    "masked_number" to call.maskedNumber,
                ),
                null,
            )
        }
    }

    /** Usage polling is needed only while a risk call or its window is open. */
    val watching: Boolean get() = risk != null

    companion object {
        /** Ring information older than this belongs to some other call. */
        const val RING_INFO_MAX_AGE_MS = 120_000L

        fun payload(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
            pairs.associate { (key, value) ->
                key to when (value) {
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            },
        )
    }
}
