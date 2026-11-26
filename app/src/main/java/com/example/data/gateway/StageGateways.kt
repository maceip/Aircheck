package com.example.data.gateway

import com.example.data.AlignedWord
import com.example.data.BreakingNewsStyle
import com.example.data.CoreRenderOptions
import com.example.data.NewsroomLook
import com.example.data.RigQualityMetrics
import com.example.data.SilenceGap
import com.example.data.StudioCutStats

import com.example.data.CoreStoryClause
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptProject
import com.example.data.local.CasStoredAsset

class RigJobNotFoundException(message: String) : IllegalStateException(message)

data class EnqueuedRenderResult(
    val jobId: String,
    val clipId: String,
    val inputHash: String
)

data class MacRigHealthResult(
    val resolvedUrl: String,
    val status: String,
    val loraVersion: Int,
    val bundleVersion: String?,
    val pendingPreferences: Int,
    val renderBridgeAvailable: Boolean,
    val activeRenderJobs: Int,
    val remotionWorkerPresent: Boolean = false,
    val remotionEnabled: Boolean = false,
    val remotionRendersCompleted: Int = 0,
    val remotionVersion: String? = null
)

data class RemotionInstallResult(
    val installed: Boolean,
    val remotionVersion: String?,
    val packageProviderId: String,
    val userRequest: String,
    val enableBrollPopups: Boolean
)

data class CoreTtsSynthesisResult(
    val ttsId: String,
    val audioUrl: String,
    val durationMs: Long,
    val waveformPeaks: List<Float>,
    val timedClauses: List<CoreStoryClause>,
    val words: List<AlignedWord> = emptyList(),
    val alignUrl: String? = null,
    val metrics: RigQualityMetrics? = null
)

data class ChoppedBrollResult(
    val jobId: String,
    val videoUrl: String,
    val durationMs: Long,
    val brollCutCount: Int,
    val appliedOverlayCount: Int,
    val studioStats: StudioCutStats? = null
)

/**
 * Durable rig task handle: [resumeTaskId] continues polling an existing rig task (after process
 * death); [onSubmitted] persists a newly created task id before polling starts.
 */
class RigTaskHandle(
    val resumeTaskId: String?,
    val onSubmitted: suspend (String) -> Unit,
    val onProgress: (String) -> Unit = {},
    val onProgressFraction: (Float, String) -> Unit = { _, _ -> }
)

data class SilenceGapsResult(
    val durationMs: Long,
    val gaps: List<SilenceGap>,
    val removableMs: Long
)

data class BackdropPlateResult(
    val plateId: String,
    val url: String,
    val prompt: String
)

data class ShotPreviewAssetResult(
    val previewId: String,
    val previewUrl: String,
    val localFilePath: String,
    val shotType: String
)

data class GeneratedOverlayBriefResult(
    val kind: OverlayObjectKind,
    val headline: String,
    val subline: String,
    val calloutStat: String,
    val durationSec: Int,
    val breakingStyle: BreakingNewsStyle = BreakingNewsStyle.BREAKING
)

data class VideoLookTweakResult(
    val accentHex: String? = null,
    val light: String? = null,
    val blurPercent: Int? = null,
    val brightnessEv: Double? = null,
    val breakingStyle: BreakingNewsStyle? = null,
    val showBrandBug: Boolean? = null,
    val cornerBugText: String? = null,
    val brollFraming: String? = null,
    val chyronHeadline: String? = null,
    val backdropPrompt: String? = null,
    val summary: String = ""
)

data class MacRigJobSnapshot(
    val jobId: String,
    val status: RenderJobStatus,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val videoUrl: String?,
    val durationMs: Long,
    val cer: Double? = null,
    val wespeakerCosine: Double? = null,
    val dnsmosP808: Double? = null,
    val errorKind: String? = null,
    val words: List<AlignedWord> = emptyList(),
    val alignUrl: String? = null,
    val metrics: RigQualityMetrics? = null
)

data class RemoteRenderSummary(
    val id: String,
    val filename: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val updatedAtUtc: String,
    val videoUrl: String,
    val thumbUrl: String
)

interface MacRigRenderGateway {
    suspend fun resolveAndCheckHealth(configuredUrl: String): MacRigHealthResult

