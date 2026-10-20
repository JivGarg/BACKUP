package app.backupcircle.member.data

import app.backupcircle.core.net.ApiClient
import app.backupcircle.core.net.ApiException
import app.backupcircle.core.net.BackupJson
import app.backupcircle.core.net.ConnectionLog
import app.backupcircle.core.net.EventRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
data class QueuedEvent(
    val clientEventId: String,
    val type: String,
    val occurredAt: Long,
    val payload: JsonObject,
    /** When set, elapsed_seconds is recomputed from this moment at each send, so a late delivery still starts the guardian's timer at the right value. */
    val elapsedBase: Long? = null,
)

/**
 * Events waiting for the server, kept in a file so they survive restarts. Sent in order,
 * retried with backoff until acknowledged; the server ignores duplicates by client event id.
 */
class EventQueue(
    private val file: File,
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val log: ConnectionLog,
    private val onAuthError: suspend () -> Unit,
    private val onNetworkTrouble: suspend () -> Unit,
) {
    private val serializer = ListSerializer(QueuedEvent.serializer())
    private val lock = Any()
    private val items = ArrayDeque(load())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _pending = MutableStateFlow(items.size)
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private var job: Job? = null

    fun add(type: String, occurredAt: Long, payload: JsonObject, elapsedBase: Long?) {
        synchronized(lock) {
            items.addLast(QueuedEvent(UUID.randomUUID().toString(), type, occurredAt, payload, elapsedBase))
            save()
            _pending.value = items.size
        }
        wake.trySend(Unit)
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
            save()
            _pending.value = 0
        }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val head = synchronized(lock) { items.firstOrNull() }
            if (head == null) {
                wake.receive()
                continue
            }
            try {
                val now = System.currentTimeMillis()
                val payload = head.elapsedBase?.let { base ->
                    JsonObject(head.payload + ("elapsed_seconds" to JsonPrimitive(((now - base) / 1000).coerceAtLeast(0))))
                } ?: head.payload
                api.sendEvent(EventRequest(head.clientEventId, head.type, head.occurredAt, now, payload))
                remove(head)
                failures = 0
            } catch (e: ApiException) {
                when {
                    e.isAuthError -> {
                        onAuthError()
                        delay(3_000)
                    }
                    e.httpStatus in 400..499 -> {
                        // The server will never accept it; don't block the events behind it.
                        log.add("Dropped ${head.type}: ${e.message}")
                        remove(head)
                    }
                    else -> {
                        failures++
                        if (failures % 3 == 0) onNetworkTrouble()
                        withTimeoutOrNull(BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]) { wake.receive() }
                    }
                }
            }
        }
    }

    private fun remove(event: QueuedEvent) {
        synchronized(lock) {
            if (items.firstOrNull()?.clientEventId == event.clientEventId) items.removeFirst()
            save()
            _pending.value = items.size
        }
    }

    private fun load(): List<QueuedEvent> = try {
        if (file.exists()) BackupJson.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Write to a temp file and rename, so a crash never leaves half a file. */
    private fun save() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(BackupJson.encodeToString(serializer, items.toList()))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
    }
}
