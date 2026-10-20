package app.backupcircle.core.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class LocatorStatus { Idle, Searching, Found, NotFound }

/**
 * Finds the laptop server: first the saved address, then mDNS (NSD) discovery of
 * _backupcircle._tcp. A manually typed address (needed on emulators: 10.0.2.2:8000)
 * is saved the same way and kept until discovery finds something that answers.
 */
class ServerLocator(
    context: Context,
    private val log: ConnectionLog,
    private val healthCheck: suspend (String) -> Boolean,
    private val loadSaved: suspend () -> String?,
    private val save: suspend (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private val _address = MutableStateFlow<String?>(null)
    /** host:port of the server, or null if none is known yet. */
    val address: StateFlow<String?> = _address.asStateFlow()

    private val _status = MutableStateFlow(LocatorStatus.Idle)
    val status: StateFlow<LocatorStatus> = _status.asStateFlow()

    /** Loads the saved address without checking it, so API calls have somewhere to go. */
    suspend fun init() {
        if (_address.value == null) _address.value = loadSaved()
    }

    /** Returns an address that answers right now, searching if needed; null if nothing answers. */
    suspend fun ensure(): String? = mutex.withLock {
        val saved = _address.value ?: loadSaved()
        if (saved != null && healthCheck(saved)) {
            publish(saved, LocatorStatus.Found)
            return@withLock saved
        }
        _status.value = LocatorStatus.Searching
        val found = discover()
        if (found != null) return@withLock found
        _address.value = saved
        _status.value = LocatorStatus.NotFound
        null
    }

    /** Search the network even if a saved address exists (the "Rediscover server" button). */
    suspend fun rediscover(): String? = mutex.withLock {
        _status.value = LocatorStatus.Searching
        val found = discover()
        if (found == null) {
            val current = _address.value
            _status.value = if (current != null && healthCheck(current)) LocatorStatus.Found else LocatorStatus.NotFound
        }
        found
    }

    /** Saves a typed address if a Backup server answers there. */
    suspend fun setManual(input: String): Result<String> = mutex.withLock {
        val normalized = normalizeAddress(input)
            ?: return@withLock Result.failure(IllegalArgumentException("Type the address like 192.168.1.20:8000"))
        if (!healthCheck(normalized)) {
            return@withLock Result.failure(IllegalStateException("No Backup server answered at $normalized. Is the server running?"))
        }
        save(normalized)
        publish(normalized, LocatorStatus.Found)
        log.add("Server address set to $normalized")
        Result.success(normalized)
    }

    private fun publish(address: String, status: LocatorStatus) {
        _address.value = address
        _status.value = status
    }

    private suspend fun discover(): String? {
        log.add("Looking for the server on this Wi-Fi…")
        val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { nsdFind() }
        if (found == null) {
            log.add("No server found by discovery")
            return null
        }
        if (!healthCheck(found)) {
            log.add("Found $found but it did not answer")
            return null
        }
        save(found)
        publish(found, LocatorStatus.Found)
        log.add("Found server at $found")
        return found
    }

    private suspend fun nsdFind(): String? = suspendCancellableCoroutine { cont ->
        val nsd = appContext.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        // Some phones drop mDNS replies unless an app holds a multicast lock.
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("backup-discovery")?.apply { setReferenceCounted(false) }
        try { lock?.acquire() } catch (_: Exception) { }

        val resolving = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener

        fun cleanup() {
            if (!stopped.compareAndSet(false, true)) return
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
        }

        fun finish(result: String?) {
            cleanup()
            if (cont.isActive) cont.resume(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_backupcircle")) return
                if (!resolving.compareAndSet(false, true)) return
                @Suppress("DEPRECATION") // still works on API 34/35 and is simpler than ServiceInfoCallback
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = hostOf(info)
                        if (host != null && info.port > 0) finish("$host:${info.port}") else resolving.set(false)
                    }
                })
            }
        }
        cont.invokeOnCancellation { cleanup() }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            finish(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val v4 = addresses.firstOrNull { it is Inet4Address }
        return v4?.hostAddress ?: addresses.firstOrNull()?.hostAddress?.let { "[$it]" }
    }

    companion object {
        const val SERVICE_TYPE = "_backupcircle._tcp"
        const val DEFAULT_PORT = 8000
        private const val DISCOVERY_TIMEOUT_MS = 8_000L

        /** "http://192.168.1.20:8000/" -> "192.168.1.20:8000"; adds :8000 when no port is given. */
        fun normalizeAddress(input: String): String? {
            var s = input.trim()
            for (prefix in listOf("http://", "https://", "ws://")) s = s.removePrefix(prefix)
            s = s.substringBefore("/").trim()
            if (s.isEmpty()) return null
            if (!s.contains(":")) s = "$s:$DEFAULT_PORT"
            val host = s.substringBeforeLast(":")
            val port = s.substringAfterLast(":").toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return "$host:$port"
        }
    }
}
