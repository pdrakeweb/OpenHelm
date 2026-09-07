package dev.openhelm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhelm.app.rrc.ConnectionState
import dev.openhelm.app.ui.ConnectScreen
import dev.openhelm.app.ui.HelmPalette
import dev.openhelm.app.ui.MainViewModel
import dev.openhelm.app.ui.DefaultPalette
import dev.openhelm.app.ui.ManualConnectScreen
import dev.openhelm.app.ui.OpenHelmTheme
import dev.openhelm.app.ui.RemoteScreen
import dev.openhelm.app.ui.Route
import dev.openhelm.app.ui.SettingsScreen
import dev.openhelm.app.ui.SimulatedRemoteScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // An Activity-level property, not resolved inside setContent, so the lifecycle callbacks below
    // — which run outside Compose entirely — can reach the same instance Compose is observing.
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The palette is read above the theme, not inside AppRoot, because it decides the
            // theme for every screen below.
            val palette = viewModel.palette ?: DefaultPalette

            OpenHelmTheme(palette = palette) {
                // The Surface fills the whole window and the inset is applied *inside* it. With the
                // padding on the Surface itself, the strip beside the camera cutout fell outside
                // the coloured area and showed the raw window background — a black band down one
                // edge of a bright or red interface.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppRoot(viewModel, palette)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForegrounded()
    }

    /**
     * The screen turning off, or the app being switched away from, both make the activity no
     * longer visible and land here — the trigger for the affirmative video teardown described in
     * [MainViewModel.onBackgrounded]'s doc.
     */
    override fun onStop() {
        viewModel.onBackgrounded()
        super.onStop()
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel, palette: HelmPalette) {
    if (viewModel.simulationMode) {
        SimulatedRemoteScreen(viewModel, palette)
        return
    }

    val state by viewModel.connection.collectAsStateWithLifecycle()
    if (state !is ConnectionState.Idle) {
        RemoteScreen(viewModel, state, palette)
        return
    }
    when (viewModel.route) {
        Route.CONNECT -> ConnectScreen(viewModel)
        Route.MANUAL -> ManualConnectScreen(viewModel)
        Route.SETTINGS -> SettingsScreen(viewModel, palette)
    }
}
