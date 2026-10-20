package app.backupcircle.member.detect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.telecom.TelecomManager
import app.backupcircle.member.data.EventQueue
import app.backupcircle.member.data.MemberStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Diagnostics(
    val lastPath: DetectionPath? = null,
    val lastUnknown: Boolean? = null,
    val lastCallState: CallState = CallState.IDLE,
    val risk: RiskCall? = null,
)

/**
 * Feeds the [Detector] from one thread, ticks it every second while a risk call or its
 * after-call window is open, and only then polls usage events for payment apps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectorRunner(
    private val context: Context,
    private val store: MemberStore,
    private val queue: EventQueue,
    private val scope: CoroutineScope,
) {
    // Every input is handled on this single thread, so the state machine needs no locks.
    private val thread = Dispatchers.Default.limitedParallelism(1)
    private val detector = Detector(
        config = { store.current.detectorConfig() },
        emit = { type, at, payload, base -> queue.add(type, at, payload, base) },
    )
    private val poller = UsagePoller(context)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    fun start() {
        if (started) return
        started = true
        post { restore() }
    }

    fun onIncoming(number: String?, unknown: Boolean, path: DetectionPath) =
        post { detector.onIncoming(number, unknown, path, now()) }

    fun onCallState(state: CallState) = post { detector.onCallState(state, now()) }

    // ---- simulation buttons: the same inputs a real call produces ----
    fun simulateUnknownCallAnswered() = post {
        val t = now()
        detector.onIncoming(SIMULATED_NUMBER, unknown = true, path = DetectionPath.SIMULATION, now = t)
        detector.onCallState(CallState.RINGING, t)
        detector.onCallState(CallState.OFFHOOK, t)
    }

    fun simulateCallEnded() = post { detector.onCallState(CallState.IDLE, now()) }

    fun simulatePaymentAppOpened() = post {
        store.current.watched.firstOrNull()?.let { detector.onAppForeground(it.packageName, now()) }
    }

    /** "Reset this phone": forget any call being watched. */
    fun reset() = post { detector.restore(null) }

    private fun now() = System.currentTimeMillis()

    private fun post(block: () -> Unit) {
        scope.launch(thread) {
            block()
            afterChange()
        }
    }

    private fun restore() {
        val saved = store.current.risk ?: return
        detector.restore(saved)
        // The app was stopped mid-call and the call is over now: close it properly.
        if (saved.active && !inCall()) detector.onCallState(CallState.IDLE, now())
    }

    private fun inCall(): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (_: SecurityException) {
        false
    }

    private fun afterChange() {
        val risk = detector.risk
        _diagnostics.value = Diagnostics(detector.lastPath, detector.lastUnknown, detector.phoneState, risk)
        if (risk != store.current.risk) scope.launch { store.setRisk(risk) }
        if (detector.watching) {
            if (loopJob?.isActive != true) {
                acquireWakeLock()
                poller.reset(risk!!.answeredAt - 2_000)
                loopJob = scope.launch(thread) { loop() }
            }
        } else {
            loopJob?.cancel()
            loopJob = null
            releaseWakeLock()
        }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive && detector.watching) {
            delay(1_000)
            val t = now()
            detector.tick(t)
            val cfg = store.current
            if (cfg.sharePayments && detector.watching) {
                for (pkg in poller.poll(t, cfg.watched.map { it.packageName }.toSet())) {
                    detector.onAppForeground(pkg, t)
                }
            }
            afterChange()
        }
    }

    // A partial wake lock only during risk windows keeps the 1-second timer honest
    // while the screen is off (CPU sleep would otherwise pause it).
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "backup:risk-window")
            ?.apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        const val SIMULATED_NUMBER = "+91 98765 43210"
    }
}

/**
 * Reads foreground-app events from UsageStatsManager. Windows overlap by a couple of
 * seconds because events can be recorded slightly late; duplicates are dropped.
 */
class UsagePoller(context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var since = 0L
    private val seen = LinkedHashSet<String>()

    fun reset(from: Long) {
        since = from
        seen.clear()
    }

    fun poll(now: Long, watched: Set<String>): List<String> {
        val events = try {
            usage?.queryEvents(since - 2_000, now) ?: return emptyList()
        } catch (_: SecurityException) {
            return emptyList() // usage access was turned off
        }
        val found = mutableListOf<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (event.packageName !in watched || event.timeStamp < since - 2_000) continue
            val key = "${event.packageName}@${event.timeStamp}"
            if (seen.add(key)) found += event.packageName
        }
        while (seen.size > 200) seen.remove(seen.first())
        since = now
        return found
    }
}
