package app.backupcircle.guardian

import android.app.Application
import android.content.Context
import app.backupcircle.core.net.ApiClient
import app.backupcircle.core.net.ConnectionLog
import app.backupcircle.core.net.ServerLocator
import app.backupcircle.core.net.WsClient
import app.backupcircle.guardian.data.GuardianRepository
import app.backupcircle.guardian.data.GuardianStore
import app.backupcircle.guardian.notify.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GuardianApp : Application() {
    lateinit var graph: GuardianGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = GuardianGraph(this)
        graph.notifier.createChannels()
    }
}

/** Manual wiring for the whole app; no DI framework. */
class GuardianGraph(context: Context) {
    val appContext: Context = context.applicationContext
    // Repository state lives on the main thread; network calls switch to IO inside ApiClient.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = ConnectionLog()
    val store = GuardianStore(appContext)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    val api: ApiClient = ApiClient(http, address = { locator.address.value }, token = { store.current.token })
    val locator: ServerLocator = ServerLocator(
        appContext,
        log,
        healthCheck = { api.isBackupServer(it) },
        loadSaved = { store.current.serverAddress },
        save = { store.setServer(it) },
    )
    val notifier = AlertNotifier(appContext)
    val ws: WsClient = WsClient(http, scope, log) { repository.wsUrl() }
    val repository: GuardianRepository = GuardianRepository(appContext, store, api, ws, locator, notifier, log, scope)
}

val Context.guardianGraph: GuardianGraph get() = (applicationContext as GuardianApp).graph
