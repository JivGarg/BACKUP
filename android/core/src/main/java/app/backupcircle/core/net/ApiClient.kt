package app.backupcircle.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A plain-language failure. [code] is the server's error code ("code_expired", ...),
 * "network" when the server could not be reached, or "no_server" before one is found.
 */
class ApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0,
    val retryAfterSeconds: Int? = null,
) : Exception(message) {
    val isAuthError: Boolean get() = httpStatus == 401
    val isNetwork: Boolean get() = httpStatus == 0
}

class ApiClient(
    private val http: OkHttpClient,
    /** "192.168.43.17:8000", or null while no server is known. */
    private val address: () -> String?,
    private val token: () -> String?,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val healthHttp = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    /** True when [address] answers /health as a Backup server. */
    suspend fun isBackupServer(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("http://$address/health").build()
            healthHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = BackupJson.decodeFromString(HealthResponse.serializer(), response.body?.string().orEmpty())
                body.ok && body.service == "backupcircle"
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun <T> send(
        method: String,
        path: String,
        body: String?,
        responseSerializer: KSerializer<T>,
        authenticated: Boolean = true,
    ): T = withContext(Dispatchers.IO) {
        val base = address() ?: throw ApiException("no_server", "Looking for the Backup server…")
        val builder = Request.Builder().url("http://$base$path")
        if (authenticated) {
            token()?.let { builder.header("Authorization", "Bearer $it") }
        }
        val requestBody = body?.toRequestBody(jsonType)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(requestBody)
            else -> builder.method(method, requestBody ?: "{}".toRequestBody(jsonType))
        }
        val (status, text) = try {
            http.newCall(builder.build()).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ApiException("network", "Can't reach the Backup server. Make sure this phone is on the same Wi-Fi as the laptop.")
        }
        if (status !in 200..299) {
            val error = try {
                BackupJson.decodeFromString(ApiErrorBody.serializer(), text)
            } catch (e: Exception) {
                ApiErrorBody("http_$status", "The server had a problem ($status). Try again.")
            }
            throw ApiException(
                error.error.ifEmpty { "http_$status" },
                error.message.ifEmpty { "The server had a problem ($status). Try again." },
                status,
                error.retryAfterSeconds,
            )
        }
        try {
            BackupJson.decodeFromString(responseSerializer, text)
        } catch (e: Exception) {
            throw ApiException("bad_response", "The server sent an unexpected answer. Is this the Backup server?", status)
        }
    }

    suspend inline fun <reified T> get(path: String): T = send("GET", path, null, serializer<T>())

    suspend inline fun <reified B, reified T> post(path: String, body: B, authenticated: Boolean = true): T =
        send("POST", path, BackupJson.encodeToString(serializer<B>(), body), serializer<T>(), authenticated)

    suspend inline fun <reified B, reified T> patch(path: String, body: B): T =
        send("PATCH", path, BackupJson.encodeToString(serializer<B>(), body), serializer<T>())

    // ------------------------------------------------------------ member
    suspend fun registerMember(appVersion: String): RegisterMemberResponse =
        post("/api/member/register", RegisterMemberRequest(appVersion), authenticated = false)

    suspend fun newCode(): CodeResponse = send("POST", "/api/member/code", "{}", CodeResponse.serializer())

    suspend fun memberMe(): MemberMeResponse = get("/api/member/me")

    suspend fun reportStatus(body: MemberStatusRequest): Unit {
        send("POST", "/api/member/status", BackupJson.encodeToString(MemberStatusRequest.serializer(), body), OkResponse.serializer())
    }

    suspend fun sendEvent(body: EventRequest): EventResponse = post("/api/member/events", body)

    // ------------------------------------------------------------ guardian
    suspend fun registerGuardian(name: String): RegisterGuardianResponse =
        post("/api/guardian/register", NameRequest(name), authenticated = false)

    suspend fun renameGuardian(name: String): GuardianMeResponse = patch("/api/guardian/me", NameRequest(name))

    suspend fun pair(code: String): PairResponse = post("/api/guardian/pair", PairRequest(code))

    suspend fun members(): MembersResponse = get("/api/guardian/members")

    suspend fun updateMember(memberId: String, body: MemberPatchRequest): MemberView =
        patch("/api/guardian/members/$memberId", body)

    suspend fun unlink(memberId: String) {
        send("DELETE", "/api/guardian/members/$memberId", null, OkResponse.serializer())
    }

    suspend fun events(afterId: Long): EventsResponse = get("/api/guardian/events?after_id=$afterId")

    suspend fun resolve(eventId: Long, resolution: String): EventView =
        post("/api/guardian/events/$eventId/resolve", ResolveRequest(resolution))
}

@kotlinx.serialization.Serializable
data class OkResponse(val ok: Boolean = true)
