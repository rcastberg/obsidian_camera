package org.castberg.obsidiancapture

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.castberg.obsidiancapture.data.AppSettings
import org.castberg.obsidiancapture.data.CaptureRecord
import org.castberg.obsidiancapture.data.LlmClient
import org.castberg.obsidiancapture.data.SettingsRepository
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
    val address: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val httpClient = OkHttpClient()

    val settings = settingsRepo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings()
    )

    val history = settingsRepo.history.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    sealed class Screen {
        object Camera : Screen()
        object Settings : Screen()
        data class Processing(val step: String) : Screen()
        data class ViewCapture(val record: CaptureRecord) : Screen()
        data class Error(val message: String, val imageBytes: ByteArray?) : Screen()
    }

    var screen by mutableStateOf<Screen>(Screen.Camera)
        private set

    var saveMessage by mutableStateOf<String?>(null)
        private set

    private val _finishEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finishEvent: SharedFlow<Unit> = _finishEvent.asSharedFlow()

    private var inactivityJob: Job? = null

    fun startInactivityTimer() {
        if (screen !is Screen.Camera) return
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            delay(2 * 60 * 1000L)
            _finishEvent.emit(Unit)
        }
    }

    fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    var useHighEffort by mutableStateOf(true)
        private set

    fun toggleEffort() { useHighEffort = !useHighEffort }

    var isMultiMode by mutableStateOf(false)
        private set

    var pendingImageCount by mutableStateOf(0)
        private set

    private val _pendingImages = mutableListOf<ByteArray>()

    fun toggleMultiMode() {
        isMultiMode = !isMultiMode
        if (!isMultiMode) {
            _pendingImages.clear()
            pendingImageCount = 0
        }
    }

    init {
        viewModelScope.launch {
            useHighEffort = settingsRepo.settings.first().defaultHighEffort
        }
    }

    private var lastImageBytes: ByteArray? = null

    fun navigateToSettings() {
        cancelInactivityTimer()
        screen = Screen.Settings
    }
    fun navigateToCamera() {
        saveMessage = null
        screen = Screen.Camera
        startInactivityTimer()
    }
    fun clearSaveMessage() { saveMessage = null }

    fun viewCapture(record: CaptureRecord) {
        cancelInactivityTimer()
        screen = Screen.ViewCapture(record)
    }

    fun onImageCaptured(bytes: ByteArray) {
        cancelInactivityTimer()
        if (isMultiMode) {
            _pendingImages.add(bytes)
            pendingImageCount = _pendingImages.size
            return
        }
        lastImageBytes = bytes
        val location = getLastKnownLocation()
        viewModelScope.launch {
            runCatching {
                val s = settings.value

                screen = Screen.Processing("Analyzing image…")
                val correctedBytes = fixImageOrientation(bytes, s.imageQuality)
                lastImageBytes = correctedBytes

                val model = if (useHighEffort) s.highEffortModel else s.mediumEffortModel
                val prompt = if (useHighEffort)
                    s.systemPrompt.ifBlank { LlmClient.DEFAULT_ANALYSIS_PROMPT }
                else
                    s.mediumSystemPrompt.ifBlank { LlmClient.DEFAULT_MEDIUM_ANALYSIS_PROMPT }
                val markdown = LlmClient.analyzeImage(s, correctedBytes, model, prompt)

                screen = Screen.Processing("Naming document…")
                val filename = LlmClient.generateFilename(s, markdown)

                screen = Screen.Processing("Saving…")
                val baseName = saveFiles(correctedBytes, filename, markdown, s, location)

                val record = CaptureRecord(
                    id = System.currentTimeMillis().toString(),
                    baseName = baseName,
                    markdown = markdown,
                    timestamp = System.currentTimeMillis(),
                    folderUri = s.outputFolderUri
                )
                settingsRepo.addRecord(record)

                saveMessage = "Saved: $baseName"
                screen = Screen.Camera
                startInactivityTimer()
            }.onFailure { e ->
                screen = Screen.Error(e.message ?: "Unknown error", lastImageBytes)
            }
        }
    }

    private suspend fun fixImageOrientation(bytes: ByteArray, quality: Int): ByteArray =
        withContext(Dispatchers.IO) {
            runCatching {
                val orientation = ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> return@withContext bytes
                }
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext bytes
                val rotated = Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(rotation) }, true
                )
                source.recycle()
                ByteArrayOutputStream().also { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    rotated.recycle()
                }.toByteArray()
            }.getOrDefault(bytes)
        }

    private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name) ?: parent.createDirectory(name)
            ?: error("Cannot create directory: $name")

    private fun buildFrontmatter(location: LocationData?) =
        if (location != null) buildString {
            append("---\n")
            append("latitude: ${location.latitude}\n")
            append("longitude: ${location.longitude}\n")
            location.name?.let { append("place: \"$it\"\n") }
            location.address?.let { append("address: \"$it\"\n") }
            append("map: \"https://www.google.com/maps?q=${location.latitude},${location.longitude}\"\n")
            append("---\n\n")
        } else ""

    private suspend fun saveFiles(
        bytes: ByteArray,
        filename: String,
        markdown: String,
        settings: AppSettings,
        locationData: LocationData?
    ): String = withContext(Dispatchers.IO) {
        val context: Context = getApplication()
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.outputFolderUri))
            ?: error("Cannot access output folder")

        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val baseName = "$dateStr-$filename"

        val noteFolder = getOrCreateDir(getOrCreateDir(folder, "_resources"), baseName)
        val imageFile = noteFolder.createFile("image/jpeg", "image.jpg")
            ?: error("Cannot create image file")
        context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(bytes) }

        val fullLocation = if (locationData != null) {
            try { reverseGeocode(locationData) } catch (_: Exception) { locationData }
        } else null

        val mdContent = "${buildFrontmatter(fullLocation)}$markdown\n\n![$baseName](_resources/$baseName/image.jpg)\n"
        val mdFile = folder.createFile("text/markdown", "$baseName.md")
            ?: error("Cannot create markdown file")
        context.contentResolver.openOutputStream(mdFile.uri)?.use { it.write(mdContent.toByteArray()) }

        baseName
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): LocationData? {
        val context: Context = getApplication()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: return null
        return LocationData(loc.latitude, loc.longitude)
    }

    private suspend fun reverseGeocode(location: LocationData): LocationData = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=${location.latitude}&lon=${location.longitude}&zoom=18&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "ObsidianCapture/1.0")
            .build()

        val body = httpClient.newCall(request).execute().use { it.body?.string() }
            ?: return@withContext location
        val json = JSONObject(body)

        val name = json.optString("name").takeIf { it.isNotBlank() }
        val addr = json.optJSONObject("address")
        val address = addr?.let {
            listOfNotNull(
                it.optString("road").takeIf { s -> s.isNotBlank() },
                (it.optString("city").takeIf { s -> s.isNotBlank() }
                    ?: it.optString("town").takeIf { s -> s.isNotBlank() }
                    ?: it.optString("village").takeIf { s -> s.isNotBlank() }),
                it.optString("country").takeIf { s -> s.isNotBlank() }
            ).joinToString(", ").takeIf { s -> s.isNotBlank() }
        }

        location.copy(name = name, address = address)
    }

    fun onSendMultiImages() {
        cancelInactivityTimer()
        val images = _pendingImages.toList()
        if (images.isEmpty()) return
        _pendingImages.clear()
        pendingImageCount = 0
        val location = getLastKnownLocation()
        viewModelScope.launch {
            runCatching {
                val s = settings.value
                screen = Screen.Processing("Analyzing ${images.size} images…")
                val correctedImages = images.map { fixImageOrientation(it, s.imageQuality) }

                val model = if (useHighEffort) s.highEffortModel else s.mediumEffortModel
                val prompt = if (useHighEffort)
                    s.systemPrompt.ifBlank { LlmClient.DEFAULT_ANALYSIS_PROMPT }
                else
                    s.mediumSystemPrompt.ifBlank { LlmClient.DEFAULT_MEDIUM_ANALYSIS_PROMPT }

                val markdown = LlmClient.analyzeImages(s, correctedImages, model, prompt)

                screen = Screen.Processing("Naming document…")
                val filename = LlmClient.generateFilename(s, markdown)

                screen = Screen.Processing("Saving…")
                val baseName = saveMultipleFiles(correctedImages, filename, markdown, s, location)

                val record = CaptureRecord(
                    id = System.currentTimeMillis().toString(),
                    baseName = baseName,
                    markdown = markdown,
                    timestamp = System.currentTimeMillis(),
                    folderUri = s.outputFolderUri
                )
                settingsRepo.addRecord(record)

                saveMessage = "Saved: $baseName"
                screen = Screen.Camera
                startInactivityTimer()
            }.onFailure { e ->
                screen = Screen.Error(e.message ?: "Unknown error", null)
            }
        }
    }

    private suspend fun saveMultipleFiles(
        imagesList: List<ByteArray>,
        filename: String,
        markdown: String,
        settings: AppSettings,
        locationData: LocationData?
    ): String = withContext(Dispatchers.IO) {
        val context: Context = getApplication()
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.outputFolderUri))
            ?: error("Cannot access output folder")

        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val baseName = "$dateStr-$filename"

        val noteFolder = getOrCreateDir(getOrCreateDir(folder, "_resources"), baseName)
        val imageRefs = StringBuilder()
        imagesList.forEachIndexed { index, bytes ->
            val imageName = "image-${index + 1}.jpg"
            val imageFile = noteFolder.createFile("image/jpeg", imageName)
                ?: error("Cannot create image file $imageName")
            context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(bytes) }
            imageRefs.append("![$baseName-${index + 1}](_resources/$baseName/$imageName)\n")
        }

        val fullLocation = if (locationData != null) {
            try { reverseGeocode(locationData) } catch (_: Exception) { locationData }
        } else null

        val mdContent = "${buildFrontmatter(fullLocation)}$markdown\n\n$imageRefs"
        val mdFile = folder.createFile("text/markdown", "$baseName.md")
            ?: error("Cannot create markdown file")
        context.contentResolver.openOutputStream(mdFile.uri)?.use { it.write(mdContent.toByteArray()) }

        baseName
    }

    fun retry() {
        val bytes = lastImageBytes ?: return
        onImageCaptured(bytes)
    }

    fun retake() {
        screen = Screen.Camera
        startInactivityTimer()
    }

    fun saveSettings(new: AppSettings) {
        viewModelScope.launch { settingsRepo.save(new) }
    }
}
