package app.backupcircle.member.detect

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetectorTest {
    private data class Sent(val type: String, val at: Long, val payload: JsonObject, val elapsedBase: Long?)

    private val sent = mutableListOf<Sent>()
    private var config = DetectorConfig(
        thresholdSeconds = 60,
        windowMinutes = 15,
        watched = mapOf("com.phonepe.app" to "PhonePe", "net.one97.paytm" to "Paytm"),
        shareCalls = true,
        sharePayments = true,
    )
    private lateinit var detector: Detector

    @Before
    fun setUp() {
        sent.clear()
        detector = Detector({ config }, { t, at, p, base -> sent += Sent(t, at, p, base) }, newId = { "call-1" })
    }

    private fun types() = sent.map { it.type }

    private fun answerUnknown(at: Long, number: String? = "+91 98765 43210", path: DetectionPath = DetectionPath.SCREENING) {
        detector.onIncoming(number, unknown = true, path = path, now = at)
        detector.onCallState(CallState.RINGING, at)
        detector.onCallState(CallState.OFFHOOK, at + 2_000)
    }

    private fun runUntil(from: Long, to: Long) {
        var t = from
        while (t <= to) {
            detector.tick(t)
            t += 1_000
        }
    }

    @Test
    fun unknownAnsweredCallStartsRiskCall() {
        answerUnknown(0)
        assertEquals(listOf("call_started"), types())
        val p = sent[0].payload
        assertEquals("••••3210", p["masked_number"]!!.jsonPrimitive.content)
        assertEquals(0L, p["elapsed_seconds"]!!.jsonPrimitive.long)
        assertEquals(2_000L, sent[0].elapsedBase)
    }

    @Test
    fun contactsMissedAndOutgoingCallsSendNothing() {
        // Saved contact, answered.
        detector.onIncoming("+91 90000 00001", unknown = false, path = DetectionPath.SCREENING, now = 0)
        detector.onCallState(CallState.RINGING, 0)
        detector.onCallState(CallState.OFFHOOK, 1_000)
        detector.onCallState(CallState.IDLE, 400_000)
        // Unknown, missed.
        detector.onIncoming("+91 90000 00002", unknown = true, path = DetectionPath.SCREENING, now = 500_000)
        detector.onCallState(CallState.RINGING, 500_000)
        detector.onCallState(CallState.IDLE, 520_000)
        // Outgoing.
        detector.onCallState(CallState.OFFHOOK, 600_000)
        runUntil(600_000, 700_000)
        detector.onAppForeground("com.phonepe.app", 650_000)
        detector.onCallState(CallState.IDLE, 700_000)
        assertTrue(sent.isEmpty())
        assertNull(detector.risk)
    }

    @Test
    fun hiddenNumberCountsAsUnknown() {
        detector.onCallState(CallState.RINGING, 0) // no number from either path
        detector.onCallState(CallState.OFFHOOK, 1_000)
        assertEquals(listOf("call_started"), types())
        assertEquals("hidden", sent[0].payload["masked_number"]!!.jsonPrimitive.content)
    }

    @Test
    fun broadcastDoesNotOverrideScreening() {
        detector.onIncoming("+91 98765 43210", unknown = true, path = DetectionPath.SCREENING, now = 0)
        detector.onIncoming("+91 98765 43210", unknown = false, path = DetectionPath.PHONE_STATE, now = 100)
        detector.onCallState(CallState.RINGING, 200)
        detector.onCallState(CallState.OFFHOOK, 1_000)
        assertEquals(listOf("call_started"), types())
        assertEquals(DetectionPath.SCREENING, detector.lastPath)
    }

    @Test
    fun longCallFiresOnceAtThreshold() {
        answerUnknown(0)
        runUntil(2_000, 61_000)
        assertEquals(listOf("call_started"), types())
        runUntil(62_000, 200_000)
        assertEquals(listOf("call_started", "long_unknown_call"), types())
        assertEquals(60L, sent[1].payload["threshold_seconds"]!!.jsonPrimitive.long)
    }

    @Test
    fun paymentAppBeforeThresholdIsMediumAfterIsHighOnceEach() {
        answerUnknown(0)
        detector.onAppForeground("com.phonepe.app", 20_000)
        detector.onAppForeground("com.phonepe.app", 30_000) // second medium ignored
        runUntil(2_000, 70_000)
        detector.onAppForeground("net.one97.paytm", 80_000)
        detector.onAppForeground("com.phonepe.app", 90_000) // second high ignored
        val alerts = sent.filter { it.type == "risk_alert" }
        assertEquals(listOf("medium", "high"), alerts.map { it.payload["level"]!!.jsonPrimitive.content })
        assertEquals("Paytm", alerts[1].payload["app_label"]!!.jsonPrimitive.content)
        assertEquals(78L, alerts[1].payload["call_duration_seconds"]!!.jsonPrimitive.long)
        assertTrue(alerts[1].payload["call_active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun unwatchedAppsAreIgnored() {
        answerUnknown(0)
        runUntil(2_000, 70_000)
        detector.onAppForeground("com.whatsapp", 75_000)
        assertTrue(sent.none { it.type == "risk_alert" })
    }

    @Test
    fun afterCallWindowGivesHighAlertThenCloses() {
        answerUnknown(0)
        runUntil(2_000, 100_000)
        detector.onCallState(CallState.IDLE, 102_000) // 100 s call, past the 60 s threshold
        assertEquals(listOf("call_started", "long_unknown_call", "call_ended"), types())
        assertEquals(100L, sent[2].payload["duration_seconds"]!!.jsonPrimitive.long)
        // 10 minutes later, inside the 15-minute window.
        detector.onAppForeground("com.phonepe.app", 702_000)
        val alert = sent.last()
        assertEquals("risk_alert", alert.type)
        assertEquals("high", alert.payload["level"]!!.jsonPrimitive.content)
        assertEquals(false, alert.payload["call_active"]!!.jsonPrimitive.boolean)
        assertEquals(100L, alert.payload["call_duration_seconds"]!!.jsonPrimitive.long)
        // Window closes after 15 minutes.
        detector.tick(102_000 + 15 * 60_000 + 1_000)
        assertNull(detector.risk)
        detector.onAppForeground("net.one97.paytm", 102_000 + 16 * 60_000)
        assertEquals(1, sent.count { it.type == "risk_alert" })
    }

    @Test
    fun shortCallHasNoWindow() {
        answerUnknown(0)
        runUntil(2_000, 30_000)
        detector.onCallState(CallState.IDLE, 32_000)
        assertNull(detector.risk)
        detector.onAppForeground("com.phonepe.app", 40_000)
        assertEquals(listOf("call_started", "call_ended"), types())
    }

    @Test
    fun switchesOffSendNothingButStateStillAdvances() {
        config = config.copy(shareCalls = false, sharePayments = false)
        answerUnknown(0)
        runUntil(2_000, 70_000)
        detector.onAppForeground("com.phonepe.app", 72_000)
        detector.onCallState(CallState.IDLE, 80_000)
        assertTrue(sent.isEmpty())
        // Turning payments back on during the window must not resend the alert for this call.
        config = config.copy(sharePayments = true)
        detector.onAppForeground("com.phonepe.app", 90_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun paymentSwitchAloneStillAlerts() {
        config = config.copy(shareCalls = false)
        answerUnknown(0)
        runUntil(2_000, 70_000)
        detector.onAppForeground("com.phonepe.app", 72_000)
        assertEquals(listOf("risk_alert"), types())
    }

    @Test
    fun simulationUsesTheSamePath() {
        detector.onIncoming("+91 98765 43210", unknown = true, path = DetectionPath.SIMULATION, now = 0)
        detector.onCallState(CallState.RINGING, 0)
        detector.onCallState(CallState.OFFHOOK, 0)
        runUntil(1_000, 61_000)
        detector.onAppForeground("com.phonepe.app", 62_000)
        detector.onCallState(CallState.IDLE, 63_000)
        assertEquals(listOf("call_started", "long_unknown_call", "risk_alert", "call_ended"), types())
        assertEquals(DetectionPath.SIMULATION, detector.lastPath)
    }
}
