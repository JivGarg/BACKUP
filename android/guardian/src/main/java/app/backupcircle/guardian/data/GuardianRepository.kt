package app.backupcircle.guardian.data

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.backupcircle.core.net.ApiClient
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.net.BackupJson
import app.backupcircle.core.net.ConnState
import app.backupcircle.core.net.ConnectionLog
import app.backupcircle.core.net.EventTypes
import app.backupcircle.core.net.EventView
import app.backupcircle.core.net.LocatorStatus
import app.backupcircle.core.net.MemberPatchRequest
import app.backupcircle.core.net.MemberView
import app.backupcircle.core.net.ServerLocator
import app.backupcircle.core.net.WsClient
import app.backupcircle.core.util.Format
import app.backupcircle.guardian.notify.AlertNotifier
import app.backupcircle.guardian.ui.alert.AlertActivity
import app.backupcircle.guardian.ui.alertBody
import app.backupcircle.guardian.ui.maskedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ActiveCallState(val callId: String?, val maskedNumber: String, val startedAtLocal: Long)

data class MemberState(
    val id: String,
    val displayName: String?,
    val phone: String?,
    val online: Boolean,
    val lastSeenLocal: Long?,
    val health: Map<String, Boolean>,
    val activeCall: ActiveCallState?,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: "Your person"
}

/**
 * Everything the Guardian app knows, kept in sync with the server over HTTP + WebSocket.
 * All state changes happen on the main thread.
 */
