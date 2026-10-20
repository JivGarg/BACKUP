package app.backupcircle.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnState { Disconnected, Connecting, Connected }

/**
 * One WebSocket to the server, kept open forever: reconnects with backoff
 * 1, 2, 4, 8, then every 10 seconds, and pings every 15 seconds for presence.
 */
class WsClient(
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val log: ConnectionLog,
    /** Builds ws://host:port/ws?token=… or returns null while the server or token is unknown. */
    private val urlProvider: suspend () -> String?,
) {
    // Protocol-level pings make OkHttp notice a dead link quickly; the JSON pings below
    // are what the server uses for presence.
    private val http = baseHttp.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val messages: SharedFlow<JsonObject> = _messages.asSharedFlow()

    /** Emits when the server rejects our token (it was reset); the owner registers again. */
    private val _authFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authFailed: SharedFlow<Unit> = _authFailed.asSharedFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    /** Latest server clock minus local clock, from pongs. */
    @Volatile var serverOffsetMs: Long = 0
        private set

    /** Called after several failed attempts in a row, so the owner can rediscover the server. */
    var onRepeatedFailure: (suspend () -> Unit)? = null

    private var loopJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var wake: CompletableDeferred<Unit>? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        socket?.close(1000, "bye")
        socket = null
        _state.value = ConnState.Disconnected
    }

    /** Drop the current socket and reconnect now (e.g. after the server address changed). */
    fun reconnectNow() {
        socket?.cancel()
        wake?.complete(Unit)
    }

    fun send(json: String): Boolean = socket?.send(json) ?: false

    private sealed interface Outcome {
        data object Closed : Outcome
        data object Failed : Outcome
        data object AuthFailed : Outcome
    }

    private suspend fun loop() {
        var failures = 0
        while (scope.isActive) {
            val url = urlProvider()
            if (url == null) {
                _state.value = ConnState.Disconnected
                sleep(backoff(failures++))
                continue
            }
            _state.value = ConnState.Connecting
            when (connectOnce(url)) {
                Outcome.AuthFailed -> {
                    log.add("Server does not know this phone any more")
                    _state.value = ConnState.Disconnected
                    _authFailed.tryEmit(Unit)
                    sleep(3000)
                }
                Outcome.Closed -> {
                    failures = 0
                    sleep(1000)
                }
                Outcome.Failed -> {
                    failures++
                    if (failures % 3 == 0) {
                        log.add("Server not answering, looking for it again")
                        onRepeatedFailure?.invoke()
                    }
                    sleep(backoff(failures - 1))
                }
            }
        }
    }

    private suspend fun sleep(ms: Long) {
        val signal = CompletableDeferred<Unit>()
        wake = signal
        kotlinx.coroutines.withTimeoutOrNull(ms) { signal.await() }
        wake = null
    }

    private fun backoff(attempt: Int): Long = longArrayOf(1, 2, 4, 8, 10)[attempt.coerceIn(0, 4)] * 1000

    private suspend fun connectOnce(url: String): Outcome {
        val done = CompletableDeferred<Outcome>()
        val opened = AtomicBoolean(false)
        val shownUrl = url.substringBefore("?")
        log.add("Connecting to $shownUrl")
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                _state.value = ConnState.Connected
                log.add("Connected")
                webSocket.send(PING)
                _connected.tryEmit(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    BackupJson.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    return
                }
                when (json["type"]?.jsonPrimitive?.contentOrNull) {
                    // The server nudges phones whose screen-off timers paused; answer at once.
                    "ping_request" -> webSocket.send(PING)
                    "pong" -> json["time"]?.jsonPrimitive?.longOrNull?.let {
                        serverOffsetMs = it - System.currentTimeMillis()
                    }
                    else -> _messages.tryEmit(json)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code == 4401) done.complete(Outcome.AuthFailed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (opened.get()) log.add("Disconnected ($code)")
                done.complete(if (code == 4401) Outcome.AuthFailed else if (opened.get()) Outcome.Closed else Outcome.Failed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                if (code == 401 || code == 403) {
                    done.complete(Outcome.AuthFailed)
                    return
                }
                log.add(if (opened.get()) "Connection lost" else "Can't connect: ${t.javaClass.simpleName}")
                done.complete(if (opened.get()) Outcome.Closed else Outcome.Failed)
            }
        })
        socket = ws
        val pinger = scope.launch {
            while (isActive) {
                delay(15_000)
                ws.send(PING)
            }
        }
        val wakeSignal = CompletableDeferred<Unit>()
        wake = wakeSignal
        return try {
            kotlinx.coroutines.selects.select {
                done.onAwait { it }
                wakeSignal.onAwait { Outcome.Closed }
            }
        } finally {
            pinger.cancel()
            ws.cancel()
            if (socket === ws) socket = null
            wake = null
            _state.value = ConnState.Disconnected
        }
    }

    private companion object {
        const val PING = """{"type":"ping"}"""
    }
}
