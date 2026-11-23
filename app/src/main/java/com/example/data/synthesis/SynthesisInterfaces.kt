package com.example.data.synthesis

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Frozen Wave-0 subsystem boundaries aligned with rectified SPEC.md's 5 Milestones:
 *   M1: :core-engine   -> VoiceEngineBridge + ClauseNormalizer
 *   M2: :core-verifier -> ClauseVerifierBridge
 *   M3: :audio-edl     -> EdlPlayerBridge
 *   M4: :ui-clipper    -> Compose Vernier Clipper + Media3BeatExporter
 *   M5: :sync-trainer  -> OtaBundleManager + PreferenceSyncCoordinator + tinker_voice_daemon.py
 *
 * Constitutional Law 2 Mandate:
 * Implementations MUST NEVER return fabricated PCM, synthetic verification scores, or fake
 * bundle statuses. Missing OTA model bundles or native failures MUST propagate real errors.
 */

data class RawTakeBuffer(
    val discreteAudioTokens: IntArray,
    val pcm44k: FloatArray,
    val seed: Long,
    val temperature: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTakeBuffer) return false
        return discreteAudioTokens.contentEquals(other.discreteAudioTokens) &&
            pcm44k.contentEquals(other.pcm44k) &&
            seed == other.seed &&
            temperature == other.temperature
    }

    override fun hashCode(): Int {
        var result = discreteAudioTokens.contentHashCode()
        result = 31 * result + pcm44k.contentHashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + temperature.hashCode()
        return result
    }
}

data class VerificationOutcome(
    val metrics: GateMetrics,
    val tokens: List<WordToken>
)

data class EdlAudioSlice(
    val pcmFilePath: String,
    val zeroCrossInSample: Long,
    val zeroCrossOutSample: Long,
    val gapAfterSamples: Int
)

/** Milestone 1 (`:core-engine`): Deterministic lexicon.json IPA G2P normalizer & <14s clause splitter. */
interface ClauseNormalizer {
    fun normalizeAndSplit(
        projectId: String,
        rawDraft: String,
        lexiconFile: File?,
        phoneticOverrides: Map<String, String> = emptyMap()
    ): List<ClauseNode>
}

/** Milestone 1 (`:core-engine`): C++20 JNI llama.cpp (s2-pro-GGUF + LoRA) + LiteRT DAC + NEON DSP. */
interface VoiceEngineBridge : AutoCloseable {
    fun initEngine(
        baseGgufPath: String,
        loraGgufPath: String,
        vocoderTflitePath: String,
        roomIrPcmPath: String
    ): Result<Long>

    /** Atomic 1-line C++ LoRA hot-swap via llama_adapter_lora_init / llama_set_adapter_lora. */
    fun hotSwapLora(engineHandle: Long, newLoraGgufPath: String): Result<Boolean>

    /** Generates N=4 discrete token rollouts, decodes via LiteRT 44.1kHz DAC, runs NEON mastering. */
    fun synthesizeCandidates(
        engineHandle: Long,
        normalizedText: String,
        prefixPcmPath: String?,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE
    ): Result<List<RawTakeBuffer>>

    /** Scans +/- 88 samples (2ms @ 44.1kHz) around targetSample for minimum |pcm[i]| sign change. */
    fun findNearestZeroCrossing(pcmFilePath: String, targetSample: Long): Long

    /** Releases mmap weight pages via madvise(MADV_DONTNEED) when synthesis queue is idle. */
    fun evictWeightPages(engineHandle: Long)
}

/** Milestone 2 (`:core-verifier`): C++ sherpa-onnx CTC Forced Aligner + WeSpeaker ResNet34 + DNSMOS p808. */
interface ClauseVerifierBridge : AutoCloseable {
    fun initVerifier(
        sherpaCtcModelDir: String,
        weSpeakerOnnxPath: String,
        dnsmosP808OnnxPath: String,
        masterCentroidBinPath: String
    ): Result<Long>

    fun verifyCandidate(
        verifierHandle: Long,
        pcm44k: FloatArray,
        expectedText: String,
        thresholds: VerificationGateThresholds = VerificationGateThresholds()
    ): Result<VerificationOutcome>
}

/** Milestone 3 (`:audio-edl`): Wait-free Oboe (AAudio) double-buffered EDL ring buffer with 15ms cosine x-fade. */
interface EdlPlayerBridge : AutoCloseable {
    val isPlaying: StateFlow<Boolean>
    val playheadSample: StateFlow<Long>

    fun initPlayer(roomToneLoop44kPath: String): Result<Unit>
    fun start(): Result<Unit>
    fun pause()
    fun seekToSample(timelineSample: Long)
    fun getCurrentSample(): Long
    fun getXRunCount(): Int

    /** Wait-free atomic std::atomic<EdlSnapshot*> swap (<0.05ms, zero WAV stitching on disk). */
    fun updateEdlSnapshot(slices: List<EdlAudioSlice>): Result<Unit>
}

/** Milestone 5 (`:sync-trainer`): Tailscale/HTTP OTA static + nightly 35MB LoRA delta manager. */
interface OtaBundleManager {
    val bundleState: StateFlow<OtaBundleState>

    /** Syncs static base artifacts once and nightly voice_lora_vXXX.gguf delta via SHA-256 + atomic rename. */
    suspend fun syncFromRig(tailscaleBaseUrl: String): Result<OtaBundleManifest>

    /** Pulls ONLY the latest ~35 MB voice_lora_vXXX.gguf delta if lora_version > current. */
    suspend fun pullLatestLoraDelta(tailscaleBaseUrl: String): Result<File?>

    suspend fun resolveArtifactFile(role: OtaArtifactRole): File?
    suspend fun loadVerifiedManifest(): OtaBundleManifest?
}

/** Shared Room WAL repository backing :ui-clipper and :sync-trainer. */
interface VoiceProjectRepository {
    fun observeProject(projectId: String): Flow<VoiceProjectManifest?>
    suspend fun upsertProject(manifest: VoiceProjectManifest)
    suspend fun selectTake(projectId: String, clauseId: String, takeIndex: Int)
    suspend fun updateGlobalGapScale(projectId: String, globalGapScale: Float)
    suspend fun bindVideoAnchor(projectId: String, clauseId: String, wordIndex: Int, videoAnchorId: String?)
    suspend fun appendPreference(record: PreferenceTelemetryRecord)
    suspend fun listUnsyncedPreferences(limit: Int = 200): List<PreferenceTelemetryRecord>
    suspend fun markPreferencesSynced(timestamps: List<Long>)
    fun observeUnsyncedPreferenceCount(): Flow<Int>
}

/** WorkManager Expedited synthesis + Wi-Fi/Tailscale preference upload & atomic LoRA hot-swap coordinator. */
interface VoiceStudioCoordinator {
    val progress: StateFlow<ClauseSynthesisProgress>

    fun enqueueProjectSynthesis(projectId: String, numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE)
    fun enqueueSingleClauseResynthesis(projectId: String, clauseId: String)
    fun enqueuePreferenceSyncAndLoraCheck()
    fun cancelActiveSynthesis()
}
