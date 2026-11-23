package com.example.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.example.data.BrandChannel
import com.example.data.PersonaProfile
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
    @ProtoNumber(20) val fcmToken: String = "",
    @ProtoNumber(21) val tailscaleRigUrl: String = DEFAULT_MAC_RIG_LAN_URL
)

data class StudioSettings(
    val brandChannel: BrandChannel = BrandChannel.VOLT_LIME,
    val voiceSource: VoiceSource = VoiceSource.MIC_VOICEOVER,
    val stage4Mode: Stage4Mode = Stage4Mode.PHOTO_HEAD_LORA,
    val activeScriptId: String = "",
    val persona: PersonaProfile = PersonaProfile(),
    val providers: StageProviderSettings = StageProviderSettings(),
    val fcmToken: String = "",
    val tailscaleRigUrl: String = DEFAULT_MAC_RIG_LAN_URL
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

    suspend fun setVoiceSource(source: VoiceSource) {
        dataStore.updateData { it.copy(voiceSource = source.name) }
    }

    suspend fun setStage4Mode(mode: Stage4Mode) {
        dataStore.updateData { it.copy(stage4Mode = mode.name) }
    }

    suspend fun setActiveScriptId(scriptId: String) {
        dataStore.updateData { it.copy(activeScriptId = scriptId) }
    }

    suspend fun updatePersona(
        headModelId: String? = null,
        voiceId: String? = null,
        defaultPhotoSha: String? = null,
        trainingJobId: String? = null,
        trainingStatus: String? = null,
        trainingError: String? = null
    ) {
        dataStore.updateData { cur ->
            cur.copy(
                headModelId = headModelId?.trim() ?: cur.headModelId,
                voiceId = voiceId?.trim() ?: cur.voiceId,
                defaultPhotoSha = defaultPhotoSha?.trim() ?: cur.defaultPhotoSha,
                trainingJobId = trainingJobId?.trim() ?: cur.trainingJobId,
                trainingStatus = trainingStatus?.trim() ?: cur.trainingStatus,
                trainingError = trainingError?.trim() ?: cur.trainingError
            )
        }
    }

    suspend fun setTailscaleRigUrl(url: String) {
        dataStore.updateData { it.copy(tailscaleRigUrl = url.trim()) }
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
        fcmToken = fcmToken,
        tailscaleRigUrl = tailscaleRigUrl.ifBlank { DEFAULT_MAC_RIG_LAN_URL }
    )
}
