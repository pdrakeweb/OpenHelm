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
    private val paletteKey = stringPreferencesKey("helm_palette")

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

    /**
     * The chosen day/dusk/night palette, or null if the user has never chosen — in which case the
     * UI shows its own default (dark; see `DefaultPalette`), deliberately ignoring the system
     * light/dark setting, which describes a living room rather than a cockpit.
     *
     * Persisted, unlike simulation mode: night vision takes twenty minutes to build and seconds to
     * destroy, so a boat that came alongside after dark must not relaunch into a white screen. The
     * name is stored rather than the ordinal, so reordering the enum cannot silently change which
     * palette a user gets.
     */
    val palette: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[paletteKey] }

    suspend fun savePalette(value: String) {
        context.dataStore.edit { prefs -> prefs[paletteKey] = value }
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

    internal companion object {
        const val MAX_REMEMBERED = 12

        fun writeList(items: List<RememberedDisplay>): String =
            items.joinToString("\n", transform = ::encode)

        fun readList(raw: String?): List<RememberedDisplay> =
            raw?.lineSequence()?.mapNotNull(::decode)?.toList() ?: emptyList()

        // Tab-separated fields, one record per line, with the separators escaped.
        //
        // The escaping is not decoration. `rtspPath`, `model` and `serial` are copied verbatim out
        // of mDNS TXT records — bytes any device on the same Wi-Fi can choose — and `name` is typed
        // by the user, who can paste anything. A field carrying a literal tab silently shifts every
        // field after it; one carrying a newline ends the record early and starts a forged one. So
        // a single advertisement could rewrite an existing trusted entry's host, pointing a
        // remembered "Helm E95" at an address of the advertiser's choosing, or flood the list until
        // MAX_REMEMBERED evicted the real displays.
        //
        // `name` is appended last, so records written before naming existed (no 8th field) still
        // decode, with name = null.
        fun encode(d: RememberedDisplay): String = listOf(
            esc(d.endpoint.host),
            d.endpoint.rtspPort.toString(),
            d.endpoint.rrcPort.toString(),
            esc(d.endpoint.rtspPath),
            d.endpoint.rrcVersion.toString(),
            esc(d.endpoint.model ?: ""),
            esc(d.endpoint.serial ?: ""),
            esc(d.name ?: ""),
        ).joinToString("\t")

        fun decode(line: String): RememberedDisplay? {
            val f = line.split("\t")
            if (f.size < 5) return null
            // Ports are validated on the way *out* of storage, not just on the way in: these
            // records are probed automatically at launch, and `InetSocketAddress` throws an
            // unchecked exception for an out-of-range port. A corrupt record must decode to
            // nothing, never to an endpoint that detonates inside a connection loop.
            val endpoint = MfdEndpoint(
                host = unesc(f[0]),
                rtspPort = f[1].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null,
                rrcPort = f[2].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null,
                rtspPath = unesc(f[3]),
                rrcVersion = f[4].toIntOrNull() ?: return null,
                model = f.getOrNull(5)?.let(::unesc)?.ifEmpty { null },
                serial = f.getOrNull(6)?.let(::unesc)?.ifEmpty { null },
            )
            return RememberedDisplay(endpoint, f.getOrNull(7)?.let(::unesc)?.ifEmpty { null })
        }

        /** Escape the two characters this format is built out of, and the backslash that escapes them. */
        fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }

        fun unesc(s: String): String {
            if ('\\' !in s) return s // the common case, and every record written before escaping
            return buildString(s.length) {
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c != '\\' || i == s.lastIndex) {
                        append(c)
                        i++
                    } else {
                        when (val n = s[i + 1]) {
                            't' -> append('\t')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            '\\' -> append('\\')
                            else -> append(c).append(n) // not ours; leave it as written
                        }
                        i += 2
                    }
                }
            }
        }
    }
}
