package com.voicestudio.sync.appfunctions

import androidx.appfunctions.AppFunctionAccessLevel
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.VoiceStudioRuntimeRegistry
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verified local VoiceStudio OTA bundle readiness snapshot.
 *
 * @property status Current bundle readiness status (`READY`, `MISSING`, `DOWNLOADING`, or `ERROR`).
 * @property bundleVersion Verified bundle version tag on disk, or empty when not downloaded.
 * @property activeLoraVersion Active personal voice LoRA integer version (`0` when absent).
 * @property isReadyForOfflineSynthesis True only when all 10 SHA-256-verified synthesis artifacts exist on disk.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class OfflineVoiceBundleSnapshot(
    val status: String,
    val bundleVersion: String,
    val activeLoraVersion: Int,
    val isReadyForOfflineSynthesis: Boolean
)

/**
 * Enqueued local on-device VoiceStudio synthesis job receipt.
 *
 * @property projectId Unique script project identifier queued for local synthesis.
 * @property workRequestId Deterministic WorkManager request UUID string.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EnqueuedSynthesisReceipt(
    val projectId: String,
    val workRequestId: String
)

/**
 * Android 17 (`CINNAMON_BUN`) `:sync-trainer:android-worker` module AppFunction service entry point
 * exposing OTA voice-bundle inspection and local expedited `MEDIA_PROCESSING` synthesis scheduling.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "VoiceStudioSyncAppFunctionService",
    appFunctionXmlFileName = "voice_studio_sync_app_function_service"
)
abstract class VoiceStudioSyncAppFunctionEntryPoint : AppFunctionService() {

    /**
     * Returns the verified on-disk readiness status of the local VoiceStudio OTA model bundle.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun inspectOfflineVoiceBundle(): OfflineVoiceBundleSnapshot = withContext(Dispatchers.IO) {
        val manager = VoiceStudioRuntimeRegistry.otaBundleSyncManager
            ?: OtaBundleSyncManager(applicationContext)
        val state = manager.bundleState.value
        val verified = manager.resolveVerifiedBundleForSynthesis().isSuccess
        OfflineVoiceBundleSnapshot(
            status = state.status.name,
            bundleVersion = state.activeManifest?.bundleVersion.orEmpty(),
            activeLoraVersion = state.activeLoraVersion,
            isReadyForOfflineSynthesis = verified && state.status == OtaBundleStatus.READY
        )
    }

    /**
     * Enqueues local 44.1 kHz personal-voice synthesis for a saved script project when the OTA bundle is verified.
     *
     * @param projectId Identifier of the saved script project to synthesize locally.
     */
    @OptIn(ExperimentalAppFunctionsApi::class)
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(
        level = AppFunctionMetadata.ACCESS_LEVEL_SELF,
        isCompatEnforcementEnabled = true
    )
    suspend fun triggerLocalVoiceSynthesis(
        projectId: String
    ): EnqueuedSynthesisReceipt = withContext(Dispatchers.IO) {
        val cleanId = projectId.trim()
        if (cleanId.isEmpty()) {
            throw AppFunctionInvalidArgumentException("projectId must not be blank.")
        }
        val manager = VoiceStudioRuntimeRegistry.otaBundleSyncManager
            ?: OtaBundleSyncManager(applicationContext)
        if (manager.resolveVerifiedBundleForSynthesis().isFailure) {
            throw AppFunctionDisabledException(
                "Local VoiceStudio OTA bundle is not synced yet; sync on Wi-Fi or USB first."
            )
        }
        val coordinator = VoiceStudioSyncCoordinator(applicationContext, manager)
        val uuid = coordinator.enqueueProjectSynthesis(cleanId)
        EnqueuedSynthesisReceipt(
            projectId = cleanId,
            workRequestId = uuid.toString()
        )
    }
}
