package app.backupcircle.member.data

import android.content.Context
import app.backupcircle.core.net.ApiClient
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.net.CodeResponse
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.net.ConnectionLog
import app.backupcircle.core.net.GuardianSummary
import app.backupcircle.core.net.LocatorStatus
import app.backupcircle.core.net.MemberStatusRequest
import app.backupcircle.core.net.ServerLocator
import app.backupcircle.core.net.WsClient
import app.backupcircle.member.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Server identity, pairing code, guardians and status reports for the Member phone. */
class MemberRepository(
    private val context: Context,
    private val store: MemberStore,
    private val api: ApiClient,
    private val ws: WsClient,
    val locator: ServerLocator,
    private val queue: EventQueue,
    val log: ConnectionLog,
    private val scope: CoroutineScope,
) {
    private val _health = MutableStateFlow(PermissionHealth.check(context))
    val health: StateFlow<PermissionHealth> = _health.asStateFlow()
    val connection: StateFlow<ConnState> get() = ws.state
    val guardians: List<GuardianSummary> get() = store.current.guardians

    private val registerMutex = Mutex()
    private var started = false
    private val jobs = mutableListOf<Job>()
    private var lastReported: MemberStatusRequest? = null

    fun start() {
        if (started) return
        started = true
        ws.onRepeatedFailure = { locator.rediscover() }
        jobs += scope.launch { locator.init() }
        jobs += scope.launch { ws.messages.collect { handle(it) } }
        jobs += scope.launch {
            ws.connected.collect {
                lastReported = null
                runCatching { refreshMe() }
                runCatching { reportStatus() }
            }
        }
        jobs += scope.launch { ws.authFailed.collect { onAuthLost() } }
        jobs += scope.launch { locator.address.drop(1).distinctUntilChanged().collect { ws.reconnectNow() } }
        // Settings changes (switches, threshold) go to the server like permission changes.
        jobs += scope.launch {
            store.state.map { it.settingsDto() }.distinctUntilChanged().drop(1).collect { runCatching { reportStatus() } }
        }
        // Permission health can change in Settings at any time; check it regularly.
        jobs += scope.launch {
            while (true) {
                recheckHealth()
                delay(10_000)
            }
        }
        // The guardian list is refreshed periodically too, in case a push was missed.
        jobs += scope.launch {
            while (true) {
                delay(30_000)
                if (store.current.token != null) runCatching { refreshMe() }
            }
        }
        ws.start()
        queue.start()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        ws.stop()
        queue.stop()
        started = false
    }

    fun recheckHealth() {
        val now = PermissionHealth.check(context)
        if (now != _health.value) {
            _health.value = now
            scope.launch { runCatching { reportStatus() } }
        }
    }

    /** ws://host:port/ws?token=… once a server answers and this phone is registered. */
    suspend fun wsUrl(): String? {
        val address = (if (locator.status.value == LocatorStatus.Found) locator.address.value else locator.ensure()) ?: return null
        val token = store.current.token ?: runCatching { ensureRegistered() }.getOrNull() ?: return null
        return "ws://$address/ws?token=$token"
    }

    suspend fun ensureRegistered(): String = registerMutex.withLock {
        store.current.token?.let { return@withLock it }
        if (locator.address.value == null || locator.status.value != LocatorStatus.Found) {
            locator.ensure() ?: throw ApiException("no_server", "Can't find the Backup server.")
        }
        val r = api.registerMember(BuildConfig.VERSION_NAME)
        store.setIdentity(r.memberId, r.token)
        log.add("Registered with the server")
        ws.reconnectNow()
        r.token
    }

    suspend fun requestCode(): CodeResponse {
        ensureRegistered()
        return authed { api.newCode() }
    }

    suspend fun refreshMe() {
        val me = authed { api.memberMe() }
        if (me.guardians != store.current.guardians) store.setGuardians(me.guardians)
    }

    suspend fun reportStatus() {
        if (store.current.token == null) return
        val body = MemberStatusRequest(_health.value.toMap(), store.current.settingsDto(), BuildConfig.VERSION_NAME)
        if (body == lastReported) return
        authed { api.reportStatus(body) }
        lastReported = body
    }

    /** The server no longer knows this phone (it was reset): register again and show a new code. */
    suspend fun onAuthLost() {
        if (store.current.token == null) return
        log.add("Server was reset; setting this phone up again")
        store.clearIdentity()
        queue.clear()
        lastReported = null
        runCatching { ensureRegistered() }
        ws.reconnectNow()
    }

    private suspend fun <T> authed(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.isAuthError) scope.launch { onAuthLost() }
        throw e
    }

    private suspend fun handle(json: JsonObject) {
        when (json["type"]?.jsonPrimitive?.contentOrNull) {
            // A guardian typed our code, or removed us: refresh the list so every screen updates.
            "paired", "unpaired" -> runCatching { refreshMe() }
        }
    }
}
