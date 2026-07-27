package dev.openhelm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The view model is resolved above the theme, not inside AppRoot, because the palette
            // it holds decides the theme for every screen below.
            val viewModel: MainViewModel = viewModel()
            val palette = viewModel.palette ?: DefaultPalette

            OpenHelmTheme(palette = palette) {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(viewModel, palette)
                }
            }
        }
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
