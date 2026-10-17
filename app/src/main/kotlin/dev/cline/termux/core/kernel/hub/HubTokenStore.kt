package dev.cline.termux.core.kernel.hub

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cline.termux.core.secure.SecretBox
import kotlinx.coroutines.flow.first

private val Context.kernelDataStore: DataStore<Preferences> by preferencesDataStore(name = "kernel")

/** The one cached Hub credential, bound to the daemon lifetime it came from. */
data class StoredHubToken(val token: String, val lifetimeKey: String, val port: Int)

/**
 * Keeps the Hub auth token sealed with [SecretBox] in DataStore. Never logged,
 * never written anywhere else; a daemon restart (different lifetime key)
 * invalidates it and the kernel coordinator re-reads production.json.
 */
class HubTokenStore(private val context: Context, private val box: SecretBox = SecretBox()) {

    suspend fun read(): StoredHubToken? {
        val prefs = context.kernelDataStore.data.first()
        val sealed = prefs[KEY_TOKEN] ?: return null
        val lifetime = prefs[KEY_LIFETIME] ?: return null
        val port = prefs[KEY_PORT]?.toIntOrNull() ?: HubEndpoints.DEFAULT_PORT
        val token = box.open(sealed) ?: return null
        return StoredHubToken(token, lifetime, port)
    }

    suspend fun write(record: HubDiscoveryRecord) {
        val sealed = box.seal(record.authToken)
        context.kernelDataStore.edit { prefs ->
            prefs[KEY_TOKEN] = sealed
            prefs[KEY_LIFETIME] = record.lifetimeKey
            prefs[KEY_PORT] = record.port.toString()
        }
    }

    suspend fun clear() {
        context.kernelDataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_LIFETIME)
            prefs.remove(KEY_PORT)
        }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("hub.token.sealed")
        val KEY_LIFETIME = stringPreferencesKey("hub.token.lifetime")
        val KEY_PORT = stringPreferencesKey("hub.token.port")
    }
}
