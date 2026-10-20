package app.backupcircle.guardian.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.guardianDataStore by preferencesDataStore(name = "guardian")

data class GuardianPrefs(
    val name: String? = null,
    val guardianId: String? = null,
    val token: String? = null,
    val serverAddress: String? = null,
    /** Highest event id the guardian has already been alerted about. */
    val lastNotifiedEventId: Long = 0,
    val onboardingDone: Boolean = false,
)

class GuardianStore(context: Context) {
    private val ds = context.guardianDataStore

    private object Keys {
        val NAME = stringPreferencesKey("name")
        val GUARDIAN_ID = stringPreferencesKey("guardian_id")
        val TOKEN = stringPreferencesKey("token")
        val SERVER = stringPreferencesKey("server_address")
        val LAST_NOTIFIED = longPreferencesKey("last_notified_event_id")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
    }

    /** Synchronous view for code that needs the token right now (tiny file, read once at start). */
    @Volatile
    var current: GuardianPrefs = runBlocking { read(ds.data.first()) }
        private set

    val flow: Flow<GuardianPrefs> = ds.data.map(::read)

    private fun read(p: Preferences) = GuardianPrefs(
        name = p[Keys.NAME],
        guardianId = p[Keys.GUARDIAN_ID],
        token = p[Keys.TOKEN],
        serverAddress = p[Keys.SERVER],
        lastNotifiedEventId = p[Keys.LAST_NOTIFIED] ?: 0,
        onboardingDone = p[Keys.ONBOARDED] ?: false,
    )

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        val updated = ds.edit { block(it) }
        current = read(updated)
    }

    suspend fun setIdentity(name: String, guardianId: String, token: String) = edit {
        it[Keys.NAME] = name
        it[Keys.GUARDIAN_ID] = guardianId
        it[Keys.TOKEN] = token
        it[Keys.LAST_NOTIFIED] = 0
    }

    suspend fun setName(name: String) = edit { it[Keys.NAME] = name }

    suspend fun setServer(address: String) = edit { it[Keys.SERVER] = address }

    suspend fun setLastNotified(eventId: Long) = edit {
        if (eventId > (it[Keys.LAST_NOTIFIED] ?: 0)) it[Keys.LAST_NOTIFIED] = eventId
    }

    suspend fun setOnboardingDone() = edit { it[Keys.ONBOARDED] = true }

    /** The server forgot us (it was reset): keep the name and server, drop the identity. */
    suspend fun clearIdentity() = edit {
        it.remove(Keys.GUARDIAN_ID)
        it.remove(Keys.TOKEN)
        it[Keys.LAST_NOTIFIED] = 0
    }

    suspend fun clearAll() = edit { it.clear() }
}
