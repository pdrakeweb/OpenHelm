package dev.openhelm.app.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "openhelm")

/**
 * Persists the last manually entered address, so a boat whose mDNS is misbehaving needs the
 * address typed once, not every session.
 */
@Singleton
class EndpointStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val manualAddressKey = stringPreferencesKey("manual_address")
    private val transportKey = stringPreferencesKey("rtp_transport")

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
}
