package org.castberg.obsidiancapture.data

data class AppSettings(
    val providerName: String = "OpenAI",
    val llmUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val defaultHighEffort: Boolean = true,
    val highEffortModel: String = "gpt-4o",
    val mediumEffortModel: String = "gpt-4o-mini",
    val lowEffortModel: String = "gpt-4o-mini",
    val systemPrompt: String = "",
    val mediumSystemPrompt: String = "",
    val outputFolderUri: String = "",
    val imageQuality: Int = 85
)
