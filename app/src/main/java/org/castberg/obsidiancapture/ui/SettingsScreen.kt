package org.castberg.obsidiancapture.ui

import android.content.Intent
import android.net.Uri
import org.castberg.obsidiancapture.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.castberg.obsidiancapture.data.AppSettings
import org.castberg.obsidiancapture.data.LlmClient
import org.castberg.obsidiancapture.data.ModelOption
import org.castberg.obsidiancapture.data.PROVIDERS
import org.castberg.obsidiancapture.data.providerByName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedProviderName by remember(settings.providerName) { mutableStateOf(settings.providerName) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var defaultHighEffort by remember(settings.defaultHighEffort) { mutableStateOf(settings.defaultHighEffort) }
    var highEffortModel by remember(settings.highEffortModel) { mutableStateOf(settings.highEffortModel) }
    var mediumEffortModel by remember(settings.mediumEffortModel) { mutableStateOf(settings.mediumEffortModel) }
    var lowEffortModel by remember(settings.lowEffortModel) { mutableStateOf(settings.lowEffortModel) }
    var mediumSystemPrompt by remember(settings.mediumSystemPrompt) { mutableStateOf(settings.mediumSystemPrompt) }
    var customUrl by remember(settings.llmUrl) { mutableStateOf(settings.llmUrl) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var outputFolderUri by remember(settings.outputFolderUri) { mutableStateOf(settings.outputFolderUri) }
    var imageQuality by remember(settings.imageQuality) { mutableStateOf(settings.imageQuality.toFloat()) }
    var showApiKey by remember { mutableStateOf(false) }

    var fetchedModels by remember { mutableStateOf<List<ModelOption>?>(null) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }

    val currentProvider = providerByName(selectedProviderName)
    val isCustom = currentProvider.name == "Custom"
    val effectiveUrl = if (isCustom) customUrl else currentProvider.baseUrl

    val effectiveModels = when {
        isCustom -> emptyList()
        fetchedModels != null -> fetchedModels!!
        else -> currentProvider.models
    }

    suspend fun doFetchModels(url: String, key: String, providerName: String) {
        if (providerName == "Custom") return
        isLoadingModels = true
        modelsError = null
        fetchedModels = null
        runCatching {
            LlmClient.fetchModels(url, key, providerName)
        }.onSuccess { models ->
            fetchedModels = models
        }.onFailure { e ->
            modelsError = e.message ?: "Failed to load models"
        }
        isLoadingModels = false
    }

    LaunchedEffect(selectedProviderName) {
        doFetchModels(effectiveUrl, apiKey, selectedProviderName)
    }

    fun onProviderSelected(name: String) {
        selectedProviderName = name
        val provider = providerByName(name)
        if (provider.name != "Custom") {
            if (provider.models.none { it.id == highEffortModel }) {
                highEffortModel = provider.models.firstOrNull()?.id ?: highEffortModel
            }
            if (provider.models.none { it.id == mediumEffortModel }) {
                mediumEffortModel = provider.models.lastOrNull()?.id ?: mediumEffortModel
            }
            if (provider.models.none { it.id == lowEffortModel }) {
                lowEffortModel = provider.models.lastOrNull()?.id ?: lowEffortModel
            }
        }
    }

    fun buildSettings() = AppSettings(
        providerName = selectedProviderName,
        llmUrl = effectiveUrl,
        apiKey = apiKey,
        defaultHighEffort = defaultHighEffort,
        highEffortModel = highEffortModel,
        mediumEffortModel = mediumEffortModel,
        lowEffortModel = lowEffortModel,
        systemPrompt = systemPrompt,
        mediumSystemPrompt = mediumSystemPrompt,
        outputFolderUri = outputFolderUri,
        imageQuality = imageQuality.toInt()
    )

    BackHandler {
        onSave(buildSettings())
        onNavigateBack()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputFolderUri = it.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        onSave(buildSettings())
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Provider ──────────────────────────────────────────────────
            Text("Provider", style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PROVIDERS.forEachIndexed { index, provider ->
                    SegmentedButton(
                        selected = selectedProviderName == provider.name,
                        onClick = { onProviderSelected(provider.name) },
                        shape = SegmentedButtonDefaults.itemShape(index, PROVIDERS.size),
                        label = { Text(provider.name, maxLines = 1) }
                    )
                }
            }

            if (isCustom) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://localhost:11434/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            } else {
                Text(
                    currentProvider.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── API Key ───────────────────────────────────────────────────
            HorizontalDivider()
            Text("Authentication", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show"
                        )
                    }
                }
            )

            // ── Models ────────────────────────────────────────────────────
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Models", style = MaterialTheme.typography.titleMedium)
                if (isLoadingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (!isCustom) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                doFetchModels(effectiveUrl, apiKey, selectedProviderName)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh models", modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (modelsError != null) {
                Text(
                    "Could not load models: $modelsError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (fetchedModels != null && !isCustom) {
                Text(
                    "${fetchedModels!!.size} models loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Default effort", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (defaultHighEffort) "High — starts on High each session" else "Medium — starts on Med each session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = defaultHighEffort,
                    onCheckedChange = { defaultHighEffort = it }
                )
            }

            Text(
                "High Effort — detailed image analysis (camera toggle)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (effectiveModels.isNotEmpty()) {
                ModelDropdown(
                    selectedModel = highEffortModel,
                    models = effectiveModels,
                    label = "High Effort Model",
                    onSelect = { highEffortModel = it }
                )
            } else {
                OutlinedTextField(
                    value = highEffortModel,
                    onValueChange = { highEffortModel = it },
                    label = { Text("High Effort Model ID") },
                    placeholder = { Text("e.g. gpt-4o") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Text(
                "Medium Effort — quick image analysis (camera toggle)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (effectiveModels.isNotEmpty()) {
                ModelDropdown(
                    selectedModel = mediumEffortModel,
                    models = effectiveModels,
                    label = "Medium Effort Model",
                    onSelect = { mediumEffortModel = it }
                )
            } else {
                OutlinedTextField(
                    value = mediumEffortModel,
                    onValueChange = { mediumEffortModel = it },
                    label = { Text("Medium Effort Model ID") },
                    placeholder = { Text("e.g. gpt-4o-mini") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Text(
                "Low Effort — filename generation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (effectiveModels.isNotEmpty()) {
                ModelDropdown(
                    selectedModel = lowEffortModel,
                    models = effectiveModels,
                    label = "Low Effort Model",
                    onSelect = { lowEffortModel = it }
                )
            } else {
                OutlinedTextField(
                    value = lowEffortModel,
                    onValueChange = { lowEffortModel = it },
                    label = { Text("Low Effort Model ID") },
                    placeholder = { Text("e.g. gpt-4o-mini") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ── System Prompts ────────────────────────────────────────────
            HorizontalDivider()
            Text("System Prompts", style = MaterialTheme.typography.titleMedium)

            Text(
                "High Effort Prompt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("High Effort Prompt (blank = default)") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                maxLines = 6
            )
            TextButton(
                onClick = { systemPrompt = LlmClient.DEFAULT_ANALYSIS_PROMPT },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Reset to default")
            }

            Text(
                "Medium Effort Prompt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = mediumSystemPrompt,
                onValueChange = { mediumSystemPrompt = it },
                label = { Text("Medium Effort Prompt (blank = default)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxLines = 5
            )
            TextButton(
                onClick = { mediumSystemPrompt = LlmClient.DEFAULT_MEDIUM_ANALYSIS_PROMPT },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Reset to default")
            }

            // ── Output ────────────────────────────────────────────────────
            HorizontalDivider()
            Text("Output", style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose Output Folder")
                }
                if (outputFolderUri.isNotBlank()) {
                    val label = runCatching {
                        Uri.parse(outputFolderUri).lastPathSegment?.substringAfterLast(':') ?: outputFolderUri
                    }.getOrDefault(outputFolderUri)
                    Text("Folder: $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("No folder selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("JPEG Quality: ${imageQuality.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = imageQuality, onValueChange = { imageQuality = it }, valueRange = 50f..100f, steps = 9)
            }

            HorizontalDivider()

            Button(
                onClick = { onSave(buildSettings()); onNavigateBack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            val buildDate = remember {
                java.time.Instant.ofEpochMilli(BuildConfig.BUILD_TIME)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
            }
            Text(
                "v${BuildConfig.VERSION_NAME} · $buildDate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selectedModel: String,
    models: List<ModelOption>,
    onSelect: (String) -> Unit,
    label: String = "Model"
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = models.find { it.id == selectedModel }?.label ?: selectedModel

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.id); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
