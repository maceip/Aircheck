package com.example.data.gateway

import android.graphics.Bitmap
import com.example.data.ClipRecord
import com.example.data.PersonaProfile
import com.example.data.PoseGateResult
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.StageProviderSettings
import com.example.data.remote.FactCheckResult
import com.example.data.remote.HonedScriptResult
import kotlinx.coroutines.flow.Flow

/**
 * Clean StageProvider contracts isolating UI/Room/Nav3 from backend transport.
 * Cloud-Default on Day 1; LAN/local adapters plug in via the exact same SHA-256 contract
 * with ZERO silent auto-fallback when an explicitly chosen provider fails.
 */
interface IngestGateway {
    suspend fun ingestUrlToScript(
        sourceUrl: String,
        targetSeconds: Int,
        providers: StageProviderSettings
    ): HonedScriptResult
}

interface ScriptGateway {
    suspend fun honeSparkToScript(
        spark: String,
        targetSeconds: Int,
        onStreamChunk: (String) -> Unit
    ): HonedScriptResult

    suspend fun streamExpandBeat(
        projectContext: String,
        beatText: String,
        onStreamChunk: (String) -> Unit
    ): String

    suspend fun proposeShorten20Percent(beats: List<ScriptBeat>): Map<String, String>

    suspend fun factCheckBeat(topic: String, beatText: String): FactCheckResult

    suspend fun generateBRollPlate(bRollPrompt: String, topicContext: String): Bitmap
}

interface PoseGateGateway {
    /**
     * Uploads `photoSha` once to Cloud Storage CAS (skips if digest already exists)
     * and runs a <200 ms cloud face-pose/occlusion check. Zero on-device ML Kit.
     */
    suspend fun validatePhotoPose(
        photoSha: String,
        headModelId: String
    ): PoseGateResult

    /**
     * Optional 2s identity-locked eye-level reframing via Flux Kontext + user's `head_model_id` LoRA.
     * Returns the new normalized photo SHA-256 cached locally in CAS.
     */
    suspend fun normalizePhotoPose(
        photoSha: String,
        headModelId: String,
        backdropPrompt: String
    ): String
}

data class EnqueuedRenderResult(
    val jobId: String,
    val clipId: String,
    val inputHash: String
)

interface RenderJobGateway {
    suspend fun enqueueClipRender(
        project: ScriptProject,
        persona: PersonaProfile,
        providers: StageProviderSettings,
        photoSha: String
    ): EnqueuedRenderResult

    fun observeRenderJobs(): Flow<List<ClipRecord>>
}

interface PersonaTrainingGateway {
    suspend fun startPersonaTraining(
        trainingVideoGcsUris: List<String>,
        voiceProviderId: String
    ): String

    fun observePersonaProfile(): Flow<PersonaProfile?>
}
