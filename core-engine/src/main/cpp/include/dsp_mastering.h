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
constexpr float kTruePeakCeiling = 0.99f;
constexpr float kSoftLimiterKnee = 0.90f;
constexpr size_t kPcmFileAlignmentBytes = 64;

/**
 * Returns true if the supplied impulse response is a unit delta ([1.0f, 0, ...]).
 */
bool IsUnitDeltaImpulse(const float* room_ir, size_t room_ir_len);

/**
 * Direct FIR linear convolution (scalar fallback, deterministic reference).
 * Output length matches input length (causal linear convolution y[n] = sum_k x[n-k]*h[k]).
 */
void ConvolveRoomIrDirectScalar(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output);

/**
 * Direct FIR linear convolution using ARM NEON when available (falls back to scalar otherwise).
 */
void ConvolveRoomIrDirectNeon(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output);

/**
 * Fast overlap-add radix-2 FFT linear convolution for longer studio room IRs (e.g. 12 ms / 529 taps).
 */
void ConvolveRoomIrOverlapAdd(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output);

/**
 * Dispatches clean pass-through for unit delta, NEON direct FIR for short IRs,
 * and overlap-add FFT for longer room IRs.
 */
void ConvolveRoomIr(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len);

/**
 * ITU-R BS.1770-4 K-weighted integrated loudness measurement (LUFS) for mono Float32 PCM.
 * Returns <= -120.0f for digital silence.
 */
float MeasureIntegratedLufs(
    const float* pcm,
    size_t total_samples,
    int32_t sample_rate_hz = kSampleRateHz);

/**
 * Scans +/- scan_radius samples around target_sample for a zero-crossing sign-change
 * transition with minimum |pcm[i]| (ties broken by proximity to target_sample).
 */
int64_t FindNearestZeroCrossing(
    const float* pcm,
    int64_t total_samples,
    int64_t target_sample,
    int32_t scan_radius = kZeroCrossScanSamples);

/**
 * Scans +/- scan_radius samples directly from a headerless 44.1 kHz Float32 PCM file.
 * Returns -1 if the file cannot be opened or is empty.
 */
int64_t FindNearestZeroCrossingInFile(
    const std::string& pcm_path,
    int64_t target_sample,
    int32_t scan_radius = kZeroCrossScanSamples);

/**
 * Deterministic 5ms C++20 NEON-accelerated mastering pipeline:
 *   1. 12ms Studio Room IR convolution (pass-through ONLY if impulse is a unit delta)
 *   2. ITU-R BS.1770-4 K-weighted -16.0 LUFS normalization + soft true-peak limiter [-0.99f, 0.99f]
 *   3. -58 dBFS studio room tone noise floor bed mixing (when room_tone buffer is provided)
 */
void MasterClausePcm(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len,
    const float* room_tone,
    size_t room_tone_len,
    float target_lufs = kTargetIntegratedLufs,
    float noise_floor_dbfs = kStudioNoiseFloorDbfs);

/**
 * Extracts the exact trailing 66,150 samples (1.5 s @ 44.1 kHz) for acoustic prefix chaining.
 */
std::vector<float> ExtractAcousticPrefixTail(
    const float* pcm,
    size_t total_samples,
    size_t tail_samples = kPrefixTailSamples);

inline std::vector<float> ExtractAcousticPrefixTail(
    const std::vector<float>& pcm,
    size_t tail_samples = kPrefixTailSamples) {
    return ExtractAcousticPrefixTail(pcm.data(), pcm.size(), tail_samples);
}

/**
 * Atomically writes headerless 64-byte-aligned 44.1 kHz Float32 mono PCM to disk.
 */
bool WriteAlignedPcmFile(
    const std::string& path,
    const float* pcm,
    size_t total_samples);

inline bool WriteAlignedPcm44k(
    const std::string& path,
    const float* pcm,
    size_t total_samples) {
    return WriteAlignedPcmFile(path, pcm, total_samples);
}

inline bool WriteAlignedPcm44k(
    const std::string& path,
    const std::vector<float>& pcm) {
    return WriteAlignedPcmFile(path, pcm.data(), pcm.size());
}

/**
 * Reads a headerless 44.1 kHz Float32 mono PCM file into memory.
 */
bool ReadPcmFile44k(
    const std::string& path,
    std::vector<float>& out_pcm);

}  // namespace voicestudio::engine
