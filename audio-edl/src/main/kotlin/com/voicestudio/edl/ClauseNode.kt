package com.voicestudio.edl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical 44.1 kHz Float32 PCM (64-byte aligned, headerless mmap) data contracts shared across
 * `:audio-edl`, `:core-engine`, `:core-verifier`, `:ui-clipper`, `:sync-trainer:android-worker`,
 * and `:app`, matching frozen Wave-0 `SynthesisContracts.kt` and rectified `SPEC.md`.
 */
const val SYNTHESIS_SAMPLE_RATE_HZ: Int = 44_100
const val ACOUSTIC_PREFIX_TAIL_SAMPLES: Int = 66_150       // 1.5s @ 44.1 kHz
const val ZERO_CROSSING_SCAN_SAMPLES: Int = 88             // +/- 2ms @ 44.1 kHz
const val ZERO_CROSSING_MAX_ABS_AMPLITUDE: Float = 0.005f
const val DEFAULT_BASE_GAP_SAMPLES: Int = 3_969            // 90ms @ 44.1 kHz
const val EDL_CROSSFADE_SAMPLES: Int = 660                 // 15ms equal-power cosine @ 44.1 kHz
const val MAX_CLAUSE_DURATION_SEC: Double = 14.0
const val FOREGROUND_RAM_CEILING_MB: Int = 9_500
const val DEFAULT_TAKES_PER_CLAUSE: Int = 4
const val VERNIER_COARSE_SAMPLES_PER_DP: Int = 220         // ~5.0 ms / dp
const val VERNIER_FINE_SAMPLES_PER_INCH: Int = 2_205       // 50.0 ms / 160 dp (1:20 gear ratio)

@Serializable
data class VoiceProjectManifest(
    val projectId: String,
    val sampleRate: Int = SYNTHESIS_SAMPLE_RATE_HZ,
    val globalGapScale: Float = 1.0f, // 1.0f = 100%, 0.25f = 25% tight compression
    val activeLoraVersion: Int = 0,
    val clauses: List<ClauseNode> = emptyList()
)

@Serializable
data class ClauseNode(
    val clauseId: String,
    val index: Int,
    val rawText: String,
    val normalizedPhonemes: String,
    val acousticPrefixPcmPath: String? = null, // Trailing 1.5s (66,150 samples) of previous clause
    val selectedTakeIndex: Int = 0,
    val baseGapAfterSamples: Int = DEFAULT_BASE_GAP_SAMPLES,
    val candidates: List<CandidateTake> = emptyList()
) {
    val selectedCandidate: CandidateTake?
        get() = candidates.getOrNull(selectedTakeIndex)
}

@Serializable
data class CandidateTake(
    val takeId: String,
    val pcmFilePath: String,            // 44.1kHz Float32 raw PCM (64-byte aligned for mmap)
    val totalSamples: Long,
    val discreteAudioTokens: IntArray,  // Flattened s2-pro codebook token IDs for Tinker OPSD/GRPO
    val seed: Long,
    val temperature: Float,
    val metrics: GateMetrics,
    val tokens: List<WordToken> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CandidateTake) return false
        return takeId == other.takeId &&
            pcmFilePath == other.pcmFilePath &&
            totalSamples == other.totalSamples &&
            discreteAudioTokens.contentEquals(other.discreteAudioTokens) &&
            seed == other.seed &&
            temperature == other.temperature &&
            metrics == other.metrics &&
            tokens == other.tokens
    }

    override fun hashCode(): Int {
        var result = takeId.hashCode()
        result = 31 * result + pcmFilePath.hashCode()
        result = 31 * result + totalSamples.hashCode()
        result = 31 * result + discreteAudioTokens.contentHashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + tokens.hashCode()
        return result
    }
}

@Serializable
data class GateMetrics(
    val cer: Float,                     // Must be == 0.0f
    val maxPhonemeDurationMs: Float,    // Must be <= 380.0f
    val speakerCosineSim: Float,        // Must be >= 0.86f
    val dnsmosP808: Float,              // Must be >= 4.10f
    val tailRmsDb: Float,               // Final 80ms energy; must be <= -45.0f dBFS
    val syllablesPerSec: Float,         // Target band: 4.2f..6.1f
    val passedAllGates: Boolean
)

