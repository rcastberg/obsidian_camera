package org.castberg.obsidiancapture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.castberg.obsidiancapture.ui.*
import org.castberg.obsidiancapture.ui.theme.ObsidianCaptureTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObsidianCaptureTheme {
                val settings by viewModel.settings.collectAsState()
                val history by viewModel.history.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(viewModel.saveMessage) {
                    viewModel.saveMessage?.let { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearSaveMessage()
                    }
                }

                when (val screen = viewModel.screen) {
                    is MainViewModel.Screen.Camera -> CameraScreen(
                        imageQuality = settings.imageQuality,
                        outputFolderUri = settings.outputFolderUri,
                        history = history,
                        onImageCaptured = viewModel::onImageCaptured,
                        onNavigateToSettings = viewModel::navigateToSettings,
                        onViewCapture = viewModel::viewCapture
                    )
                    is MainViewModel.Screen.Settings -> SettingsScreen(
                        settings = settings,
                        onSave = viewModel::saveSettings,
                        onNavigateBack = viewModel::navigateToCamera
                    )
                    is MainViewModel.Screen.Processing -> ProcessingScreen(step = screen.step)
                    is MainViewModel.Screen.ViewCapture -> CaptureDetailScreen(
                        record = screen.record,
                        onClose = viewModel::navigateToCamera
                    )
                    is MainViewModel.Screen.Error -> ErrorScreen(
                        message = screen.message,
                        canRetry = screen.imageBytes != null,
                        onRetry = viewModel::retry,
                        onRetake = viewModel::retake
                    )
                }
            }
        }
    }
}
