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
 * Remembers what the user has connected to, so a boat needs the address found once, not every
 * session. On a boat you connect to the same display every time — discovery should be the
 * fallback, not the ritual — so every *successful* endpoint is persisted, most-recent first, and
 * offered for one-tap reconnect.
 */
@Singleton
class EndpointStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val manualAddressKey = stringPreferencesKey("manual_address")
    private val transportKey = stringPreferencesKey("rtp_transport")
    private val rememberedKey = stringPreferencesKey("remembered_displays")

    val manualAddress: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[manualAddressKey] }

    suspend fun saveManualAddress(line: String) {
        context.dataStore.edit { prefs -> prefs[manualAddressKey] = line }
    }

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
    val rememberedDisplays: Flow<List<MfdEndpoint>> =
        context.dataStore.data.map { prefs ->
            prefs[rememberedKey]?.lineSequence()?.mapNotNull(::decode)?.toList() ?: emptyList()
        }

    /**
     * Record a successful connection. The endpoint moves to the front (dedup by host), and the
     * list is capped so it stays a short chooser, not a history log.
     */
    suspend fun remember(endpoint: MfdEndpoint) {
        context.dataStore.edit { prefs ->
            val existing = prefs[rememberedKey]?.lineSequence()?.mapNotNull(::decode)?.toList() ?: emptyList()
            val deduped = existing.filterNot { it.host == endpoint.host }
            val updated = (listOf(endpoint) + deduped).take(MAX_REMEMBERED)
            prefs[rememberedKey] = updated.joinToString("\n", transform = ::encode)
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 8

        // Tab-separated fields, one record per line. Host and path never contain tabs or newlines,
        // so this needs no escaping — and it keeps model/serial, which the compact address form
        // (used for manual entry) deliberately does not carry.
        fun encode(e: MfdEndpoint): String = listOf(
            e.host, e.rtspPort, e.rrcPort, e.rtspPath, e.rrcVersion, e.model ?: "", e.serial ?: "",
        ).joinToString("\t")

        fun decode(line: String): MfdEndpoint? {
            val f = line.split("\t")
            if (f.size < 5) return null
            return MfdEndpoint(
                host = f[0],
                rtspPort = f[1].toIntOrNull() ?: return null,
                rrcPort = f[2].toIntOrNull() ?: return null,
                rtspPath = f[3],
                rrcVersion = f[4].toIntOrNull() ?: return null,
                model = f.getOrNull(5)?.ifEmpty { null },
                serial = f.getOrNull(6)?.ifEmpty { null },
            )
        }
    }
}
