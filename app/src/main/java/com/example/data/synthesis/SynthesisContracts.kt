package com.example.data.synthesis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frozen Wave-0 wire & persistence contracts for the Aircheck Bare-Metal On-Device Synthesis,
 * 4-Gate C++ Verifier, 48kHz RVC v2 + Room IR Resynthesis, Hairline Clipper, and Nightly
 * GRPO/OPSD Self-Distillation pipeline.
 *
 * Schema mirrors:
 * - docs/swarm/schemas/clause_node.schema.json
 * - docs/swarm/schemas/ota_bundle_manifest.schema.json
 * - docs/swarm/schemas/preference_tuple.schema.json
 *
 * All audio sample indices are 48,000 Hz mono Float32 PCM (`PCM_48K_F32`).
 */
const val SYNTHESIS_SAMPLE_RATE_HZ: Int = 48_000
const val ACOUSTIC_PREFIX_TAIL_MS: Int = 1_500
const val ACOUSTIC_PREFIX_TAIL_SAMPLES: Int = (SYNTHESIS_SAMPLE_RATE_HZ * ACOUSTIC_PREFIX_TAIL_MS) / 1_000
const val MAX_CLAUSE_DURATION_SEC: Double = 15.0
const val FOREGROUND_RAM_CEILING_MB: Int = 9_500
const val VERIFIER_RAM_CEILING_MB: Int = 350
const val DEFAULT_TAKES_PER_CLAUSE: Int = 4

@Serializable
data class ClauseNode(
    @SerialName("clause_id") val clauseId: String,
    @SerialName("raw_text") val rawText: String,
    @SerialName("normalized_phonemes") val normalizedPhonemes: String,
    @SerialName("acoustic_prefix_uri") val acousticPrefixUri: String? = null,
    @SerialName("selected_take_index") val selectedTakeIndex: Int = 0,
    @SerialName("gap_after_ms") val gapAfterMs: Int = 85,
    @SerialName("candidates") val candidates: List<TakeCandidate> = emptyList()
) {
    val selectedCandidate: TakeCandidate?
        get() = candidates.getOrNull(selectedTakeIndex)
}

@Serializable
data class TakeCandidate(
    @SerialName("take_id") val takeId: String,
    @SerialName("pcm_uri") val pcmUri: String,
    @SerialName("seed") val seed: Long,
    @SerialName("temperature") val temperature: Double,
    @SerialName("metrics") val metrics: VerificationMetrics,
    @SerialName("tokens") val tokens: List<ZeroCrossingToken> = emptyList()
)

@Serializable
data class VerificationMetrics(
    @SerialName("cer") val cer: Double,
    @SerialName("speaker_cosine_sim") val speakerCosineSim: Double,
    @SerialName("utmos_score") val utmosScore: Double,
    @SerialName("syllables_per_sec") val syllablesPerSec: Double,
    @SerialName("passed_all_gates") val passedAllGates: Boolean
)

@Serializable
data class ZeroCrossingToken(
    @SerialName("word") val word: String,
    @SerialName("start_sample") val startSample: Long,
    @SerialName("end_sample") val endSample: Long,
    @SerialName("zero_cross_in") val zeroCrossIn: Long,
    @SerialName("zero_cross_out") val zeroCrossOut: Long,
    @SerialName("f0_peak_sample") val f0PeakSample: Long,
    @SerialName("video_anchor_id") val videoAnchorId: String? = null
)

/**
 * Deterministic 4-Gate acceptance criteria evaluated by Stage B C++ verifier.
 * Gate 1 requires exact CTC CER == 0.0.
 */
@Serializable
data class VerificationGateThresholds(
    val maxCer: Double = 0.0,
    val minSpeakerCosineSim: Double = 0.80,
    val minUtmosScore: Double = 4.0,
    val minSyllablesPerSec: Double = 2.8,
    val maxSyllablesPerSec: Double = 6.5
) {
    fun evaluate(
        cer: Double,
        speakerCosineSim: Double,
        utmosScore: Double,
        syllablesPerSec: Double
    ): VerificationMetrics {
        val passed = cer <= maxCer &&
            speakerCosineSim >= minSpeakerCosineSim &&
            utmosScore >= minUtmosScore &&
            syllablesPerSec in minSyllablesPerSec..maxSyllablesPerSec
        return VerificationMetrics(
            cer = cer,
            speakerCosineSim = speakerCosineSim,
            utmosScore = utmosScore,
            syllablesPerSec = syllablesPerSec,
            passedAllGates = passed
        )
    }
}

@Serializable
enum class ProsodyBackend {
    @SerialName("ming_3_0_tts_tiny") MING_3_0_TTS_TINY,
    @SerialName("fish_s2_pro_4b") FISH_S2_PRO_4B,
    @SerialName("voxcpm2") VOXCPM2
}

@Serializable
enum class OtaArtifactRole {
    @SerialName("prosody_generator") PROSODY_GENERATOR,
    @SerialName("ctc_verifier") CTC_VERIFIER,
    @SerialName("wespeaker_verifier") WESPEAKER_VERIFIER,
    @SerialName("speaker_centroid") SPEAKER_CENTROID,
    @SerialName("utmos_verifier") UTMOS_VERIFIER,
    @SerialName("rvc_v2_resynth") RVC_V2_RESYNTH,
    @SerialName("room_ir") ROOM_IR,
    @SerialName("g2p_lexicon") G2P_LEXICON
}

