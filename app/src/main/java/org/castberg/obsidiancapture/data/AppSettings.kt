package org.castberg.obsidiancapture.data

data class AppSettings(
    val providerName: String = "OpenAI",
    val llmUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o",
    val systemPrompt: String = "",
    val outputFolderUri: String = "",
    val imageQuality: Int = 85
)
