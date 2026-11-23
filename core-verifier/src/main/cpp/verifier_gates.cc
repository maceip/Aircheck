#include "verifier_gates.h"

#include <algorithm>
#include <cmath>
#include <fstream>

namespace voicestudio::verifier {

namespace {

bool FileReadable(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    std::ifstream in(path, std::ios::binary);
    return in.good();
}

}  // namespace

VerifierGates::VerifierGates() = default;
VerifierGates::~VerifierGates() = default;

bool VerifierGates::Init(const VerifierConfig& config, std::string* error_out) {
    if (config.sherpa_ctc_model_dir.empty()) {
        if (error_out) {
            *error_out = "sherpa_ctc_model_dir must not be empty";
        }
        return false;
    }
    if (!FileReadable(config.wespeaker_onnx_path)) {
        if (error_out) {
            *error_out = "Missing WeSpeaker ONNX model: " + config.wespeaker_onnx_path;
        }
        return false;
    }
    if (!FileReadable(config.dnsmos_p808_onnx_path)) {
        if (error_out) {
            *error_out = "Missing DNSMOS p808 ONNX model: " + config.dnsmos_p808_onnx_path;
        }
        return false;
    }
    if (!FileReadable(config.master_centroid_bin_path)) {
        if (error_out) {
            *error_out = "Missing master speaker centroid: " + config.master_centroid_bin_path;
        }
        return false;
    }
    config_ = config;
    initialized_ = false;
    if (error_out) {
        *error_out = "Wave-1 build scaffold: sherpa-onnx + ORT sessions pending Milestone 2 implementation";
    }
    return false;
}

bool VerifierGates::VerifyCandidate(
    const float* pcm_44k,
    size_t total_samples,
    const std::string& expected_text,
    const VerifierThresholds& thresholds,
    GateResult* result_out,
    std::string* error_out) const {
    (void)expected_text;
    (void)thresholds;
    if (result_out == nullptr) {
        if (error_out) {
            *error_out = "result_out must not be null";
        }
        return false;
    }
    *result_out = GateResult{};
    result_out->tail_rms_db = ComputeTailRmsDb(pcm_44k, total_samples);
    if (!initialized_) {
        result_out->passed_all_gates = false;
        result_out->failure_reason = "VerifierGates is not initialized";
        if (error_out) {
            *error_out = result_out->failure_reason;
        }
        return false;
    }
    return false;
}

bool VerifierGates::IsInitialized() const {
    return initialized_;
}

float VerifierGates::ComputeTailRmsDb(
    const float* pcm_44k,
    size_t total_samples,
    int32_t tail_ms,
    int32_t sample_rate_hz) {
    if (pcm_44k == nullptr || total_samples == 0 || tail_ms <= 0 || sample_rate_hz <= 0) {
        return -120.0f;
    }
    const size_t window_samples = std::min(
        total_samples,
        static_cast<size_t>((static_cast<int64_t>(sample_rate_hz) * tail_ms) / 1000));
    if (window_samples == 0) {
        return -120.0f;
    }
    const size_t start = total_samples - window_samples;
    double sum_sq = 0.0;
    for (size_t i = start; i < total_samples; ++i) {
        const double s = static_cast<double>(pcm_44k[i]);
        sum_sq += s * s;
    }
    const double rms = std::sqrt(sum_sq / static_cast<double>(window_samples));
    if (rms <= 1e-6) {
        return -120.0f;
    }
    return static_cast<float>(20.0 * std::log10(rms));
}

int64_t VerifierGates::FindNearestZeroCrossing(
    const float* pcm_44k,
    int64_t total_samples,
    int64_t target_sample,
    int32_t scan_radius) {
    if (pcm_44k == nullptr || total_samples <= 0) {
        return 0;
    }
    const int64_t clamped_target = std::clamp<int64_t>(target_sample, 0, total_samples - 1);
    const int64_t radius = std::max<int32_t>(0, scan_radius);
    const int64_t lo = std::max<int64_t>(0, clamped_target - radius);
    const int64_t hi = std::min<int64_t>(total_samples - 1, clamped_target + radius);

    int64_t best_idx = clamped_target;
    float best_abs = std::fabs(pcm_44k[clamped_target]);
    bool found_sign_change = false;

    for (int64_t i = lo + 1; i <= hi; ++i) {
        const float prev = pcm_44k[i - 1];
        const float curr = pcm_44k[i];
        if ((prev <= 0.0f && curr >= 0.0f) || (prev >= 0.0f && curr <= 0.0f)) {
            const int64_t cand = (std::fabs(prev) <= std::fabs(curr)) ? (i - 1) : i;
            const float cand_abs = std::fabs(pcm_44k[cand]);
            if (!found_sign_change || cand_abs < best_abs) {
                found_sign_change = true;
                best_idx = cand;
                best_abs = cand_abs;
            }
        }
    }
    if (found_sign_change) {
        return best_idx;
    }
    for (int64_t i = lo; i <= hi; ++i) {
        const float v = std::fabs(pcm_44k[i]);
        if (v < best_abs) {
            best_abs = v;
            best_idx = i;
        }
    }
    return best_idx;
}

float VerifierGates::CosineSimilarity(const float* a, const float* b, size_t dim) {
    if (a == nullptr || b == nullptr || dim == 0) {
        return 0.0f;
    }
    double dot = 0.0;
    double norm_a = 0.0;
    double norm_b = 0.0;
    for (size_t i = 0; i < dim; ++i) {
        const double va = static_cast<double>(a[i]);
        const double vb = static_cast<double>(b[i]);
        dot += va * vb;
        norm_a += va * va;
        norm_b += vb * vb;
    }
    const double denom = std::sqrt(norm_a) * std::sqrt(norm_b);
    if (denom <= 1e-12) {
        return 0.0f;
    }
    return static_cast<float>(dot / denom);
}

}  // namespace voicestudio::verifier
