package org.castberg.obsidiancapture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    var selectedProviderName by remember(settings.providerName) { mutableStateOf(settings.providerName) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var customUrl by remember(settings.llmUrl) { mutableStateOf(settings.llmUrl) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var outputFolderUri by remember(settings.outputFolderUri) { mutableStateOf(settings.outputFolderUri) }
    var imageQuality by remember(settings.imageQuality) { mutableStateOf(settings.imageQuality.toFloat()) }
    var showApiKey by remember { mutableStateOf(false) }

    val currentProvider = providerByName(selectedProviderName)
    val isCustom = currentProvider.name == "Custom"
    val effectiveUrl = if (isCustom) customUrl else currentProvider.baseUrl

    fun onProviderSelected(name: String) {
        selectedProviderName = name
        val provider = providerByName(name)
        if (provider.name != "Custom") {
            if (provider.models.none { it.id == model }) {
                model = provider.models.firstOrNull()?.id ?: model
            }
        }
    }

    fun buildSettings() = AppSettings(
        providerName = selectedProviderName,
        llmUrl = effectiveUrl,
        apiKey = apiKey,
        model = model,
        systemPrompt = systemPrompt,
        outputFolderUri = outputFolderUri,
        imageQuality = imageQuality.toInt()
    )

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

            // URL: editable only for Custom, otherwise shown as info
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

            // ── Model ─────────────────────────────────────────────────────
            HorizontalDivider()
            Text("Model", style = MaterialTheme.typography.titleMedium)

            if (currentProvider.models.isNotEmpty()) {
                ModelDropdown(
                    selectedModel = model,
                    models = currentProvider.models,
                    onSelect = { model = it }
                )
            } else {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. llama3.2-vision") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ── System Prompt ─────────────────────────────────────────────
            HorizontalDivider()
            Text("System Prompt", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt (blank = default)") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                maxLines = 6
            )
            TextButton(
                onClick = { systemPrompt = LlmClient.DEFAULT_ANALYSIS_PROMPT },
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

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selectedModel: String,
    models: List<ModelOption>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = models.find { it.id == selectedModel }?.label ?: selectedModel

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
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
