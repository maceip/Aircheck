#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace voicestudio::verifier {

constexpr int32_t kSampleRateHz = 44100;
constexpr int32_t kZeroCrossScanSamples = 88;  // +/- 2 ms @ 44.1 kHz
constexpr float kZeroCrossMaxAbsAmplitude = 0.005f;
constexpr int32_t kTailWindowMs = 80;          // 3,528 samples @ 44.1 kHz
constexpr size_t kSpeakerEmbeddingDim = 256;

struct VerifierThresholds {
    float max_cer = 0.0f;
    float max_phoneme_duration_ms = 380.0f;
    float min_speaker_cosine_sim = 0.86f;
    float min_dnsmos_p808 = 4.10f;
    float max_tail_rms_db = -45.0f;
    float min_syllables_per_sec = 4.2f;
    float max_syllables_per_sec = 6.1f;
    // Tail HF glitch thresholds (4 kHz+ band within final 80 ms window).
    float max_tail_hf_spectral_flux = 0.008f;
    float max_tail_hf_energy_db = -48.0f;
    float max_tail_peak_frame_rms_db = -38.0f;
};

struct RawWordSpan {
    std::string word;
    int64_t start_sample = 0;
    int64_t end_sample = 0;
};

struct WordAlignment {
    std::string word;
    int64_t start_sample = 0;
    int64_t end_sample = 0;
    int64_t zero_cross_in = 0;
    int64_t zero_cross_out = 0;
    int64_t f0_peak_sample = 0;
};

struct TailAnalysis {
    float tail_rms_db = -120.0f;
    float tail_peak_frame_rms_db = -120.0f;
    float tail_hf_energy_db = -120.0f;
    float tail_hf_spectral_flux = 0.0f;
    bool hf_glitch_detected = false;
};

struct CtcAlignmentOutput {
    std::string recognized_text;
    float max_phoneme_duration_ms = 0.0f;
    std::vector<RawWordSpan> word_spans;
};

struct GateResult {
    float cer = 1.0f;
    float max_phoneme_duration_ms = 0.0f;
    float speaker_cosine_sim = 0.0f;
    float dnsmos_p808 = 0.0f;
    float tail_rms_db = 0.0f;
    float tail_hf_spectral_flux = 0.0f;
    bool tail_hf_glitch_detected = false;
    float syllables_per_sec = 0.0f;
    bool passed_all_gates = false;
    std::string failure_reason;
    std::vector<WordAlignment> tokens;
};

struct VerifierConfig {
    std::string sherpa_ctc_model_dir;
    std::string wespeaker_onnx_path;
    std::string dnsmos_p808_onnx_path;
    std::string master_centroid_bin_path;
};

/**
 * Pluggable inference backends used by VerifierGates.
 * Production mode binds sherpa-onnx C API / ONNX Runtime sessions; unit tests may inject
 * deterministic backends operating over real waveforms without fabricating production paths.
 */
class CtcAlignerBackend {
public:
    virtual ~CtcAlignerBackend() = default;
    virtual bool Align(
        const float* pcm_44k,
        size_t total_samples,
        const std::string& expected_text,
        CtcAlignmentOutput* output,
        std::string* error_out) const = 0;
};

class SpeakerEmbeddingBackend {
public:
    virtual ~SpeakerEmbeddingBackend() = default;
    virtual bool ExtractEmbedding(
        const float* pcm_44k,
        size_t total_samples,
        std::array<float, kSpeakerEmbeddingDim>* embedding_out,
        std::string* error_out) const = 0;
};

class DnsmosP808Backend {
public:
    virtual ~DnsmosP808Backend() = default;
    virtual bool ScoreAudio(
        const float* pcm_44k,
        size_t total_samples,
        float* score_out,
        std::string* error_out) const = 0;
};

class VerifierGates {
public:
    VerifierGates();
    ~VerifierGates();

    VerifierGates(const VerifierGates&) = delete;
    VerifierGates& operator=(const VerifierGates&) = delete;

    /**
     * Validates all 4 model paths on disk, loads the 256-float master speaker centroid,
     * and binds native sherpa-onnx / ONNX Runtime C API sessions if available.
     * Returns false with an explicit error if any model file/dir is missing or unreadable.
     */
    bool Init(const VerifierConfig& config, std::string* error_out = nullptr);

    /**
     * Initializes with explicit inference backends and a real on-disk master_centroid.bin.
     * Used by host C++ integration tests and JNI/ORT-coordinated execution.
     */
    bool InitWithBackends(
        const std::string& master_centroid_bin_path,
        std::shared_ptr<CtcAlignerBackend> ctc_backend,
        std::shared_ptr<SpeakerEmbeddingBackend> speaker_backend,
        std::shared_ptr<DnsmosP808Backend> dnsmos_backend,
        std::string* error_out = nullptr);

