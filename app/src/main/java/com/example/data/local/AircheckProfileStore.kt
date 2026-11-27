package com.example.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.example.data.BrandChannel
import com.example.data.DEFAULT_REMOTE_LLM_PROVIDER_ID
import com.example.data.LlmProviderChoice
import com.example.data.LlmRoutingSettings
import com.example.data.LlmStage
import com.example.data.LlmStageConfig
import com.example.data.PersonaProfile
import com.example.data.SUPPORTED_REMOTE_LLM_PROVIDERS
import com.example.data.Stage4Mode
import com.example.data.StageProviderSettings
import com.example.data.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

const val DEFAULT_MAC_RIG_LAN_URL: String = "http://10.0.0.242:8443"
const val DEFAULT_MAC_RIG_USB_URL: String = "http://127.0.0.1:8443"

/**
 * Binary Protocol Buffer schema persisted via Jetpack Proto DataStore (aircheck_profile.pb).
 * Preloaded asynchronously off the main thread in Application.onCreate() — zero SharedPreferences.commit().
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AircheckProfileProto(
    @ProtoNumber(1) val voiceId: String = "",
    @ProtoNumber(2) val headModelId: String = "",
    @ProtoNumber(3) val defaultPhotoSha: String = "",
    @ProtoNumber(4) val brandChannel: String = BrandChannel.VOLT_LIME.name,
    @ProtoNumber(5) val activeScriptId: String = "",
    @ProtoNumber(6) val voiceSource: String = VoiceSource.MIC_VOICEOVER.name,
    @ProtoNumber(7) val stage4Mode: String = Stage4Mode.PHOTO_HEAD_LORA.name,
    @ProtoNumber(8) val voiceProviderId: String = "voicestudio_s2pro_local",
    @ProtoNumber(9) val alignProviderId: String = "sherpa_onnx_local",
    @ProtoNumber(10) val headProviderId: String = "mac_rig_wan30",
    @ProtoNumber(11) val packageProviderId: String = "mac_rig_ffmpeg",
    @ProtoNumber(12) val lanGatewayUrl: String = DEFAULT_MAC_RIG_LAN_URL,
    @ProtoNumber(13) val useLanVoice: Boolean = true,
    @ProtoNumber(14) val useLanAlign: Boolean = true,
    @ProtoNumber(15) val useLanHead: Boolean = true,
    @ProtoNumber(16) val useLanPackage: Boolean = true,
    @ProtoNumber(17) val trainingJobId: String = "",
    @ProtoNumber(18) val trainingStatus: String = "",
    @ProtoNumber(19) val trainingError: String = "",
    // @ProtoNumber(20) reserved (legacy push token removed)
    @ProtoNumber(21) val tailscaleRigUrl: String = DEFAULT_MAC_RIG_LAN_URL,
    @ProtoNumber(22) val remotionUsedCount: Int = 0,
    @ProtoNumber(23) val remotionInstallRequested: Boolean = false,
    @ProtoNumber(24) val remotionInstallNote: String = "",
    @ProtoNumber(25) val phoneOnlyVoiceMode: Boolean = true,
    @ProtoNumber(26) val draftingLlmProvider: String = LlmProviderChoice.MAC_RIG_DEFAULT.id,
    @ProtoNumber(27) val draftingLlmRemoteProvider: String = DEFAULT_REMOTE_LLM_PROVIDER_ID,
    @ProtoNumber(28) val draftingLlmModel: String = "",
    @ProtoNumber(29) val scriptEditLlmProvider: String = LlmProviderChoice.MAC_RIG_DEFAULT.id,
    @ProtoNumber(30) val scriptEditLlmRemoteProvider: String = DEFAULT_REMOTE_LLM_PROVIDER_ID,
    @ProtoNumber(31) val scriptEditLlmModel: String = "",
    @ProtoNumber(32) val newsroomOverlayLlmProvider: String = LlmProviderChoice.MAC_RIG_DEFAULT.id,
    @ProtoNumber(33) val newsroomOverlayLlmRemoteProvider: String = DEFAULT_REMOTE_LLM_PROVIDER_ID,
    @ProtoNumber(34) val newsroomOverlayLlmModel: String = "",
    @ProtoNumber(35) val preferOnPhoneVoice: Boolean = false,
    @ProtoNumber(36) val rigVoiceBackend: String = "auto",
    @ProtoNumber(37) val heygenLookId: String = "",
    @ProtoNumber(38) val heygenEngine: String = "heygen-avatar-6",
    @ProtoNumber(39) val heygenResolution: String = "1080p",
    @ProtoNumber(40) val heygenMotionPrompt: String = "",
    @ProtoNumber(41) val rigVideoBackend: String = "heygen"
)

data class StudioSettings(
    val brandChannel: BrandChannel = BrandChannel.VOLT_LIME,
    val voiceSource: VoiceSource = VoiceSource.MIC_VOICEOVER,
    val stage4Mode: Stage4Mode = Stage4Mode.PHOTO_HEAD_LORA,
    val activeScriptId: String = "",
    val persona: PersonaProfile = PersonaProfile(),
    val providers: StageProviderSettings = StageProviderSettings(),
    val tailscaleRigUrl: String = DEFAULT_MAC_RIG_LAN_URL,
    val remotionUsedCount: Int = 0,
    val remotionInstallRequested: Boolean = false,
    val remotionInstallNote: String = "",
    val phoneOnlyVoiceMode: Boolean = true,
    val preferOnPhoneVoice: Boolean = false,
    val rigVoiceBackend: String = "auto",
    val rigVideoBackend: String = "heygen",
    val heygenLookId: String = "",
    val heygenEngine: String = "heygen-avatar-6",
    val heygenResolution: String = "1080p",
    val heygenMotionPrompt: String = "",
    val llmRouting: LlmRoutingSettings = LlmRoutingSettings()
)

@OptIn(ExperimentalSerializationApi::class)
object AircheckProfileSerializer : Serializer<AircheckProfileProto> {
    override val defaultValue: AircheckProfileProto = AircheckProfileProto()

    override suspend fun readFrom(input: InputStream): AircheckProfileProto {
        return try {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) defaultValue else ProtoBuf.decodeFromByteArray(bytes)
        } catch (e: Exception) {
            throw CorruptionException("Unable to decode aircheck_profile.pb", e)
        }
    }

    override suspend fun writeTo(t: AircheckProfileProto, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(t))
    }
}

class AircheckProfileStore(
    private val dataStore: DataStore<AircheckProfileProto>
) {
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) : this(
        DataStoreFactory.create(
            serializer = AircheckProfileSerializer,
            scope = scope,
            produceFile = { context.applicationContext.dataStoreFile("aircheck_profile.pb") }
        )
    )

    val settingsFlow: Flow<StudioSettings> = dataStore.data
        .catch { err ->
            if (err is IOException) emit(AircheckProfileProto()) else throw err
        }
        .map { proto -> proto.toDomain() }

    suspend fun preload() {
        runCatching { dataStore.data.first() }
    }

    suspend fun current(): StudioSettings = settingsFlow.first()

    suspend fun setBrandChannel(channel: BrandChannel) {
        dataStore.updateData { it.copy(brandChannel = channel.name) }
    }

    suspend fun setActiveScriptId(scriptId: String) {
        dataStore.updateData { it.copy(activeScriptId = scriptId) }
    }

    suspend fun setTailscaleRigUrl(url: String) {
        dataStore.updateData { it.copy(tailscaleRigUrl = url.trim()) }
    }

    suspend fun setPhoneOnlyVoiceMode(enabled: Boolean) {
        dataStore.updateData {
            it.copy(
                phoneOnlyVoiceMode = enabled,
                useLanVoice = !enabled
            )
        }
    }

    suspend fun setPreferOnPhoneVoice(enabled: Boolean) {
        dataStore.updateData { it.copy(preferOnPhoneVoice = enabled) }
    }

    suspend fun setRigVoiceBackend(backend: String) {
        val clean = backend.trim().lowercase().ifBlank { "auto" }
        dataStore.updateData { it.copy(rigVoiceBackend = clean) }
    }

    suspend fun setRigVideoBackend(backend: String) {
        val clean = backend.trim().lowercase().ifBlank { "heygen" }
        dataStore.updateData { it.copy(rigVideoBackend = clean) }
    }

    suspend fun setHeygenLookId(lookId: String) {
        dataStore.updateData { it.copy(heygenLookId = lookId.trim()) }
    }

    suspend fun setHeygenEngine(engine: String) {
        val clean = engine.trim().lowercase().ifBlank { "heygen-avatar-6" }
        dataStore.updateData { cur ->
            val adjustedRes = when {
                clean.startsWith("heygen-avatar-6") && cur.heygenResolution == "720p" -> "1080p"
                !clean.startsWith("heygen-avatar-6") && cur.heygenResolution == "2k" -> "1080p"
                else -> cur.heygenResolution.ifBlank { "1080p" }
            }
            cur.copy(heygenEngine = clean, heygenResolution = adjustedRes)
        }
    }

    suspend fun setHeygenResolution(resolution: String) {
        val clean = resolution.trim().lowercase().ifBlank { "1080p" }
        dataStore.updateData { it.copy(heygenResolution = clean) }
    }

    suspend fun setHeygenMotionPrompt(prompt: String) {
        dataStore.updateData { it.copy(heygenMotionPrompt = prompt.trim().take(400)) }
    }

    suspend fun recordRemotionInstallRequest(
        userNote: String,
        installedOnRig: Boolean
    ) {
        val cleanNote = userNote.trim()
        dataStore.updateData { current ->
            current.copy(
                remotionInstallRequested = true,
                remotionInstallNote = cleanNote,
                packageProviderId = if (installedOnRig) "mac_rig_remotion" else current.packageProviderId,
                remotionUsedCount = if (installedOnRig) {
                    (current.remotionUsedCount + 1).coerceAtLeast(1)
                } else {
                    current.remotionUsedCount
                }
            )
        }
    }

    suspend fun incrementRemotionUsedCount() {
        dataStore.updateData { current ->
            current.copy(
                remotionUsedCount = current.remotionUsedCount + 1,
                packageProviderId = "mac_rig_remotion"
            )
        }
    }

    suspend fun setDefaultPhotoSha(photoSha: String) {
        val clean = photoSha.trim()
        dataStore.updateData { it.copy(defaultPhotoSha = clean) }
    }

    suspend fun setLlmStageConfig(
        stage: LlmStage,
        provider: LlmProviderChoice,
        modelOverride: String = "",
        remoteProviderId: String? = null
    ) {
        val cleanModel = modelOverride.trim()
        dataStore.updateData { current ->
            val resolvedRemote = normalizeRemoteProviderId(
                candidate = remoteProviderId ?: when (stage) {
                    LlmStage.DRAFTING -> current.draftingLlmRemoteProvider
                    LlmStage.SCRIPT_EDIT -> current.scriptEditLlmRemoteProvider
                    LlmStage.NEWSROOM_OVERLAY -> current.newsroomOverlayLlmRemoteProvider
                }
            )
            when (stage) {
                LlmStage.DRAFTING -> current.copy(
                    draftingLlmProvider = provider.id,
                    draftingLlmRemoteProvider = resolvedRemote,
                    draftingLlmModel = cleanModel
                )
                LlmStage.SCRIPT_EDIT -> current.copy(
                    scriptEditLlmProvider = provider.id,
                    scriptEditLlmRemoteProvider = resolvedRemote,
                    scriptEditLlmModel = cleanModel
                )
                LlmStage.NEWSROOM_OVERLAY -> current.copy(
                    newsroomOverlayLlmProvider = provider.id,
                    newsroomOverlayLlmRemoteProvider = resolvedRemote,
                    newsroomOverlayLlmModel = cleanModel
                )
            }
        }
    }

    suspend fun setLlmStageConfig(
        stage: LlmStage,
        rawProviderId: String,
        modelOverride: String = ""
    ) {
        val trimmed = rawProviderId.trim().lowercase()
        val choice = LlmProviderChoice.fromRaw(trimmed)
        val remoteOverride = trimmed.takeIf { it in SUPPORTED_REMOTE_LLM_PROVIDERS }
        setLlmStageConfig(
            stage = stage,
            provider = choice,
            modelOverride = modelOverride,
            remoteProviderId = remoteOverride
        )
    }

    private fun normalizeRemoteProviderId(candidate: String?): String {
        val clean = candidate?.trim()?.lowercase().orEmpty()
        return if (clean in SUPPORTED_REMOTE_LLM_PROVIDERS) clean else DEFAULT_REMOTE_LLM_PROVIDER_ID
    }

    private fun AircheckProfileProto.toDomain(): StudioSettings = StudioSettings(
        brandChannel = BrandChannel.fromRaw(brandChannel),
        voiceSource = VoiceSource.fromRaw(voiceSource),
        stage4Mode = Stage4Mode.fromRaw(stage4Mode),
        activeScriptId = activeScriptId,
        persona = PersonaProfile(
            headModelId = headModelId,
            voiceId = voiceId,
            defaultPhotoSha = defaultPhotoSha,
            trainingJobId = trainingJobId,
            trainingStatus = trainingStatus,
            trainingError = trainingError
        ),
        providers = StageProviderSettings(
            voiceProviderId = voiceProviderId.ifBlank { "voicestudio_s2pro_local" },
            alignProviderId = alignProviderId.ifBlank { "sherpa_onnx_local" },
            headProviderId = headProviderId.ifBlank { "mac_rig_wan30" },
            packageProviderId = packageProviderId.ifBlank { "mac_rig_ffmpeg" },
            lanGatewayUrl = lanGatewayUrl.ifBlank { DEFAULT_MAC_RIG_LAN_URL },
            useLanVoice = useLanVoice,
            useLanAlign = useLanAlign,
            useLanHead = useLanHead,
            useLanPackage = useLanPackage
        ),
        tailscaleRigUrl = tailscaleRigUrl.ifBlank { DEFAULT_MAC_RIG_LAN_URL },
        remotionUsedCount = remotionUsedCount.coerceAtLeast(0),
        remotionInstallRequested = remotionInstallRequested,
        remotionInstallNote = remotionInstallNote,
        phoneOnlyVoiceMode = phoneOnlyVoiceMode,
        preferOnPhoneVoice = preferOnPhoneVoice,
        rigVoiceBackend = rigVoiceBackend.ifBlank { "auto" },
        rigVideoBackend = rigVideoBackend.ifBlank { "heygen" },
        heygenLookId = heygenLookId.trim(),
        heygenEngine = heygenEngine.ifBlank { "heygen-avatar-6" },
        heygenResolution = heygenResolution.ifBlank { "1080p" },
        heygenMotionPrompt = heygenMotionPrompt.trim(),
        llmRouting = LlmRoutingSettings(
            drafting = LlmStageConfig(
                provider = LlmProviderChoice.fromRaw(draftingLlmProvider),
                remoteProviderId = normalizeRemoteProviderId(draftingLlmRemoteProvider),
                modelOverride = draftingLlmModel.trim()
            ),
            scriptEdit = LlmStageConfig(
                provider = LlmProviderChoice.fromRaw(scriptEditLlmProvider),
                remoteProviderId = normalizeRemoteProviderId(scriptEditLlmRemoteProvider),
                modelOverride = scriptEditLlmModel.trim()
            ),
            newsroomOverlay = LlmStageConfig(
                provider = LlmProviderChoice.fromRaw(newsroomOverlayLlmProvider),
                remoteProviderId = normalizeRemoteProviderId(newsroomOverlayLlmRemoteProvider),
                modelOverride = newsroomOverlayLlmModel.trim()
            )
        )
    )
}
