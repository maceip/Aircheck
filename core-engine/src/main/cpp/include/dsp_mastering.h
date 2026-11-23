#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace voicestudio::engine {

constexpr int32_t kSampleRateHz = 44100;
constexpr int32_t kZeroCrossScanSamples = 88;         // +/- 2 ms @ 44.1 kHz
constexpr size_t kPrefixTailSamples = 66150;          // 1.5 s @ 44.1 kHz
constexpr float kTargetIntegratedLufs = -16.0f;
constexpr float kStudioNoiseFloorDbfs = -58.0f;
constexpr size_t kPcmFileAlignmentBytes = 64;

int64_t FindNearestZeroCrossing(
    const float* pcm,
    int64_t total_samples,
    int64_t target_sample,
    int32_t scan_radius = kZeroCrossScanSamples);

void MasterClausePcm(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len,
    const float* room_tone,
    size_t room_tone_len,
    float target_lufs = kTargetIntegratedLufs,
    float noise_floor_dbfs = kStudioNoiseFloorDbfs);

std::vector<float> ExtractAcousticPrefixTail(
    const float* pcm,
    size_t total_samples,
    size_t tail_samples = kPrefixTailSamples);

bool WriteAlignedPcmFile(
    const std::string& path,
    const float* pcm,
    size_t total_samples);

}  // namespace voicestudio::engine
