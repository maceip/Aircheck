package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.BrandChannel
import com.example.data.VoiceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(name = "aircheck_prefs")

data class StudioSettings(
    val brandChannel: BrandChannel = BrandChannel.VOLT_LIME,
    val voiceSource: VoiceSource = VoiceSource.MALE_TTS_DRY_RUN,
    val voiceSelectionId: String = "",
    val voiceCloneEndpoint: String = "",
    val voiceCloneApiKey: String = "",
    val activeProjectId: String = ""
)

class StudioPreferences(private val context: Context) {
    private object Keys {
        val BRAND_CHANNEL = stringPreferencesKey("brand_channel")
        val VOICE_SOURCE = stringPreferencesKey("voice_source")
        val VOICE_SELECTION_ID = stringPreferencesKey("voice_selection_id")
        val VOICE_CLONE_ENDPOINT = stringPreferencesKey("voice_clone_endpoint")
        val VOICE_CLONE_API_KEY = stringPreferencesKey("voice_clone_api_key")
        val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
    }

    val settingsFlow: Flow<StudioSettings> = context.studioDataStore.data.map { prefs ->
        StudioSettings(
            brandChannel = BrandChannel.fromRaw(prefs[Keys.BRAND_CHANNEL]),
            voiceSource = VoiceSource.fromRaw(prefs[Keys.VOICE_SOURCE]),
            voiceSelectionId = prefs[Keys.VOICE_SELECTION_ID].orEmpty(),
            voiceCloneEndpoint = prefs[Keys.VOICE_CLONE_ENDPOINT].orEmpty(),
            voiceCloneApiKey = prefs[Keys.VOICE_CLONE_API_KEY].orEmpty(),
            activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID].orEmpty()
        )
    }

    suspend fun setBrandChannel(channel: BrandChannel) {
        context.studioDataStore.edit { it[Keys.BRAND_CHANNEL] = channel.name }
    }

    suspend fun setVoiceSource(source: VoiceSource) {
        context.studioDataStore.edit { it[Keys.VOICE_SOURCE] = source.name }
    }

    suspend fun setVoiceSelectionId(voiceId: String) {
        context.studioDataStore.edit { it[Keys.VOICE_SELECTION_ID] = voiceId }
    }

    suspend fun setVoiceCloneCredentials(endpoint: String, apiKey: String) {
        context.studioDataStore.edit {
            it[Keys.VOICE_CLONE_ENDPOINT] = endpoint.trim()
            it[Keys.VOICE_CLONE_API_KEY] = apiKey.trim()
        }
    }

    suspend fun setActiveProjectId(projectId: String) {
        context.studioDataStore.edit { it[Keys.ACTIVE_PROJECT_ID] = projectId }
    }
}
