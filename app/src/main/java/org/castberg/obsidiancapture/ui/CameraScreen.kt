package org.castberg.obsidiancapture.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.castberg.obsidiancapture.data.CaptureRecord
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    imageQuality: Int,
    outputFolderUri: String,
    history: List<CaptureRecord>,
    onImageCaptured: (ByteArray) -> Unit,
    onNavigateToSettings: () -> Unit,
    onViewCapture: (CaptureRecord) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var captureError by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val imageCapture = remember(imageQuality) {
        ImageCapture.Builder().setJpegQuality(imageQuality).build()
    }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Obsidian Capture") },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        BadgedBox(
                            badge = {
                                if (history.isNotEmpty()) Badge { Text("${history.size}") }
                            }
                        ) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Camera permission is required", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                    if (outputFolderUri.isBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(
                                "Output folder not set — tap Settings to configure",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    captureError?.let { msg ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(msg, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        captureError = null
                        if (outputFolderUri.isBlank()) { onNavigateToSettings(); return@FloatingActionButton }
                        runCatching {
                            val tempFile = File.createTempFile("cap", ".jpg", context.cacheDir)
                            val opts = ImageCapture.OutputFileOptions.Builder(tempFile).build()
                            imageCapture.takePicture(
                                opts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        val bytes = tempFile.readBytes()
                                        tempFile.delete()
                                        onImageCaptured(bytes)
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        tempFile.delete()
                                        captureError = e.message ?: "Capture failed"
                                    }
                                }
                            )
                        }.onFailure { captureError = it.message ?: "Capture failed" }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                }
            }
        }
    }

    if (showHistory) {
        HistorySheet(
            records = history,
            onSelect = { record ->
                showHistory = false
                onViewCapture(record)
            },
            onDismiss = { showHistory = false }
        )
    }
}
