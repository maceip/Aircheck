package com.example.data.synthesis

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Frozen Wave-0 subsystem boundaries so Wave-2 swarm workers (`native-dsp`, `g2p-storage`,
 * `synth-workers`, `hairline-clipper`) compile and unit-test in parallel without cross-worktree
 * file conflicts.
 *
 * Constitutional Law 2 Mandate:
 * Implementations MUST NEVER return fabricated PCM, synthetic verification scores, or fake
 * bundle statuses. Missing OTA model bundles or native failures MUST propagate real errors.
 */

/** Owned by `g2p-storage` (`com.example.engine.g2p`). */
interface ClauseNormalizer {
    /**
     * Splits raw script text into sequential clauses each strictly < 15.0 seconds estimated speech,
     * applying deterministic text normalization and IPA pronunciation lookup from OTA `lexicon.json`.
     */
    fun normalizeAndSplit(
        scriptId: String,
        rawDraft: String,
        lexiconFile: File?,
        phoneticOverrides: Map<String, String> = emptyMap()
    ): List<ClauseNode>
}

/** Owned by `g2p-storage` (`com.example.data.synthesis`). */
interface OtaBundleManager {
    val bundleState: StateFlow<OtaBundleState>

    /** Syncs manifest + SHA-256 verified model artifacts over Tailscale/WireGuard into noBackupFilesDir. */
    suspend fun syncFromRig(tailscaleBaseUrl: String): Result<OtaBundleManifest>

    /** Resolves a verified installed artifact file by role, or null if not installed / failed hash check. */
    suspend fun resolveArtifactFile(role: OtaArtifactRole): File?

    /** Returns the active verified manifest if all required artifacts exist and pass SHA-256 checks. */
    suspend fun loadVerifiedManifest(): OtaBundleManifest?
}

/** Owned by `g2p-storage` (`com.example.data.synthesis` + Room WAL DAO). */
interface SynthesisRepository {
    fun observeClauses(scriptId: String): Flow<List<ClauseNode>>
    suspend fun getClauses(scriptId: String): List<ClauseNode>
    suspend fun upsertClauses(scriptId: String, clauses: List<ClauseNode>)
    suspend fun selectTake(scriptId: String, clauseId: String, takeIndex: Int, bundleVersion: String)
    suspend fun updateGapAfterMs(scriptId: String, clauseId: String, gapAfterMs: Int, bundleVersion: String)
    suspend fun updateAllGapsMs(scriptId: String, gapAfterMs: Int, bundleVersion: String)
    suspend fun bindVideoAnchor(scriptId: String, clauseId: String, wordIndex: Int, videoAnchorId: String?, bundleVersion: String)
    suspend fun recordPreference(tuple: PreferenceTuple)
    suspend fun listUnsyncedPreferences(limit: Int = 200): List<PreferenceTuple>
    suspend fun markPreferencesSynced(eventIds: List<String>)
    fun observeUnsyncedPreferenceCount(): Flow<Int>
}

/** Owned by `native-dsp` (`com.example.nativeaudio` + `app/src/main/cpp`). */
interface NativeDspBridge : AutoCloseable {
    /** Loads Stage B 4-gate verifier models (~350 MB RAM budget via XNNPACK mmap). */
    fun loadVerifierSession(
        ctcModelPath: String,
        weSpeakerModelPath: String,
        speakerCentroidPath: String,
        utmosModelPath: String
    ): Result<Unit>

    /** Evaluates CTC CER, WeSpeaker cosine sim, UTMOSv2, and syllable rate on 48kHz f32 PCM. */
    fun verifyCandidate(
        pcm48kF32Path: String,
        expectedText: String,
        expectedPhonemes: String,
        thresholds: VerificationGateThresholds = VerificationGateThresholds()
    ): Result<VerificationMetrics>

    /** Unloads Stage B verifier weights immediately to reclaim RAM. */
    fun unloadVerifierSession()

    /** Loads Stage C RVC v2 ONNX model and 48kHz Room IR impulse response. */
    fun loadResynthesisSession(
        rvcOnnxPath: String,
        roomIrPath: String
    ): Result<Unit>

    /**
     * Runs 48kHz RVC v2 harmonic resynthesis + partitioned Room IR convolution, writes output
     * 48kHz f32 PCM + 1500ms acoustic tail, and computes sample-accurate zero-crossing & F0 peak tokens.
     */
    fun resynthesizeAndAnalyze(
        inputPcm48kPath: String,
        outputPcm48kPath: String,
        outputTail1500msPath: String,
        alignedWords: List<String>,
        wordSampleBounds: LongArray
    ): Result<List<ZeroCrossingToken>>

    /** Unloads Stage C RVC + IR weights immediately. */
    fun unloadResynthesisSession()
}

/** Owned by `native-dsp` (`com.example.nativeaudio` + Oboe C++ stream). */
interface OboeScrubberBridge : AutoCloseable {
    val isPlaying: StateFlow<Boolean>
    val playheadSample: StateFlow<Long>

    /** Loads ordered clause PCM slices and gap timings for zero-crossing spliced hairline playback. */
    fun loadTimeline(clauses: List<ClauseNode>, takesRootDir: File): Result<Unit>

    /** Sample-accurate seek used by the Fixed Center Blade + Dual-Speed Vernier Scrubber. */
    fun seekToSample(sampleIndex: Long)

    /** Hot-swaps a single clause take at zero-crossing boundaries without tearing down the Oboe stream. */
    fun hotSwapClauseTake(clauseId: String, take: TakeCandidate, takesRootDir: File): Result<Unit>

    fun play()
    fun pause()
}

/** Owned by `synth-workers` (`com.example.engine.synthesis` + `com.example.worker`). */
interface ClauseSynthesisCoordinator {
    val progress: StateFlow<ClauseSynthesisProgress>

    /** Enqueues WorkManager expedited synthesis for all clauses of a script. */
    fun enqueueScriptSynthesis(scriptId: String, takesPerClause: Int = DEFAULT_TAKES_PER_CLAUSE)

    /** Enqueues Wi-Fi-only (`NetworkType.UNMETERED`) JSONL preference telemetry upload to Tailscale rig. */
    fun enqueuePreferenceSync()

    fun cancelActiveSynthesis()
}
