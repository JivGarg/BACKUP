package app.backupcircle.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** All server timestamps are epoch milliseconds in the server's clock. */

val BackupJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

object EventTypes {
    const val CALL_STARTED = "call_started"
    const val LONG_UNKNOWN_CALL = "long_unknown_call"
    const val CALL_ENDED = "call_ended"
    const val RISK_ALERT = "risk_alert"
}

/** Masked number sent when the caller hid their number. */
const val HIDDEN_NUMBER = "hidden"

@Serializable
data class HealthResponse(val ok: Boolean = false, val service: String = "", val version: String = "", val time: Long = 0)

@Serializable
data class ApiErrorBody(
    val error: String = "",
    val message: String = "",
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

// ---------------------------------------------------------------- member
@Serializable
data class RegisterMemberRequest(@SerialName("app_version") val appVersion: String)

@Serializable
data class RegisterMemberResponse(@SerialName("member_id") val memberId: String, val token: String)

@Serializable
data class CodeResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
)

@Serializable
data class GuardianSummary(
    @SerialName("guardian_id") val guardianId: String,
    val name: String,
    @SerialName("linked_at") val linkedAt: Long = 0,
)

@Serializable
data class MemberSettingsDto(
    @SerialName("share_long_calls") val shareLongCalls: Boolean = true,
    @SerialName("share_payment_alerts") val sharePaymentAlerts: Boolean = true,
    @SerialName("threshold_seconds") val thresholdSeconds: Int = 300,
    @SerialName("after_call_window_minutes") val afterCallWindowMinutes: Int = 15,
)

@Serializable
data class MemberMeResponse(
    @SerialName("member_id") val memberId: String,
    val guardians: List<GuardianSummary> = emptyList(),
    @SerialName("server_time") val serverTime: Long = 0,
)

@Serializable
data class MemberStatusRequest(
    @SerialName("permission_health") val permissionHealth: Map<String, Boolean>,
    val settings: MemberSettingsDto,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class EventRequest(
    @SerialName("client_event_id") val clientEventId: String,
    val type: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("sent_at") val sentAt: Long,
    val payload: JsonObject,
)

@Serializable
data class EventResponse(@SerialName("event_id") val eventId: Long, val duplicate: Boolean = false)

// ---------------------------------------------------------------- guardian
@Serializable
data class NameRequest(val name: String)

@Serializable
data class RegisterGuardianResponse(
    @SerialName("guardian_id") val guardianId: String,
    val token: String,
    val name: String,
)

@Serializable
data class GuardianMeResponse(@SerialName("guardian_id") val guardianId: String, val name: String)

@Serializable
data class PairRequest(val code: String)

@Serializable
data class ActiveCallDto(
    @SerialName("call_id") val callId: String? = null,
    @SerialName("masked_number") val maskedNumber: String = "",
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("elapsed_seconds") val elapsedSeconds: Long = 0,
)

@Serializable
data class MemberView(
    @SerialName("member_id") val memberId: String,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long? = null,
    @SerialName("permission_health") val permissionHealth: Map<String, Boolean> = emptyMap(),
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("active_call") val activeCall: ActiveCallDto? = null,
    @SerialName("open_alerts") val openAlerts: Int = 0,
    @SerialName("linked_at") val linkedAt: Long = 0,
)

@Serializable
data class PairResponse(val member: MemberView, @SerialName("already_linked") val alreadyLinked: Boolean = false)

@Serializable
data class MembersResponse(
    @SerialName("guardian_id") val guardianId: String,
    val name: String,
    val members: List<MemberView> = emptyList(),
    @SerialName("server_time") val serverTime: Long = 0,
)

/** PATCH body: a field left null is not sent, so it stays unchanged. An empty string clears it. */
@Serializable
data class MemberPatchRequest(
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
)

@Serializable
data class EventView(
    val id: Long,
    @SerialName("member_id") val memberId: String,
    @SerialName("client_event_id") val clientEventId: String = "",
    val type: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("received_at") val receivedAt: Long = 0,
    val payload: JsonObject = JsonObject(emptyMap()),
    val resolved: Boolean = false,
    val resolution: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolved_by_name") val resolvedByName: String? = null,
    @SerialName("resolved_at") val resolvedAt: Long? = null,
) {
    private fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull
    private fun long(key: String) = payload[key]?.jsonPrimitive?.longOrNull ?: 0L

    val callId: String? get() = str("call_id")
    val maskedNumber: String get() = str("masked_number") ?: ""
    val elapsedSeconds: Long get() = long("elapsed_seconds")
    val thresholdSeconds: Long get() = long("threshold_seconds")
    val durationSeconds: Long get() = long("duration_seconds")
    val callDurationSeconds: Long get() = long("call_duration_seconds")
    val level: String get() = str("level") ?: ""
    val appLabel: String get() = str("app_label") ?: str("app_package") ?: ""
    val callActive: Boolean get() = payload["call_active"]?.jsonPrimitive?.booleanOrNull ?: false
    val isAlert: Boolean get() = type == EventTypes.RISK_ALERT
    val isHigh: Boolean get() = isAlert && level == "high"
}

@Serializable
data class EventsResponse(val events: List<EventView> = emptyList(), @SerialName("server_time") val serverTime: Long = 0)

@Serializable
data class ResolveRequest(val resolution: String)

/** Keys of the permission health map the Member app reports. */
object HealthKeys {
    const val NOTIFICATIONS = "notifications"
    const val PHONE = "phone"
    const val CALL_SCREENING = "call_screening"
    const val USAGE_ACCESS = "usage_access"
    const val BATTERY = "battery"
    val REQUIRED = listOf(NOTIFICATIONS, PHONE, CALL_SCREENING, USAGE_ACCESS)
}
