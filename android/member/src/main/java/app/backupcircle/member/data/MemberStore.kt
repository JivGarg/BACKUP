package app.backupcircle.member.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.backupcircle.core.net.BackupJson
import app.backupcircle.core.net.GuardianSummary
import app.backupcircle.core.net.MemberSettingsDto
import app.backupcircle.member.detect.DetectorConfig
import app.backupcircle.member.detect.RiskCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val Context.memberDataStore by preferencesDataStore(name = "member")

@Serializable
data class WatchedApp(val packageName: String, val label: String)

val DEFAULT_WATCHED_APPS = listOf(
    WatchedApp("com.phonepe.app", "PhonePe"),
    WatchedApp("com.google.android.apps.nbu.paisa.user", "Google Pay"),
    WatchedApp("net.one97.paytm", "Paytm"),
    WatchedApp("in.org.npci.upiapp", "BHIM"),
    WatchedApp("com.dreamplug.androidapp", "CRED"),
    WatchedApp("com.mobikwik_new", "MobiKwik"),
    WatchedApp("com.sbi.lotusintouch", "YONO SBI"),
)

data class MemberPrefs(
    val language: String? = null,
    val started: Boolean = false,
    val permissionsDone: Boolean = false,
    val memberId: String? = null,
    val token: String? = null,
    val serverAddress: String? = null,
    val guardians: List<GuardianSummary> = emptyList(),
    /** guardian id -> phone number the member saved for the Call button */
    val guardianPhones: Map<String, String> = emptyMap(),
    val shareCalls: Boolean = true,
    val sharePayments: Boolean = true,
    val thresholdSeconds: Int = 300,
    val windowMinutes: Int = 15,
    val watched: List<WatchedApp> = DEFAULT_WATCHED_APPS,
    val autostartOpened: Boolean = false,
    val risk: RiskCall? = null,
) {
    fun detectorConfig() = DetectorConfig(
        thresholdSeconds = thresholdSeconds,
        windowMinutes = windowMinutes,
        watched = watched.associate { it.packageName to it.label },
        shareCalls = shareCalls,
        sharePayments = sharePayments,
    )

    fun settingsDto() = MemberSettingsDto(shareCalls, sharePayments, thresholdSeconds, windowMinutes)
}

class MemberStore(context: Context) {
    private val ds = context.memberDataStore

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val STARTED = booleanPreferencesKey("started")
        val PERMISSIONS_DONE = booleanPreferencesKey("permissions_done")
        val MEMBER_ID = stringPreferencesKey("member_id")
        val TOKEN = stringPreferencesKey("token")
        val SERVER = stringPreferencesKey("server_address")
        val GUARDIANS = stringPreferencesKey("guardians")
        val GUARDIAN_PHONES = stringPreferencesKey("guardian_phones")
        val SHARE_CALLS = booleanPreferencesKey("share_calls")
        val SHARE_PAYMENTS = booleanPreferencesKey("share_payments")
        val THRESHOLD = intPreferencesKey("threshold_seconds")
        val WINDOW = intPreferencesKey("window_minutes")
        val WATCHED = stringPreferencesKey("watched_apps")
        val AUTOSTART = booleanPreferencesKey("autostart_opened")
        val RISK = stringPreferencesKey("risk_call")
    }

    private val guardianList = ListSerializer(GuardianSummary.serializer())
    private val phoneMap = MapSerializer(String.serializer(), String.serializer())
    private val watchedList = ListSerializer(WatchedApp.serializer())

    private val _state = MutableStateFlow(runBlocking { read(ds.data.first()) })
    /** Always-current settings; also readable synchronously by the detector. */
    val state: StateFlow<MemberPrefs> = _state.asStateFlow()
    val current: MemberPrefs get() = _state.value

    private fun read(p: Preferences) = MemberPrefs(
        language = p[Keys.LANGUAGE],
        started = p[Keys.STARTED] ?: false,
        permissionsDone = p[Keys.PERMISSIONS_DONE] ?: false,
        memberId = p[Keys.MEMBER_ID],
        token = p[Keys.TOKEN],
        serverAddress = p[Keys.SERVER],
        guardians = p[Keys.GUARDIANS]?.let { runCatching { BackupJson.decodeFromString(guardianList, it) }.getOrNull() } ?: emptyList(),
        guardianPhones = p[Keys.GUARDIAN_PHONES]?.let { runCatching { BackupJson.decodeFromString(phoneMap, it) }.getOrNull() } ?: emptyMap(),
        shareCalls = p[Keys.SHARE_CALLS] ?: true,
        sharePayments = p[Keys.SHARE_PAYMENTS] ?: true,
        thresholdSeconds = p[Keys.THRESHOLD] ?: 300,
        windowMinutes = p[Keys.WINDOW] ?: 15,
        watched = p[Keys.WATCHED]?.let { runCatching { BackupJson.decodeFromString(watchedList, it) }.getOrNull() } ?: DEFAULT_WATCHED_APPS,
        autostartOpened = p[Keys.AUTOSTART] ?: false,
        risk = p[Keys.RISK]?.let { runCatching { BackupJson.decodeFromString(RiskCall.serializer(), it) }.getOrNull() },
    )

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        val updated = ds.edit { block(it) }
        _state.value = read(updated)
    }

    suspend fun setLanguage(code: String) = edit { it[Keys.LANGUAGE] = code }
    suspend fun setStarted() = edit { it[Keys.STARTED] = true }
    suspend fun setPermissionsDone() = edit { it[Keys.PERMISSIONS_DONE] = true }
    suspend fun setServer(address: String) = edit { it[Keys.SERVER] = address }
    suspend fun setAutostartOpened() = edit { it[Keys.AUTOSTART] = true }

    suspend fun setIdentity(memberId: String, token: String) = edit {
        it[Keys.MEMBER_ID] = memberId
        it[Keys.TOKEN] = token
    }

    /** The server forgot this phone (it was reset): drop identity and guardians, keep settings. */
    suspend fun clearIdentity() = edit {
        it.remove(Keys.MEMBER_ID)
        it.remove(Keys.TOKEN)
        it.remove(Keys.GUARDIANS)
    }

    suspend fun setGuardians(list: List<GuardianSummary>) = edit {
        it[Keys.GUARDIANS] = BackupJson.encodeToString(guardianList, list)
    }

    suspend fun setGuardianPhone(guardianId: String, phone: String) = edit {
        val phones = current.guardianPhones.toMutableMap()
        if (phone.isBlank()) phones.remove(guardianId) else phones[guardianId] = phone.trim()
        it[Keys.GUARDIAN_PHONES] = BackupJson.encodeToString(phoneMap, phones)
    }

    suspend fun setShareCalls(on: Boolean) = edit { it[Keys.SHARE_CALLS] = on }
    suspend fun setSharePayments(on: Boolean) = edit { it[Keys.SHARE_PAYMENTS] = on }
    suspend fun setThreshold(seconds: Int) = edit { it[Keys.THRESHOLD] = seconds }
    suspend fun setWindow(minutes: Int) = edit { it[Keys.WINDOW] = minutes }

    suspend fun setWatched(apps: List<WatchedApp>) = edit {
        it[Keys.WATCHED] = BackupJson.encodeToString(watchedList, apps)
    }

    suspend fun setRisk(risk: RiskCall?) = edit {
        if (risk == null) it.remove(Keys.RISK) else it[Keys.RISK] = BackupJson.encodeToString(RiskCall.serializer(), risk)
    }

    suspend fun clearAll() = edit { it.clear() }
}
