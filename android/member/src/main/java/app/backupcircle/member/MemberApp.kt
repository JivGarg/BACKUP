package app.backupcircle.member

import android.app.Application
import android.content.Context
import app.backupcircle.core.net.ApiClient
import app.backupcircle.core.net.ConnectionLog
import app.backupcircle.core.net.ServerLocator
import app.backupcircle.core.net.WsClient
import app.backupcircle.member.data.EventQueue
import app.backupcircle.member.data.MemberRepository
import app.backupcircle.member.data.MemberStore
import app.backupcircle.member.detect.DetectorRunner
import app.backupcircle.member.ui.applyLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class MemberApp : Application() {
    lateinit var graph: MemberGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = MemberGraph(this)
        // Our store is the source of truth for the language; make AppCompat agree with it.
        graph.store.current.language?.let { applyLanguage(it, onlyIfDifferent = true) }
    }
}

/** Manual wiring for the whole app; no DI framework. */
class MemberGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val log = ConnectionLog()
    val store = MemberStore(appContext)
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
    val ws: WsClient = WsClient(http, scope, log) { repository.wsUrl() }
    val queue: EventQueue = EventQueue(
        File(appContext.filesDir, "event-queue.json"),
        api,
        scope,
        log,
        onAuthError = { repository.onAuthLost() },
        onNetworkTrouble = { locator.rediscover() },
    )
    val repository: MemberRepository = MemberRepository(appContext, store, api, ws, locator, queue, log, scope)
    val runner: DetectorRunner = DetectorRunner(appContext, store, queue, scope)
}

val Context.memberGraph: MemberGraph get() = (applicationContext as MemberApp).graph
