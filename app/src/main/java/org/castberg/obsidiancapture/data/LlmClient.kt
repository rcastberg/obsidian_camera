package org.castberg.obsidiancapture.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    const val DEFAULT_ANALYSIS_PROMPT =
        "You are creating a note for an Obsidian personal knowledge base. " +
        "Analyze the image and write a well-structured markdown note following Obsidian conventions:\n" +
        "- Start with a single # heading that names the main subject\n" +
        "- Use ## subheadings to organize sections\n" +
        "- Use [[wikilinks]] for any concepts, people, places, or topics that would make sense as linked notes\n" +
        "- Add a #tags section at the bottom with relevant tags (e.g. #photo #receipt #whiteboard)\n" +
        "- Describe what you see in detail — objects, people, setting, colors, composition\n" +
        "- If there is any written text visible in the image, include a ## Text section that transcribes or summarizes it\n" +
        "Return only the markdown content — no JSON, no preamble, no code fences."

    private const val ANALYSIS_PROMPT = DEFAULT_ANALYSIS_PROMPT

    private const val FILENAME_PROMPT =
        "Generate a short filename for the following note. " +
        "Rules: lowercase kebab-case, max 50 characters, only letters/numbers/hyphens, no extension. " +
        "Respond with ONLY the filename — no explanation, no punctuation, nothing else."

    suspend fun analyzeImage(settings: AppSettings, imageBytes: ByteArray): String {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val systemPrompt = settings.systemPrompt.ifBlank { ANALYSIS_PROMPT }

        val body = JSONObject().apply {
            put("model", settings.model.ifBlank { "gpt-4o" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Analyze this image.")
                        })
                    })
                })
            })
            put("max_tokens", 2000)
        }.toString()

        return callApi(settings, body)
    }

    suspend fun generateFilename(settings: AppSettings, markdown: String): String {
        val body = JSONObject().apply {
            put("model", settings.model.ifBlank { "gpt-4o" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", FILENAME_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", markdown.take(1500))
                })
            })
            put("max_tokens", 30)
        }.toString()

        return sanitizeFilename(callApi(settings, body))
    }

    private suspend fun callApi(settings: AppSettings, requestBody: String): String = withContext(Dispatchers.IO) {
        val baseUrl = settings.llmUrl.trimEnd('/')
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .apply {
                if (url.contains("openrouter.ai")) {
                    addHeader("HTTP-Referer", "https://github.com/castberg/obsidian-capture")
                    addHeader("X-Title", "Obsidian Capture")
                }
            }
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from API")

        if (!response.isSuccessful) throw Exception("API error ${response.code}: $body")

        JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun sanitizeFilename(name: String): String =
        name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trimStart('-').trimEnd('-')
            .take(50)
            .ifBlank { "captured-image" }
}