@Serializable
data class WordToken(
    val word: String,
    val startSample: Long,
    val endSample: Long,
    val zeroCrossIn: Long,              // Nearest zero-crossing to startSample (+/- 88 samples)
    val zeroCrossOut: Long,             // Nearest zero-crossing to endSample (+/- 88 samples)
    val f0PeakSample: Long,             // Pitch emphasis anchor for video cuts
    val videoAnchorId: String? = null   // Bound Media3 ClippingConfiguration ID
)

@Serializable
data class EdlAudioSlice(
    val pcmFilePath: String,
    val zeroCrossInSample: Long,
    val zeroCrossOutSample: Long,
    val gapAfterSamples: Int
)

@Serializable
data class VerificationGateThresholds(
    val maxCer: Float = 0.0f,
    val maxPhonemeDurationMs: Float = 380.0f,
    val minSpeakerCosineSim: Float = 0.86f,
    val minDnsmosP808: Float = 4.10f,
    val maxTailRmsDb: Float = -45.0f,
    val minSyllablesPerSec: Float = 4.2f,
    val maxSyllablesPerSec: Float = 6.1f
) {
    fun evaluate(
        cer: Float,
        maxPhonemeDurationMs: Float,
        speakerCosineSim: Float,
        dnsmosP808: Float,
        tailRmsDb: Float,
        syllablesPerSec: Float
    ): GateMetrics {
        val passed = cer <= maxCer &&
            maxPhonemeDurationMs <= this.maxPhonemeDurationMs &&
            speakerCosineSim >= minSpeakerCosineSim &&
            dnsmosP808 >= minDnsmosP808 &&
            tailRmsDb <= maxTailRmsDb &&
            syllablesPerSec in minSyllablesPerSec..maxSyllablesPerSec
        return GateMetrics(
            cer = cer,
            maxPhonemeDurationMs = maxPhonemeDurationMs,
            speakerCosineSim = speakerCosineSim,
            dnsmosP808 = dnsmosP808,
            tailRmsDb = tailRmsDb,
            syllablesPerSec = syllablesPerSec,
            passedAllGates = passed
        )
    }
}

/**
 * Deterministic Media3 beat-anchor calculation (SPEC.md Section 4B).
 * Recomputes microsecond timestamps for every bound `videoAnchorId` whenever `globalGapScale`
 * changes or a take is swapped.
 */
fun computeAnchorTimestampsUs(manifest: VoiceProjectManifest): Map<String, Long> {
    val anchorMapUs = linkedMapOf<String, Long>()
    val rate = manifest.sampleRate.coerceAtLeast(1).toLong()
    var cumulativeSamples = 0L

    for (clause in manifest.clauses) {
        val winner = clause.selectedCandidate ?: continue
        val clauseStartInSample = winner.tokens.firstOrNull()?.zeroCrossIn ?: 0L
        for (token in winner.tokens) {
            val anchorId = token.videoAnchorId
            if (!anchorId.isNullOrBlank()) {
                val tokenOffsetInClause = (token.zeroCrossIn - clauseStartInSample).coerceAtLeast(0L)
                val absoluteSample = cumulativeSamples + tokenOffsetInClause
                anchorMapUs[anchorId] = (absoluteSample * 1_000_000L) / rate
            }
        }
        val activeClauseDuration = if (winner.tokens.isNotEmpty()) {
            (winner.tokens.last().zeroCrossOut - clauseStartInSample).coerceAtLeast(0L)
        } else {
            winner.totalSamples.coerceAtLeast(0L)
        }
        val scaledGap = (clause.baseGapAfterSamples * manifest.globalGapScale).toLong().coerceAtLeast(0L)
        cumulativeSamples += activeClauseDuration + scaledGap
    }
    return anchorMapUs
}

@Serializable
enum class OtaArtifactRole {
    @SerialName("s2_pro_base_gguf") S2_PRO_BASE_GGUF,
    @SerialName("voice_lora_gguf") VOICE_LORA_GGUF,
    @SerialName("codec_decoder_tflite") CODEC_DECODER_TFLITE,
    @SerialName("sherpa_ctc_aligner") SHERPA_CTC_ALIGNER,
    @SerialName("wespeaker_resnet34_onnx") WESPEAKER_RESNET34_ONNX,
    @SerialName("master_centroid_bin") MASTER_CENTROID_BIN,
    @SerialName("dnsmos_p808_onnx") DNSMOS_P808_ONNX,
    @SerialName("room_ir_pcm") ROOM_IR_PCM,
    @SerialName("room_tone_pcm") ROOM_TONE_PCM,
    @SerialName("g2p_lexicon") G2P_LEXICON
}

