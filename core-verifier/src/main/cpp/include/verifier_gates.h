#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace voicestudio::verifier {

constexpr int32_t kSampleRateHz = 44100;
constexpr int32_t kZeroCrossScanSamples = 88;  // +/- 2 ms @ 44.1 kHz
constexpr int32_t kTailWindowMs = 80;
constexpr size_t kSpeakerEmbeddingDim = 256;

struct VerifierThresholds {
    float max_cer = 0.0f;
    float max_phoneme_duration_ms = 380.0f;
    float min_speaker_cosine_sim = 0.86f;
    float min_dnsmos_p808 = 4.10f;
    float max_tail_rms_db = -45.0f;
    float min_syllables_per_sec = 4.2f;
    float max_syllables_per_sec = 6.1f;
};

struct WordAlignment {
    std::string word;
    int64_t start_sample = 0;
    int64_t end_sample = 0;
    int64_t zero_cross_in = 0;
    int64_t zero_cross_out = 0;
    int64_t f0_peak_sample = 0;
};

struct GateResult {
    float cer = 1.0f;
    float max_phoneme_duration_ms = 0.0f;
    float speaker_cosine_sim = 0.0f;
    float dnsmos_p808 = 0.0f;
    float tail_rms_db = 0.0f;
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

class VerifierGates {
public:
    VerifierGates();
    ~VerifierGates();

    VerifierGates(const VerifierGates&) = delete;
    VerifierGates& operator=(const VerifierGates&) = delete;

    bool Init(const VerifierConfig& config, std::string* error_out = nullptr);
    bool VerifyCandidate(
        const float* pcm_44k,
        size_t total_samples,
        const std::string& expected_text,
        const VerifierThresholds& thresholds,
        GateResult* result_out,
        std::string* error_out = nullptr) const;
    bool IsInitialized() const;

    static float ComputeTailRmsDb(
        const float* pcm_44k,
        size_t total_samples,
        int32_t tail_ms = kTailWindowMs,
        int32_t sample_rate_hz = kSampleRateHz);

    static int64_t FindNearestZeroCrossing(
        const float* pcm_44k,
        int64_t total_samples,
        int64_t target_sample,
        int32_t scan_radius = kZeroCrossScanSamples);

    static float CosineSimilarity(const float* a, const float* b, size_t dim);

private:
    bool initialized_ = false;
    VerifierConfig config_;
};

}  // namespace voicestudio::verifier
