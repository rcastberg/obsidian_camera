package org.castberg.obsidiancapture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER_NAME = stringPreferencesKey("provider_name")
        val LLM_URL = stringPreferencesKey("llm_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val OUTPUT_FOLDER_URI = stringPreferencesKey("output_folder_uri")
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val HISTORY = stringPreferencesKey("history")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            providerName = prefs[Keys.PROVIDER_NAME] ?: "OpenAI",
            llmUrl = prefs[Keys.LLM_URL] ?: "https://api.openai.com/v1",
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL] ?: "gpt-4o",
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: "",
            outputFolderUri = prefs[Keys.OUTPUT_FOLDER_URI] ?: "",
            imageQuality = prefs[Keys.IMAGE_QUALITY] ?: 85
        )
    }

    val history: Flow<List<CaptureRecord>> = context.dataStore.data.map { prefs ->
        runCatching {
            val arr = JSONArray(prefs[Keys.HISTORY] ?: return@runCatching emptyList())
            (0 until arr.length()).map { CaptureRecord.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROVIDER_NAME] = settings.providerName
            prefs[Keys.LLM_URL] = settings.llmUrl
            prefs[Keys.API_KEY] = settings.apiKey
            prefs[Keys.MODEL] = settings.model
            prefs[Keys.SYSTEM_PROMPT] = settings.systemPrompt
            prefs[Keys.OUTPUT_FOLDER_URI] = settings.outputFolderUri
            prefs[Keys.IMAGE_QUALITY] = settings.imageQuality
        }
    }

    suspend fun addRecord(record: CaptureRecord) {
        context.dataStore.edit { prefs ->
            val existing = runCatching {
                val arr = JSONArray(prefs[Keys.HISTORY] ?: "[]")
                (0 until arr.length()).map { CaptureRecord.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())

            val updated = (listOf(record) + existing).take(20)
            val arr = JSONArray()
            updated.forEach { arr.put(JSONObject(it.toJson())) }
            prefs[Keys.HISTORY] = arr.toString()
        }
    }
}
