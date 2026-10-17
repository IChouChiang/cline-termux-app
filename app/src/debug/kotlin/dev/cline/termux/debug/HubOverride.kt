package dev.cline.termux.debug

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * What one `dev.cline.termux.debug.SET_HUB_TOKEN` broadcast asks for (docs/06 "Developer Hub
 * override"). Pure, so [HubTokenOverrideReceiver]'s rules are unit-tested without a receiver.
 */
sealed interface HubOverrideRequest {
    /** Store [token]; [port] (null = none) replaces the Hub port until cleared. */
    data class Set(val token: String, val port: Int?) : HubOverrideRequest

    /** `--ez clear true`: drop the token and the port. */
    data object Clear : HubOverrideRequest

    /** Neither a token nor `clear`, or a port that is not an int in [MIN_PORT]..[MAX_PORT]. */
    data object Invalid : HubOverrideRequest

    companion object {
        /** No privileged ports; the bench Hub is on 25498. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /**
         * [clear] is `--ez clear`, [token] `--es token`, [port] the raw `port` extra (null when
         * absent; `--ei` delivers an Int, anything else is refused rather than guessed).
         */
        fun parse(clear: Boolean, token: String?, port: Any?): HubOverrideRequest {
            if (clear) return Clear
            val trimmed = token?.trim()?.takeIf { it.isNotEmpty() } ?: return Invalid
            if (port == null) return Set(trimmed, null)
            val value = port as? Int ?: return Invalid
            if (value !in MIN_PORT..MAX_PORT) return Invalid
            return Set(trimmed, value)
        }
    }
}

/**
 * The developer override as stored in the debug DataStore: the token sealed by [seal] / [open]
 * (the Keystore `SecretBox` in the app) and the optional port in the clear (it is no secret).
 * A [set] without a port removes an older one, so the stored override is exactly the last push.
 */
class HubOverrideStore(
    private val store: DataStore<Preferences>,
    private val seal: (String) -> String,
    private val open: (String) -> String?,
) {
    suspend fun token(): String? {
        val sealed = store.data.first()[KEY_TOKEN] ?: return null
        return open(sealed)
    }

    /** The port override, only while a token override is stored with it. */
    suspend fun port(): Int? {
        val prefs = store.data.first()
        if (prefs[KEY_TOKEN] == null) return null
        return prefs[KEY_PORT]?.takeIf { it in HubOverrideRequest.MIN_PORT..HubOverrideRequest.MAX_PORT }
    }

    suspend fun set(token: String, port: Int?) {
        val sealed = seal(token)
        store.edit { prefs ->
            prefs[KEY_TOKEN] = sealed
            if (port == null) prefs.remove(KEY_PORT) else prefs[KEY_PORT] = port
        }
    }

    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_PORT)
        }
    }

    companion object {
        val KEY_TOKEN = stringPreferencesKey("hub.token.override")
        val KEY_PORT = intPreferencesKey("hub.port.override")
    }
}
