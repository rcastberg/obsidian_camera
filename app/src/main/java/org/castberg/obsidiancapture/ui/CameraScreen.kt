package org.castberg.obsidiancapture.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.castberg.obsidiancapture.data.CaptureRecord
import java.io.File

private data class LensInfo(
    val cameraId: String,
    val focalLength: Float,
    val zoomFactor: Float,
    val label: String
)

private fun buildCameraSelector(cameraId: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter { infoList ->
            infoList.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }
        .build()

private fun detectLenses(context: Context): List<LensInfo> {
    return runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val raw = manager.cameraIdList.mapNotNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (facing == CameraCharacteristics.LENS_FACING_BACK && focalLengths != null && focalLengths.isNotEmpty()) {
                    Pair(id, focalLengths.min())
                } else null
            }.getOrNull()
        }.sortedBy { it.second }

        val minFocal = raw.minOfOrNull { it.second } ?: return@runCatching emptyList()
        raw.mapIndexed { idx, (id, focal) ->
            val zoomFactor = focal / minFocal
            val label = when {
                raw.size == 1 -> "Main"
                idx == 0 -> "Wide"
                idx == raw.size - 1 -> "Tele"
                else -> "Main"
            }
            LensInfo(id, focal, zoomFactor, label)
        }
    }.getOrDefault(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    imageQuality: Int,
    outputFolderUri: String,
    history: List<CaptureRecord>,
    useHighEffort: Boolean,
    isMultiMode: Boolean,
    pendingImageCount: Int,
    onImageCaptured: (ByteArray) -> Unit,
    onNavigateToSettings: () -> Unit,
    onViewCapture: (CaptureRecord) -> Unit,
    onToggleEffort: () -> Unit,
    onToggleMultiMode: () -> Unit,
    onSendMultiImages: () -> Unit
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

    var camera by remember { mutableStateOf<Camera?>(null) }
    var currentZoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    var selectedLensIndex by remember { mutableStateOf(0) }

    val availableLenses = remember { detectLenses(context) }

    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions -> hasCameraPermission = permissions[Manifest.permission.CAMERA] == true }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    val imageCapture = remember(imageQuality) {
        ImageCapture.Builder().setJpegQuality(imageQuality).build()
    }
    LaunchedEffect(flashMode, imageCapture) { imageCapture.flashMode = flashMode }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(hasCameraPermission, selectedLensIndex) {
        if (!hasCameraPermission) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val selector = if (availableLenses.size > 1 && selectedLensIndex < availableLenses.size) {
                runCatching { buildCameraSelector(availableLenses[selectedLensIndex].cameraId) }
                    .getOrDefault(CameraSelector.DEFAULT_BACK_CAMERA)
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            runCatching {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                camera = cam
                cam.cameraInfo.zoomState.observe(lifecycleOwner) { zoomState ->
                    minZoomRatio = zoomState.minZoomRatio
                    maxZoomRatio = zoomState.maxZoomRatio
                    currentZoomRatio = zoomState.zoomRatio
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Obsidian Capture") },
                actions = {
                    IconButton(onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF  -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else                         -> ImageCapture.FLASH_MODE_OFF
                        }
                    }) {
                        Icon(
                            when (flashMode) {
                                ImageCapture.FLASH_MODE_ON   -> Icons.Default.FlashOn
                                ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                                else                         -> Icons.Default.FlashOff
                            },
                            contentDescription = "Flash mode"
                        )
                    }
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
                    Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }) {
                        Text("Grant Permission")
                    }
                }
            } else {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera) {
                            detectTransformGestures { _, _, zoom, _ ->
                                val newZoom = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
                                currentZoomRatio = newZoom
                                camera?.cameraControl?.setZoomRatio(newZoom)
                            }
                        }
                )

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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (availableLenses.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            availableLenses.forEachIndexed { index, lens ->
                                FilterChip(
                                    selected = selectedLensIndex == index,
                                    onClick = {
                                        selectedLensIndex = index
                                        currentZoomRatio = 1f
                                    },
                                    label = { Text("%.1fx".format(lens.zoomFactor)) }
                                )
                            }
                        }
                    }

                    if (isMultiMode && pendingImageCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "$pendingImageCount image${if (pendingImageCount == 1) "" else "s"} queued",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = onSendMultiImages) {
                                Text("Send")
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 32.dp)
                    ) {
                        if (maxZoomRatio > minZoomRatio) {
                            Text(
                                "%.1fx".format(currentZoomRatio),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 32.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
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
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "EFFORT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                            FilterChip(
                                selected = useHighEffort,
                                onClick = { if (!useHighEffort) onToggleEffort() },
                                label = { Text("High") }
                            )
                            FilterChip(
                                selected = !useHighEffort,
                                onClick = { if (useHighEffort) onToggleEffort() },
                                label = { Text("Med") }
                            )
                            Spacer(Modifier.height(4.dp))
                            FilterChip(
                                selected = isMultiMode,
                                onClick = onToggleMultiMode,
                                label = { Text("Multi") }
                            )
                        }
                    }
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