class GuardianRepository(
    private val context: Context,
    private val store: GuardianStore,
    private val api: ApiClient,
    private val ws: WsClient,
    val locator: ServerLocator,
    private val notifier: AlertNotifier,
    val log: ConnectionLog,
    private val scope: CoroutineScope,
) {
    private val _members = MutableStateFlow<List<MemberState>>(emptyList())
    val members: StateFlow<List<MemberState>> = _members.asStateFlow()

    /** All events of linked members, oldest first. */
    private val _events = MutableStateFlow<List<EventView>>(emptyList())
    val events: StateFlow<List<EventView>> = _events.asStateFlow()

    private val _synced = MutableStateFlow(false)
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    /** Live events as they arrive, for the dashboard's "New events" pill. */
    private val _incoming = MutableSharedFlow<EventView>(extraBufferCapacity = 16)
    val incoming: SharedFlow<EventView> = _incoming.asSharedFlow()

    val connection: StateFlow<ConnState> get() = ws.state
    val prefs: GuardianPrefs get() = store.current

    /** Server clock minus local clock; server timestamps are shown in local time. */
    @Volatile private var offsetMs = 0L
    private var started = false
    private val jobs = mutableListOf<Job>()
    private val syncMutex = Mutex()
    private val identityMutex = Mutex()

    fun toLocal(serverMs: Long): Long = serverMs - offsetMs

    fun memberName(memberId: String): String =
        _members.value.firstOrNull { it.id == memberId }?.name ?: "Your person"

    fun start() {
        if (started) return
        started = true
        ws.onRepeatedFailure = { locator.rediscover() }
        jobs += scope.launch { locator.init() }
        jobs += scope.launch { ws.messages.collect { handle(it) } }
        jobs += scope.launch { ws.connected.collect { runCatching { sync() } } }
        jobs += scope.launch { ws.authFailed.collect { onAuthLost() } }
        jobs += scope.launch { locator.address.drop(1).distinctUntilChanged().collect { ws.reconnectNow() } }
        ws.start()
    }

    /** URL for the WebSocket, registering again first if the server lost us. */
    suspend fun wsUrl(): String? {
        if (store.current.token == null && store.current.name != null) {
            runCatching { reRegister() }
        }
        val token = store.current.token ?: return null
        val address = if (locator.status.value == LocatorStatus.Found) locator.address.value else locator.ensure()
        return address?.let { "ws://$it/ws?token=$token" }
    }

    // ------------------------------------------------------------ identity
    suspend fun register(name: String) = identityMutex.withLock {
        locator.ensure() ?: throw ApiException(
            "no_server",
            "Can't find the Backup server. Make sure this phone is on the same Wi-Fi as the laptop.",
        )
        val r = api.registerGuardian(name)
        store.setIdentity(r.name, r.guardianId, r.token)
        log.add("Registered as ${r.name}")
        ws.reconnectNow()
    }

    private suspend fun reRegister() = identityMutex.withLock {
        val name = store.current.name ?: return@withLock
        if (store.current.token != null) return@withLock
        if (locator.address.value == null && locator.ensure() == null) return@withLock
        val r = api.registerGuardian(name)
        store.setIdentity(r.name, r.guardianId, r.token)
        log.add("Set up again on the server as ${r.name}")
    }

    private suspend fun onAuthLost() {
        if (store.current.token == null) return
        log.add("Server was reset; setting this phone up again")
        store.clearIdentity()
        _members.value = emptyList()
        _events.value = emptyList()
        notifier.cancelAll()
        runCatching { reRegister() }
        ws.reconnectNow()
    }

    private suspend fun <T> authed(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.isAuthError) scope.launch { onAuthLost() }
        throw e
    }

    suspend fun rename(name: String) {
        val r = authed { api.renameGuardian(name) }
        store.setName(r.name)
    }

    // ------------------------------------------------------------ data
    suspend fun sync() = syncMutex.withLock {
        refreshMembers()
        val after = _events.value.maxOfOrNull { it.id } ?: 0L
        val response = authed { api.events(after) }
        updateOffset(response.serverTime)
        if (response.events.isNotEmpty()) merge(response.events)
        val lastNotified = store.current.lastNotifiedEventId
        val missed = response.events.filter { it.id > lastNotified }
        if (missed.isNotEmpty()) {
            // Missed while offline: ring for the newest alert that nobody handled yet.
            val alert = missed.lastOrNull { it.isHigh && !it.resolved } ?: missed.lastOrNull { it.isAlert && !it.resolved }
            alert?.let { notifyEvent(it, live = false) }
            store.setLastNotified(missed.maxOf { it.id })
        }
        _synced.value = true
    }

    suspend fun refreshMembers() {
        val r = authed { api.members() }
        updateOffset(r.serverTime)
        if (r.name != store.current.name) store.setName(r.name)
        _members.value = r.members.map { it.toState() }
    }

    suspend fun pair(code: String): MemberView {
        val r = authed { api.pair(code) }
        runCatching { refreshMembers() }
        if (_members.value.none { it.id == r.member.memberId }) {
            _members.update { it + r.member.toState() }
        }
        return r.member
    }

    suspend fun updateMember(memberId: String, displayName: String? = null, phone: String? = null) {
        val view = authed { api.updateMember(memberId, MemberPatchRequest(displayName, phone)) }
        _members.update { list -> list.map { if (it.id == memberId) it.copy(displayName = view.displayName, phone = view.phone) else it } }
    }

    suspend fun unlink(memberId: String) {
        authed { api.unlink(memberId) }
        _members.update { list -> list.filterNot { it.id == memberId } }
        _events.update { list -> list.filterNot { it.memberId == memberId } }
        notifier.cancelCallActivity(memberId)
    }

    /** Marks an alert handled right away on this phone, then tells the server (retrying). */
    fun resolve(eventId: Long, resolution: String) {
        val me = store.current.name
        _events.update { list ->
            list.map {
                if (it.id == eventId && !it.resolved) {
                    it.copy(resolved = true, resolution = resolution, resolvedByName = me, resolvedAt = System.currentTimeMillis() + offsetMs)
                } else it
            }
        }
        notifier.cancelAlert(eventId)
        scope.launch {
            var wait = 1_000L
            repeat(40) {
                try {
                    val view = authed { api.resolve(eventId, resolution) }
                    merge(listOf(view))
                    return@launch
                } catch (e: ApiException) {
                    if (!e.isNetwork && e.code != "no_server") return@launch
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(10_000)
            }
        }
    }

    suspend fun resetPhone() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        ws.stop()
        notifier.cancelAll()
        store.clearAll()
        _members.value = emptyList()
        _events.value = emptyList()
        _synced.value = false
        started = false
    }

    // ------------------------------------------------------------ live messages
    private suspend fun handle(json: JsonObject) {
        when (json["type"]?.jsonPrimitive?.contentOrNull) {
            "member_status" -> {
                val id = json.str("member_id") ?: return
                if (_members.value.none { it.id == id }) {
                    runCatching { refreshMembers() }
                    return
                }
                val online = json["online"]?.jsonPrimitive?.booleanOrNull ?: false
                val lastSeen = json["last_seen"]?.jsonPrimitive?.longOrNull
                val health = json["permission_health"]?.let {
                    runCatching { BackupJson.decodeFromJsonElement(MapSerializer(String.serializer(), Boolean.serializer()), it) }.getOrNull()
                }
                _members.update { list ->
                    list.map {
                        if (it.id == id) {
                            it.copy(online = online, lastSeenLocal = lastSeen?.let(::toLocal) ?: it.lastSeenLocal, health = health ?: it.health)
                        } else it
                    }
                }
            }
            "event" -> {
                val element = json["event"] ?: return
                val event = runCatching { BackupJson.decodeFromJsonElement(EventView.serializer(), element) }.getOrNull() ?: return
                if (_members.value.none { it.id == event.memberId }) runCatching { refreshMembers() }
                merge(listOf(event))
                applyCallState(event)
                _incoming.tryEmit(event)
                if (event.id > store.current.lastNotifiedEventId) {
                    notifyEvent(event, live = true)
                    store.setLastNotified(event.id)
                }
            }
            "event_resolved" -> {
                val id = json["event_id"]?.jsonPrimitive?.longOrNull ?: return
                _events.update { list ->
                    list.map {
                        if (it.id == id) {
                            it.copy(
                                resolved = true,
                                resolution = json.str("resolution"),
                                resolvedBy = json.str("resolved_by"),
                                resolvedByName = json.str("resolver_name"),
                                resolvedAt = json["resolved_at"]?.jsonPrimitive?.longOrNull,
                            )
                        } else it
                    }
                }
                notifier.cancelAlert(id)
            }
        }
    }

    private fun applyCallState(event: EventView) {
        val now = System.currentTimeMillis()
        _members.update { list ->
            list.map { m ->
                if (m.id != event.memberId) return@map m
                when (event.type) {
                    // Timer start = local time of receipt minus the elapsed seconds; phone clocks never compared.
                    EventTypes.CALL_STARTED, EventTypes.LONG_UNKNOWN_CALL -> m.copy(
                        activeCall = ActiveCallState(
                            event.callId,
                            event.maskedNumber.ifEmpty { m.activeCall?.maskedNumber.orEmpty() },
                            now - event.elapsedSeconds * 1000,
                        ),
                    )
                    EventTypes.CALL_ENDED -> m.copy(activeCall = null)
                    EventTypes.RISK_ALERT -> if (event.callActive && m.activeCall == null) {
                        m.copy(activeCall = ActiveCallState(event.callId, event.maskedNumber, now - event.callDurationSeconds * 1000))
                    } else m
                    else -> m
                }
            }
        }
    }

    private fun notifyEvent(event: EventView, live: Boolean) {
        val name = memberName(event.memberId)
        when (event.type) {
            EventTypes.RISK_ALERT -> if (!event.resolved) {
                notifier.showAlert(event, name, alertBody(event, name))
                scope.launch {
                    delay(60_000)
                    val current = _events.value.firstOrNull { it.id == event.id }
                    if (current != null && !current.resolved) notifier.silenceAlert(current, name, alertBody(current, name))
                }
                if (isAppInForeground()) {
                    runCatching { context.startActivity(AlertActivity.intent(context, event.id)) }
                }
            }
            EventTypes.CALL_STARTED -> if (live) notifier.showCallActivity(
                event.memberId,
                "$name is on a call with an unknown number",
                "Answered a call from ${maskedText(event.maskedNumber)}",
            )
            EventTypes.LONG_UNKNOWN_CALL -> if (live) notifier.showCallActivity(
                event.memberId,
                "Long call: $name, ${Format.duration(event.elapsedSeconds)} with an unknown number",
                "$name has been on a call with an unknown number for ${Format.duration(event.elapsedSeconds)}.",
            )
            EventTypes.CALL_ENDED -> notifier.cancelCallActivity(event.memberId)
        }
    }

    private fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun merge(incoming: List<EventView>) {
        _events.update { list ->
            val byId = LinkedHashMap<Long, EventView>()
            list.forEach { byId[it.id] = it }
            incoming.forEach { byId[it.id] = it }
            byId.values.sortedBy { it.id }
        }
    }

    private fun updateOffset(serverTime: Long) {
        if (serverTime > 0) offsetMs = serverTime - System.currentTimeMillis()
    }

    private fun MemberView.toState() = MemberState(
        id = memberId,
        displayName = displayName,
        phone = phone,
        online = online,
        lastSeenLocal = lastSeen?.let(::toLocal),
        health = permissionHealth,
        activeCall = activeCall?.let {
            ActiveCallState(it.callId, it.maskedNumber, System.currentTimeMillis() - it.elapsedSeconds * 1000)
        },
    )

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