    suspend fun listRemoteRenders(rigBaseUrl: String): List<RemoteRenderSummary>

    suspend fun enqueueMacRigRender(
        rigBaseUrl: String,
        project: ScriptProject,
        photoSha: String?,
        resolution: String = "1080P",
        ratio: String = "9:16",
        maxDurationSec: Int = 10,
        words: List<AlignedWord> = emptyList(),
        alignUrl: String? = null,
        useRemotion: Boolean? = null,
        brollPlan: List<CoreStoryClause> = emptyList(),
        brollAspect: String = "9:16"
    ): EnqueuedRenderResult

    suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot

    suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset

    suspend fun downloadAudioWavToCas(audioUrl: String): CasStoredAsset

    suspend fun fetchAlignedWords(alignUrl: String): List<AlignedWord> = emptyList()

    /** `GET /v1/logs`: tail of the rig's durable, auto-rotating generation log (rig + shipped phone events). */
    suspend fun fetchRigLogs(rigBaseUrl: String, lines: Int = 200): List<String> = emptyList()

    suspend fun synthesizeCoreStoryTts(
        rigBaseUrl: String,
        projectId: String,
        clauses: List<CoreStoryClause>,
        task: RigTaskHandle? = null
    ): CoreTtsSynthesisResult

    suspend fun chopTalkingHeadIntoBroll(
        rigBaseUrl: String,
        scriptId: String,
        title: String,
        sourceJobId: String?,
        sourceMp4Sha: String?,
        clauses: List<CoreStoryClause>,
        overlays: List<SavedNewsroomOverlay> = emptyList(),
        renderOptions: CoreRenderOptions? = null,
        look: NewsroomLook? = null,
        task: RigTaskHandle? = null,
        alignedWords: List<AlignedWord> = emptyList(),
        alignUrl: String? = null,
        useRemotion: Boolean? = null
    ): ChoppedBrollResult

    /** `POST /v1/core/silence-gaps`: internal pauses in a rig talking-head render. */
    suspend fun detectSilenceGaps(rigBaseUrl: String, sourceJobId: String): SilenceGapsResult

    /** `POST /v1/newsroom/backdrop`: generates a studio backdrop plate image on the rig. */
    suspend fun generateBackdropPlate(
        rigBaseUrl: String,
        prompt: String,
        aspect: String,
        task: RigTaskHandle? = null
    ): BackdropPlateResult

    suspend fun generateAiOverlayBrief(
        rigBaseUrl: String,
        briefPrompt: String
    ): GeneratedOverlayBriefResult

    suspend fun generateAiOverlaySet(
        rigBaseUrl: String,
        briefPrompt: String,
        breakingStyle: BreakingNewsStyle = BreakingNewsStyle.BREAKING
    ): List<GeneratedOverlayBriefResult> = listOf(
        generateAiOverlayBrief(rigBaseUrl, briefPrompt).copy(breakingStyle = breakingStyle)
    )

    suspend fun requestRemotionInstall(
        rigBaseUrl: String,
        userRequest: String,
        enableBrollPopups: Boolean = true
    ): RemotionInstallResult

    /** `POST /v1/ai-assist` (`mode = "polish_headline"`): rewrites/polishes the on-screen title. */
    suspend fun polishHeadline(
        rigBaseUrl: String,
        currentHeadline: String,
        storyContext: String = ""
    ): String = throw UnsupportedOperationException("polishHeadline not implemented")

    /** `POST /v1/ai-assist` (`mode = "tweak_video_look"`): applies natural-language look/style/logo/framing tweaks via rig LLM. */
    suspend fun tweakVideoLook(
        rigBaseUrl: String,
        instruction: String,
        currentLook: NewsroomLook,
        currentOptions: CoreRenderOptions,
        storyContext: String = ""
    ): VideoLookTweakResult = throw UnsupportedOperationException("tweakVideoLook not implemented")

    /** `POST /v1/studio/shot-preview` + download JPEG into CAS for immediate SPARK shot thumbnails. */
    suspend fun generateAndCacheShotPreview(
        rigBaseUrl: String,
        shotType: String,
        prompt: String,
        headline: String = "",
        photoSha: String? = null
    ): ShotPreviewAssetResult = throw UnsupportedOperationException("generateAndCacheShotPreview not implemented")
}
