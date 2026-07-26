package dev.openhelm.app.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.openhelm.protocol.MfdEndpoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openhelm")

/**
 * A display the user has connected to before: the full endpoint (the backend identity — host,
 * ports, path, version, serial) plus an optional human [name] the user can set. The card shows
 * [label]; the connection uses [endpoint].
 */
data class RememberedDisplay(
    val endpoint: MfdEndpoint,
    val name: String? = null,
) {
    /**
     * What to show a human. The user's own name wins; then the model TXT (e.g. `E9` — note it is
     * abbreviated and may not match the hardware, which is why renaming exists); then the address.
     */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: endpoint.model ?: endpoint.host
}

/**
 * Remembers what the user has connected to, so a boat needs the address found once, not every
 * session. On a boat you connect to the same display every time — discovery should be the
 * fallback, not the ritual — so every *successful* endpoint is persisted, most-recent first, and
 * offered for one-tap reconnect and launch-time auto-reconnect.
 */
@Singleton
class EndpointStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val transportKey = stringPreferencesKey("rtp_transport")
    private val rememberedKey = stringPreferencesKey("remembered_displays")

    /**
     * "tcp" selects the simulator-only interleaved transport; anything else means UDP, the only
     * transport a real display can serve. Persisted because a development phone points at the
     * simulator across many sessions.
     */
    val rtpTransport: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[transportKey] }

    suspend fun saveRtpTransport(value: String) {
        context.dataStore.edit { prefs -> prefs[transportKey] = value }
    }

    /** Every remembered display, most-recently-connected first. */
    val rememberedDisplays: Flow<List<RememberedDisplay>> =
        context.dataStore.data.map { prefs -> readList(prefs[rememberedKey]) }

    /**
     * Record a successful connection. The endpoint moves to the front (dedup by host), any name
     * the user already gave it is preserved, and the list is capped so it stays a short chooser.
     */
    suspend fun remember(endpoint: MfdEndpoint) {
        context.dataStore.edit { prefs ->
            val existing = readList(prefs[rememberedKey])
            val keptName = existing.firstOrNull { it.endpoint.host == endpoint.host }?.name
            val deduped = existing.filterNot { it.endpoint.host == endpoint.host }
            val updated = (listOf(RememberedDisplay(endpoint, keptName)) + deduped).take(MAX_REMEMBERED)
            prefs[rememberedKey] = writeList(updated)
        }
    }

    /** Give a remembered display (identified by host) a human name; blank clears it. */
    suspend fun rename(host: String, name: String) {
        context.dataStore.edit { prefs ->
            val updated = readList(prefs[rememberedKey]).map {
                if (it.endpoint.host == host) it.copy(name = name.trim().ifBlank { null }) else it
            }
            prefs[rememberedKey] = writeList(updated)
        }
    }

    /** Drop a remembered display. */
    suspend fun forget(host: String) {
        context.dataStore.edit { prefs ->
            val updated = readList(prefs[rememberedKey]).filterNot { it.endpoint.host == host }
            prefs[rememberedKey] = writeList(updated)
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 12

        fun writeList(items: List<RememberedDisplay>): String =
            items.joinToString("\n", transform = ::encode)

        fun readList(raw: String?): List<RememberedDisplay> =
            raw?.lineSequence()?.mapNotNull(::decode)?.toList() ?: emptyList()

        // Tab-separated fields, one record per line. Host and path never contain tabs or newlines,
        // so this needs no escaping. `name` is appended last, so records written before naming
        // existed (no 8th field) still decode, with name = null.
        fun encode(d: RememberedDisplay): String = listOf(
            d.endpoint.host,
            d.endpoint.rtspPort,
            d.endpoint.rrcPort,
            d.endpoint.rtspPath,
            d.endpoint.rrcVersion,
            d.endpoint.model ?: "",
            d.endpoint.serial ?: "",
            d.name ?: "",
        ).joinToString("\t")

        fun decode(line: String): RememberedDisplay? {
            val f = line.split("\t")
            if (f.size < 5) return null
            val endpoint = MfdEndpoint(
                host = f[0],
                rtspPort = f[1].toIntOrNull() ?: return null,
                rrcPort = f[2].toIntOrNull() ?: return null,
                rtspPath = f[3],
                rrcVersion = f[4].toIntOrNull() ?: return null,
                model = f.getOrNull(5)?.ifEmpty { null },
                serial = f.getOrNull(6)?.ifEmpty { null },
            )
            return RememberedDisplay(endpoint, f.getOrNull(7)?.ifEmpty { null })
        }
    }
}
