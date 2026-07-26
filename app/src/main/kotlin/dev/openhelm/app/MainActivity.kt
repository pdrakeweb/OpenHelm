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
import dev.openhelm.app.ui.MainViewModel
import dev.openhelm.app.ui.ManualConnectScreen
import dev.openhelm.app.ui.OpenHelmTheme
import dev.openhelm.app.ui.RemoteScreen
import dev.openhelm.app.ui.Route
import dev.openhelm.app.ui.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenHelmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.connection.collectAsStateWithLifecycle()

    if (state !is ConnectionState.Idle) {
        RemoteScreen(viewModel, state)
        return
    }
    when (viewModel.route) {
        Route.CONNECT -> ConnectScreen(viewModel)
        Route.MANUAL -> ManualConnectScreen(viewModel)
        Route.SETTINGS -> SettingsScreen(viewModel)
    }
}