@Serializable
enum class OtaArtifactFormat {
    @SerialName("gguf") GGUF,
    @SerialName("tflite") TFLITE,
    @SerialName("onnx") ONNX,
    @SerialName("bin") BIN,
    @SerialName("pcm") PCM,
    @SerialName("json") JSON,
    @SerialName("tar_gz") TAR_GZ
}

@Serializable
enum class OtaUpdateCadence {
    @SerialName("static_once") STATIC_ONCE,
    @SerialName("nightly_delta") NIGHTLY_DELTA
}

@Serializable
data class OtaModelArtifact(
    @SerialName("role") val role: OtaArtifactRole,
    @SerialName("filename") val filename: String,
    @SerialName("format") val format: OtaArtifactFormat,
    @SerialName("sha256") val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("estimated_ram_mb") val estimatedRamMb: Int,
    @SerialName("update_cadence") val updateCadence: OtaUpdateCadence,
    @SerialName("download_path") val downloadPath: String? = null
)

@Serializable
data class OtaBundleManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("bundle_version") val bundleVersion: String,
    @SerialName("lora_version") val loraVersion: Int,
    @SerialName("created_at_utc") val createdAtUtc: String,
    @SerialName("sample_rate_hz") val sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ,
    @SerialName("foreground_ram_ceiling_mb") val foregroundRamCeilingMb: Int = FOREGROUND_RAM_CEILING_MB,
    @SerialName("artifacts") val artifacts: List<OtaModelArtifact>
) {
    fun totalEstimatedRamMb(): Int = artifacts.sumOf { it.estimatedRamMb }

    fun fitsMemoryBudget(): Boolean =
        sampleRateHz == SYNTHESIS_SAMPLE_RATE_HZ &&
            foregroundRamCeilingMb <= FOREGROUND_RAM_CEILING_MB &&
            totalEstimatedRamMb() <= foregroundRamCeilingMb
}

@Serializable
enum class PreferenceUserAction {
    @SerialName("TAKE_SWAP") TAKE_SWAP,
    @SerialName("PACE_EDIT") PACE_EDIT,
    @SerialName("TAKE_SWAP_AND_PACE_EDIT") TAKE_SWAP_AND_PACE_EDIT,
    @SerialName("PHONEME_OVERRIDE") PHONEME_OVERRIDE,
    @SerialName("VIDEO_ANCHOR_SNAP") VIDEO_ANCHOR_SNAP,
    @SerialName("PROJECT_EXPORT") PROJECT_EXPORT
}

@Serializable
data class GateFailureRecord(
    @SerialName("seed") val seed: Long,
    @SerialName("cer") val cer: Float,
    @SerialName("reason") val reason: String,
    @SerialName("tokens") val tokens: List<Int>
)

@Serializable
data class PreferenceTelemetryRecord(
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("clause_id") val clauseId: String,
    @SerialName("lora_version") val loraVersion: Int,
    @SerialName("raw_text") val rawText: String,
    @SerialName("normalized_phonemes") val normalizedPhonemes: String,
    @SerialName("user_action") val userAction: PreferenceUserAction,
    @SerialName("user_gap_scale") val userGapScale: Float,
    @SerialName("chosen_tokens") val chosenTokens: List<Int>,
    @SerialName("rejected_tokens") val rejectedTokens: List<Int>,
    @SerialName("gate_failures") val gateFailures: List<GateFailureRecord> = emptyList()
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
    val activeLoraVersion: Int = 0,
    val installedDirPath: String? = null,
    val verifiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

enum class ClauseSynthesisPhase {
    IDLE,
    NORMALIZING,
    STAGE_A_GGUF_AND_LITERT_DAC,
    STAGE_B_4GATE_VERIFIER,
    STAGE_C_NEON_MASTERING,
    READY,
    FAILED
}

data class ClauseSynthesisProgress(
    val projectId: String = "",
    val activeClauseId: String? = null,
    val phase: ClauseSynthesisPhase = ClauseSynthesisPhase.IDLE,
    val completedClauses: Int = 0,
    val totalClauses: Int = 0,
    val measuredRssMb: Int? = null,
    val errorMessage: String? = null
)
