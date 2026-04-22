package org.castberg.obsidiancapture

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.castberg.obsidiancapture.data.AppSettings
import org.castberg.obsidiancapture.data.CaptureRecord
import org.castberg.obsidiancapture.data.LlmClient
import org.castberg.obsidiancapture.data.SettingsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)

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

    private var lastImageBytes: ByteArray? = null

    fun navigateToSettings() { screen = Screen.Settings }
    fun navigateToCamera() {
        saveMessage = null
        screen = Screen.Camera
    }
    fun clearSaveMessage() { saveMessage = null }

    fun viewCapture(record: CaptureRecord) { screen = Screen.ViewCapture(record) }

    fun onImageCaptured(bytes: ByteArray) {
        lastImageBytes = bytes
        viewModelScope.launch {
            runCatching {
                val s = settings.value

                screen = Screen.Processing("Analyzing image…")
                val markdown = LlmClient.analyzeImage(s, bytes)

                screen = Screen.Processing("Naming document…")
                val filename = LlmClient.generateFilename(s, markdown)

                screen = Screen.Processing("Saving…")
                val baseName = saveFiles(bytes, filename, markdown, s)

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
            }.onFailure { e ->
                screen = Screen.Error(e.message ?: "Unknown error", bytes)
            }
        }
    }

    private suspend fun saveFiles(
        bytes: ByteArray,
        filename: String,
        markdown: String,
        settings: AppSettings
    ): String = withContext(Dispatchers.IO) {
        val context: Context = getApplication()
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.outputFolderUri))
            ?: error("Cannot access output folder")

        val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val baseName = "$dateStr-$filename"

        val imageFile = folder.createFile("image/jpeg", "$baseName.jpg")
            ?: error("Cannot create image file")
        context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(bytes) }

        val mdContent = "$markdown\n\n![$baseName]($baseName.jpg)\n"
        val mdFile = folder.createFile("text/markdown", "$baseName.md")
            ?: error("Cannot create markdown file")
        context.contentResolver.openOutputStream(mdFile.uri)?.use { it.write(mdContent.toByteArray()) }

        baseName
    }

    fun retry() {
        val bytes = lastImageBytes ?: return
        onImageCaptured(bytes)
    }

    fun retake() { screen = Screen.Camera }

    fun saveSettings(new: AppSettings) {
        viewModelScope.launch { settingsRepo.save(new) }
    }
}