@Serializable
enum class OtaArtifactFormat {
    @SerialName("litertlm") LITERTLM,
    @SerialName("tflite") TFLITE,
    @SerialName("onnx") ONNX,
    @SerialName("json") JSON,
    @SerialName("f32") F32,
    @SerialName("wav") WAV
}

@Serializable
enum class PipelineStage {
    @SerialName("g2p") G2P,
    @SerialName("stage_a") STAGE_A,
    @SerialName("stage_b") STAGE_B,
    @SerialName("stage_c") STAGE_C
}

@Serializable
data class OtaModelArtifact(
    @SerialName("role") val role: OtaArtifactRole,
    @SerialName("filename") val filename: String,
    @SerialName("format") val format: OtaArtifactFormat,
    @SerialName("sha256") val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("estimated_ram_mb") val estimatedRamMb: Int,
    @SerialName("stage") val stage: PipelineStage,
    @SerialName("download_path") val downloadPath: String? = null
)

@Serializable
data class OtaBundleManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("bundle_version") val bundleVersion: String,
    @SerialName("created_at_utc") val createdAtUtc: String,
    @SerialName("prosody_backend") val prosodyBackend: ProsodyBackend,
    @SerialName("adapter_checkpoint_id") val adapterCheckpointId: String? = null,
    @SerialName("sample_rate_hz") val sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ,
    @SerialName("foreground_ram_ceiling_mb") val foregroundRamCeilingMb: Int = FOREGROUND_RAM_CEILING_MB,
    @SerialName("artifacts") val artifacts: List<OtaModelArtifact>
) {
    /**
     * Because Stage A, Stage B, and Stage C execute sequentially with explicit release between stages,
     * peak foreground RAM is the maximum of any single stage's artifacts plus baseline app overhead.
     */
    fun peakStageRamMb(): Int = PipelineStage.entries.maxOfOrNull { stage ->
        artifacts.filter { it.stage == stage }.sumOf { it.estimatedRamMb }
    } ?: 0

    fun verifierRamMb(): Int =
        artifacts.filter { it.stage == PipelineStage.STAGE_B }.sumOf { it.estimatedRamMb }

    fun fitsMemoryBudget(): Boolean =
        sampleRateHz == SYNTHESIS_SAMPLE_RATE_HZ &&
            foregroundRamCeilingMb <= FOREGROUND_RAM_CEILING_MB &&
            peakStageRamMb() <= foregroundRamCeilingMb &&
            verifierRamMb() <= VERIFIER_RAM_CEILING_MB
}

@Serializable
enum class PreferenceActionType {
    @SerialName("TAKE_SWAP") TAKE_SWAP,
    @SerialName("GAP_ADJUST") GAP_ADJUST,
    @SerialName("BOUNDARY_TRIM") BOUNDARY_TRIM,
    @SerialName("VIDEO_ANCHOR_SNAP") VIDEO_ANCHOR_SNAP,
    @SerialName("PHONEME_OVERRIDE") PHONEME_OVERRIDE
}

@Serializable
data class PreferenceTuple(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String,
    @SerialName("timestamp_utc") val timestampUtc: String,
    @SerialName("bundle_version") val bundleVersion: String,
    @SerialName("script_id") val scriptId: String,
    @SerialName("clause_id") val clauseId: String,
    @SerialName("raw_text") val rawText: String,
    @SerialName("normalized_phonemes") val normalizedPhonemes: String,
    @SerialName("acoustic_prefix_uri") val acousticPrefixUri: String? = null,
    @SerialName("action_type") val actionType: PreferenceActionType,
    @SerialName("chosen_take_id") val chosenTakeId: String,
    @SerialName("rejected_take_ids") val rejectedTakeIds: List<String>,
    @SerialName("chosen_metrics") val chosenMetrics: VerificationMetrics? = null,
    @SerialName("rejected_metrics") val rejectedMetrics: List<VerificationMetrics> = emptyList(),
    @SerialName("gap_after_ms") val gapAfterMs: Int,
    @SerialName("trim_in_sample") val trimInSample: Long? = null,
    @SerialName("trim_out_sample") val trimOutSample: Long? = null,
    @SerialName("video_anchor_id") val videoAnchorId: String? = null
)

enum class OtaBundleStatus {
    NOT_INSTALLED,
    SYNCING,
    READY,
    CORRUPT_OR_INCOMPATIBLE,
    SYNC_FAILED
}

data class OtaBundleState(
    val status: OtaBundleStatus = OtaBundleStatus.NOT_INSTALLED,
    val tailscaleBaseUrl: String = "",
    val activeManifest: OtaBundleManifest? = null,
    val installedDirPath: String? = null,
    val verifiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

enum class ClauseSynthesisPhase {
    IDLE,
    NORMALIZING,
    STAGE_A_PROSODY,
    STAGE_B_VERIFIER,
    STAGE_C_RESYNTHESIS,
    READY,
    FAILED
}

data class ClauseSynthesisProgress(
    val scriptId: String = "",
    val activeClauseId: String? = null,
    val phase: ClauseSynthesisPhase = ClauseSynthesisPhase.IDLE,
    val completedClauses: Int = 0,
    val totalClauses: Int = 0,
    val measuredRssMb: Int? = null,
    val errorMessage: String? = null
)
