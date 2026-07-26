package dev.openhelm.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openhelm.app.config.EndpointStore
import dev.openhelm.app.discovery.MfdDiscovery
import dev.openhelm.app.rrc.ConnectionState
import dev.openhelm.app.rrc.RrcClient
import dev.openhelm.protocol.KeyAction
import dev.openhelm.protocol.MfdEndpoint
import dev.openhelm.protocol.MfdKey
import dev.openhelm.protocol.Rrc
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val rrc: RrcClient,
    private val discovery: MfdDiscovery,
    private val store: EndpointStore,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = rrc.state
    val discovered: StateFlow<List<MfdEndpoint>> = discovery.endpoints
    val searching: StateFlow<Boolean> = discovery.searching

    var manualText by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            store.manualAddress.first()?.let { saved ->
                if (manualText.isEmpty()) manualText = saved
            }
        }
    }

    fun onManualTextChange(text: String) {
        manualText = text
    }

    /** Null when [manualText] is not a valid compact address. Drives the Connect button. */
    val manualEndpoint: MfdEndpoint?
        get() = MfdEndpoint.parse(manualText)

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    fun connect(endpoint: MfdEndpoint) {
        rrc.connect(endpoint)
    }

    fun connectManual() {
        val endpoint = manualEndpoint ?: return
        viewModelScope.launch { store.saveManualAddress(manualText.trim()) }
        rrc.connect(endpoint)
    }

    fun disconnect() = rrc.disconnect()

    /**
     * Key events are forwarded exactly as the user produces them. DOWN on touch-down, UP on
     * touch-up — the time in between belongs to the MFD's own auto-repeat, which is what makes
     * holding an arrow key sweep the cursor across the screen.
     */
    fun keyDown(key: MfdKey) = sendButton(key, KeyAction.DOWN)

    fun keyUp(key: MfdKey) = sendButton(key, KeyAction.UP)

    private fun sendButton(key: MfdKey, action: KeyAction) {
        val endpoint = currentEndpoint() ?: return
        rrc.send(Rrc.button(key, action, endpoint.rrcVersion))
    }

    private fun currentEndpoint(): MfdEndpoint? = when (val s = connection.value) {
        is ConnectionState.Connected -> s.endpoint
        is ConnectionState.Connecting -> s.endpoint
        is ConnectionState.Reconnecting -> s.endpoint
        ConnectionState.Idle -> null
    }
}
