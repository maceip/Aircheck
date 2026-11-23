#include "dsp_mastering.h"

#include <algorithm>
#include <cmath>
#include <fstream>

namespace voicestudio::engine {

int64_t FindNearestZeroCrossing(
    const float* pcm,
    int64_t total_samples,
    int64_t target_sample,
    int32_t scan_radius) {
    if (pcm == nullptr || total_samples <= 0) {
        return 0;
    }
    const int64_t clamped_target = std::clamp<int64_t>(target_sample, 0, total_samples - 1);
    const int64_t radius = std::max<int32_t>(0, scan_radius);
    const int64_t lo = std::max<int64_t>(0, clamped_target - radius);
    const int64_t hi = std::min<int64_t>(total_samples - 1, clamped_target + radius);

    int64_t best_sign_change_idx = -1;
    float best_sign_change_abs = 0.0f;

    int64_t best_overall_idx = clamped_target;
    float best_overall_abs = std::fabs(pcm[clamped_target]);

    for (int64_t i = lo; i <= hi; ++i) {
        const float abs_val = std::fabs(pcm[i]);
        if (abs_val < best_overall_abs) {
            best_overall_abs = abs_val;
            best_overall_idx = i;
        }
        if (i > lo) {
            const float prev = pcm[i - 1];
            const float curr = pcm[i];
            const bool crosses_zero = (prev <= 0.0f && curr >= 0.0f) || (prev >= 0.0f && curr <= 0.0f);
            if (crosses_zero) {
                const int64_t candidate_idx = (std::fabs(prev) <= abs_val) ? (i - 1) : i;
                const float candidate_abs = std::fabs(pcm[candidate_idx]);
                if (best_sign_change_idx < 0 || candidate_abs < best_sign_change_abs) {
                    best_sign_change_idx = candidate_idx;
                    best_sign_change_abs = candidate_abs;
                }
            }
        }
    }

    return (best_sign_change_idx >= 0) ? best_sign_change_idx : best_overall_idx;
}

void MasterClausePcm(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len,
    const float* room_tone,
    size_t room_tone_len,
    float target_lufs,
    float noise_floor_dbfs) {
    (void)pcm;
    (void)room_ir;
    (void)room_ir_len;
    (void)room_tone;
    (void)room_tone_len;
    (void)target_lufs;
    (void)noise_floor_dbfs;
}

std::vector<float> ExtractAcousticPrefixTail(
    const float* pcm,
    size_t total_samples,
    size_t tail_samples) {
    if (pcm == nullptr || total_samples == 0 || tail_samples == 0) {
        return {};
    }
    const size_t count = std::min(total_samples, tail_samples);
    const float* start = pcm + (total_samples - count);
    return std::vector<float>(start, start + count);
}

bool WriteAlignedPcmFile(
    const std::string& path,
    const float* pcm,
    size_t total_samples) {
    if (path.empty() || (pcm == nullptr && total_samples > 0)) {
        return false;
    }
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out.good()) {
        return false;
    }
    if (total_samples > 0) {
        out.write(
            reinterpret_cast<const char*>(pcm),
            static_cast<std::streamsize>(total_samples * sizeof(float)));
    }
    const size_t written_bytes = total_samples * sizeof(float);
    const size_t remainder = written_bytes % kPcmFileAlignmentBytes;
    if (remainder != 0) {
        const size_t pad = kPcmFileAlignmentBytes - remainder;
        char zeros[kPcmFileAlignmentBytes] = {};
        out.write(zeros, static_cast<std::streamsize>(pad));
    }
    return out.good();
}

}  // namespace voicestudio::engine