    /**
     * Runs all 4 gates + zero-crossing & YIN F0 peak scanning on a 44.1 kHz Float32 PCM take.
     * Returns false cleanly if uninitialized or if any inference backend is unavailable/fails.
     * Never fabricates passing GateResult metrics.
     */
    bool VerifyCandidate(
        const float* pcm_44k,
        size_t total_samples,
        const std::string& expected_text,
        const VerifierThresholds& thresholds,
        GateResult* result_out,
        std::string* error_out = nullptr) const;

    /**
     * Deterministic 4-gate evaluation from already-computed model outputs (e.g., Kotlin OrtSession
     * WeSpeaker/DNSMOS + CTC alignment), computing exact Levenshtein CER, cosine similarity against
     * loaded 256-float centroid, tail RMS dBFS + HF spectral flux glitch detection, English syllable
     * cadence, +/- 88-sample zero crossings, and YIN F0 peak samples.
     */
    bool EvaluateWithModelOutputs(
        const float* pcm_44k,
        size_t total_samples,
        const std::string& expected_text,
        const CtcAlignmentOutput& ctc_output,
        const std::array<float, kSpeakerEmbeddingDim>& speaker_embedding,
        float dnsmos_p808_score,
        const VerifierThresholds& thresholds,
        GateResult* result_out,
        std::string* error_out = nullptr) const;

    bool IsInitialized() const;
    bool HasMasterCentroid() const;
    const std::array<float, kSpeakerEmbeddingDim>& MasterCentroid() const;
    bool HasNativeInferenceRuntimes() const;

    // --- Gate 1: Levenshtein CER & CTC Decoding / Forced Alignment ---
    static std::string NormalizeTranscript(const std::string& text);
    static float ComputeCer(
        const std::string& expected_text,
        const std::string& recognized_text);

    static bool CtcGreedyDecode(
        const float* log_probs,
        size_t num_frames,
        size_t vocab_size,
        int32_t blank_id,
        const std::vector<std::string>& vocab,
        int32_t frame_shift_samples,
        std::string* decoded_text_out,
        float* max_phoneme_duration_ms_out,
        int32_t sample_rate_hz = kSampleRateHz);

    static bool CtcViterbiForceAlign(
        const float* log_probs,
        size_t num_frames,
        size_t vocab_size,
        int32_t blank_id,
        const std::vector<int32_t>& target_token_ids,
        const std::vector<std::string>& vocab,
        int32_t frame_shift_samples,
        size_t total_samples,
        std::vector<RawWordSpan>* word_spans_out,
        float* max_phoneme_duration_ms_out,
        int32_t sample_rate_hz = kSampleRateHz);

    // --- Gate 2: Master Centroid Binary Reader & L2-Normalized Cosine Similarity ---
    static bool LoadMasterCentroid(
        const std::string& path,
        std::array<float, kSpeakerEmbeddingDim>* centroid_out,
        std::string* error_out = nullptr);

    static float CosineSimilarity(const float* a, const float* b, size_t dim);

    // --- Gate 3: Tail RMS dBFS & High-Frequency Spectral Flux Glitch Check ---
    static float ComputeTailRmsDb(
        const float* pcm_44k,
        size_t total_samples,
        int32_t tail_ms = kTailWindowMs,
        int32_t sample_rate_hz = kSampleRateHz);

    static TailAnalysis AnalyzeTail(
        const float* pcm_44k,
        size_t total_samples,
        const VerifierThresholds& thresholds = VerifierThresholds{},
        int32_t tail_ms = kTailWindowMs,
        int32_t sample_rate_hz = kSampleRateHz);

    // --- Gate 4: Deterministic English Syllable Counter & Cadence ---
    static int32_t CountWordSyllables(const std::string& word);
    static int32_t CountTranscriptSyllables(const std::string& text);
    static float ComputeSyllablesPerSec(
        const std::string& expected_text,
        int64_t active_speech_samples,
        int32_t sample_rate_hz = kSampleRateHz);

    // --- Zero-Crossing & YIN F0 Peak Scanners ---
    static int64_t FindNearestZeroCrossing(
        const float* pcm_44k,
        int64_t total_samples,
        int64_t target_sample,
        int32_t scan_radius = kZeroCrossScanSamples);

    static int64_t FindF0PeakSample(
        const float* pcm_44k,
        int64_t total_samples,
        int64_t start_sample,
        int64_t end_sample,
        int32_t sample_rate_hz = kSampleRateHz);

    static std::vector<WordAlignment> ScanWordBoundaries(
        const float* pcm_44k,
        int64_t total_samples,
        const std::vector<RawWordSpan>& raw_spans,
        int32_t scan_radius = kZeroCrossScanSamples,
        int32_t sample_rate_hz = kSampleRateHz);

private:
    bool initialized_ = false;
    bool has_centroid_ = false;
    VerifierConfig config_;
    std::array<float, kSpeakerEmbeddingDim> master_centroid_{};
    std::shared_ptr<CtcAlignerBackend> ctc_backend_;
    std::shared_ptr<SpeakerEmbeddingBackend> speaker_backend_;
    std::shared_ptr<DnsmosP808Backend> dnsmos_backend_;
};

}  // namespace voicestudio::verifier
