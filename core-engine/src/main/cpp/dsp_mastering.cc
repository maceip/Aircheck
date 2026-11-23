#include "dsp_mastering.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define VOICESTUDIO_HAS_NEON 1
#else
#define VOICESTUDIO_HAS_NEON 0
#endif

namespace voicestudio::engine {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr float kDeltaEpsilon = 1e-7f;
constexpr float kSilenceLufs = -120.0f;
constexpr size_t kDirectFirMaxTaps = 64;

struct BiquadCoeffs {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
};

struct BiquadState {
    double z1 = 0.0;
    double z2 = 0.0;

    inline double Process(double x, const BiquadCoeffs& c) {
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        return y;
    }
};

// ITU-R BS.1770-4 Stage 1 (acoustic head-effect high-shelf pre-filter) via exact bilinear transform.
BiquadCoeffs MakeKWeightingPreFilter(int32_t sample_rate_hz) {
    const double fs = static_cast<double>(std::max(8000, sample_rate_hz));
    const double f0 = 1681.974450955533;
    const double g_db = 3.999843853973347;
    const double q = 0.7071752369554196;

    const double k = std::tan(kPi * f0 / fs);
    const double vh = std::pow(10.0, g_db / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    const double k2 = k * k;
    const double a0 = 1.0 + k / q + k2;

    BiquadCoeffs c;
    c.b0 = (vh + vb * k / q + k2) / a0;
    c.b1 = 2.0 * (k2 - vh) / a0;
    c.b2 = (vh - vb * k / q + k2) / a0;
    c.a1 = 2.0 * (k2 - 1.0) / a0;
    c.a2 = (1.0 - k / q + k2) / a0;
    return c;
}

// ITU-R BS.1770-4 Stage 2 (RLB high-pass filter) via exact bilinear transform.
BiquadCoeffs MakeKWeightingRlbFilter(int32_t sample_rate_hz) {
    const double fs = static_cast<double>(std::max(8000, sample_rate_hz));
    const double f0 = 38.13547087602444;
    const double q = 0.5003270373238773;

    const double k = std::tan(kPi * f0 / fs);
    const double k2 = k * k;
    const double a0 = 1.0 + k / q + k2;

    BiquadCoeffs c;
    c.b0 = 1.0 / a0;
    c.b1 = -2.0 / a0;
    c.b2 = 1.0 / a0;
    c.a1 = 2.0 * (k2 - 1.0) / a0;
    c.a2 = (1.0 - k / q + k2) / a0;
    return c;
}

size_t NextPowerOfTwo(size_t v) {
    size_t p = 1;
    while (p < v) {
        p <<= 1;
    }
    return p;
}

// Iterative in-place Cooley-Tukey radix-2 FFT (separate real/imag arrays for SIMD friendliness).
void Radix2Fft(
    std::vector<float>& re,
    std::vector<float>& im,
    bool inverse) {
    const size_t n = re.size();
    if (n <= 1) {
        return;
    }

    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; (j & bit) != 0; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }

    const double sign = inverse ? 1.0 : -1.0;
    for (size_t len = 2; len <= n; len <<= 1) {
        const size_t half = len >> 1;
        const double angle = sign * (2.0 * kPi / static_cast<double>(len));
        const double w_step_re = std::cos(angle);
        const double w_step_im = std::sin(angle);

        for (size_t i = 0; i < n; i += len) {
            double w_re = 1.0;
            double w_im = 0.0;
            for (size_t j = 0; j < half; ++j) {
                const size_t idx0 = i + j;
                const size_t idx1 = idx0 + half;
                const float u_re = re[idx0];
                const float u_im = im[idx0];
                const float wr = static_cast<float>(w_re);
                const float wi = static_cast<float>(w_im);
                const float v_re = re[idx1] * wr - im[idx1] * wi;
                const float v_im = re[idx1] * wi + im[idx1] * wr;

                re[idx0] = u_re + v_re;
                im[idx0] = u_im + v_im;
                re[idx1] = u_re - v_re;
                im[idx1] = u_im - v_im;

                const double next_re = w_re * w_step_re - w_im * w_step_im;
                const double next_im = w_re * w_step_im + w_im * w_step_re;
                w_re = next_re;
                w_im = next_im;
            }
        }
    }

    if (inverse) {
        const float inv_n = 1.0f / static_cast<float>(n);
        size_t i = 0;
#if VOICESTUDIO_HAS_NEON
        const float32x4_t v_scale = vdupq_n_f32(inv_n);
        for (; i + 4 <= n; i += 4) {
            vst1q_f32(re.data() + i, vmulq_f32(vld1q_f32(re.data() + i), v_scale));
            vst1q_f32(im.data() + i, vmulq_f32(vld1q_f32(im.data() + i), v_scale));
        }
#endif
        for (; i < n; ++i) {
            re[i] *= inv_n;
            im[i] *= inv_n;
        }
    }
}

void ApplyGainAndSoftLimiter(
    float* pcm,
    size_t count,
    float linear_gain,
    float knee,
    float ceiling) {
    if (pcm == nullptr || count == 0) {
        return;
    }
    const float headroom = std::max(1e-5f, ceiling - knee);
    const float inv_headroom = 1.0f / headroom;

    size_t i = 0;
#if VOICESTUDIO_HAS_NEON
    const float32x4_t v_gain = vdupq_n_f32(linear_gain);
    const float32x4_t v_knee = vdupq_n_f32(knee);
    for (; i + 4 <= count; i += 4) {
        float32x4_t v = vmulq_f32(vld1q_f32(pcm + i), v_gain);
        const uint32x4_t within_knee = vcleq_f32(vabsq_f32(v), v_knee);
        const uint32x4_t needs_limit = vmvnq_u32(within_knee);
        // Fast path: 99%+ of finite samples at -16 LUFS stay strictly below the 0.90 knee.
        const uint64x2_t mask64 = vreinterpretq_u64_u32(needs_limit);
        if ((vgetq_lane_u64(mask64, 0) | vgetq_lane_u64(mask64, 1)) == 0ULL) {
            vst1q_f32(pcm + i, v);
        } else {
            float lane[4];
            vst1q_f32(lane, v);
            for (int k = 0; k < 4; ++k) {
                float x = lane[k];
                if (!std::isfinite(x)) {
                    x = 0.0f;
                }
                const float ax = std::fabs(x);
                if (ax > knee) {
                    const float limited = knee + headroom * std::tanh((ax - knee) * inv_headroom);
                    x = std::copysign(std::min(limited, ceiling), x);
                }
                lane[k] = x;
            }
            vst1q_f32(pcm + i, vld1q_f32(lane));
        }
    }
#endif
    for (; i < count; ++i) {
        float x = pcm[i] * linear_gain;
        if (!std::isfinite(x)) {
            x = 0.0f;
        }
        const float ax = std::fabs(x);
        if (ax > knee) {
            const float limited = knee + headroom * std::tanh((ax - knee) * inv_headroom);
            x = std::copysign(std::min(limited, ceiling), x);
        }
        pcm[i] = x;
    }
}

float ComputeRms(const float* pcm, size_t count) {
    if (pcm == nullptr || count == 0) {
        return 0.0f;
    }
    double sum_sq = 0.0;
    size_t i = 0;
#if VOICESTUDIO_HAS_NEON
    float32x4_t acc = vdupq_n_f32(0.0f);
    for (; i + 4 <= count; i += 4) {
        const float32x4_t v = vld1q_f32(pcm + i);
        acc = vmlaq_f32(acc, v, v);
        // Flush periodically to keep float32 accumulator precision tight.
        if ((i & 4095u) == 0u) {
            float lanes[4];
            vst1q_f32(lanes, acc);
            sum_sq += static_cast<double>(lanes[0]) + static_cast<double>(lanes[1]) +
                      static_cast<double>(lanes[2]) + static_cast<double>(lanes[3]);
            acc = vdupq_n_f32(0.0f);
        }
    }
    float lanes[4];
    vst1q_f32(lanes, acc);
    sum_sq += static_cast<double>(lanes[0]) + static_cast<double>(lanes[1]) +
              static_cast<double>(lanes[2]) + static_cast<double>(lanes[3]);
#endif
    for (; i < count; ++i) {
        const double s = static_cast<double>(pcm[i]);
        sum_sq += s * s;
    }
    return static_cast<float>(std::sqrt(sum_sq / static_cast<double>(count)));
}

void MixRoomToneBed(
    std::vector<float>& pcm,
    const float* room_tone,
    size_t room_tone_len,
    float noise_floor_dbfs) {
    if (pcm.empty() || room_tone == nullptr || room_tone_len == 0) {
        return;
    }
    const float source_rms = ComputeRms(room_tone, room_tone_len);
    if (source_rms <= 1e-12f || !std::isfinite(source_rms)) {
        return;
    }

    const float target_rms = std::pow(10.0f, noise_floor_dbfs / 20.0f);
    const float scale = target_rms / source_rms;

    // Crossfade length when wrapping the room tone buffer cyclically to prevent boundary clicks.
    const size_t xfade = std::min<size_t>(256, room_tone_len / 4);
    const size_t total = pcm.size();

    size_t dst_pos = 0;
    while (dst_pos < total) {
        const size_t remaining = total - dst_pos;
        const size_t chunk = std::min(remaining, room_tone_len);
        size_t j = 0;

#if VOICESTUDIO_HAS_NEON
        // Use fast NEON multiply-accumulate across the non-crossfaded bulk of the chunk.
        const size_t simd_end = (dst_pos > 0 && xfade > 0) ? 0 : chunk;
        if (simd_end > 0) {
            const float32x4_t v_scale = vdupq_n_f32(scale);
            for (; j + 4 <= simd_end; j += 4) {
                const float32x4_t base = vld1q_f32(pcm.data() + dst_pos + j);
                const float32x4_t tone = vld1q_f32(room_tone + j);
                vst1q_f32(pcm.data() + dst_pos + j, vmlaq_f32(base, tone, v_scale));
            }
        }
#endif
        for (; j < chunk; ++j) {
            float sample = room_tone[j];
            if (dst_pos > 0 && xfade > 0 && j < xfade) {
                const float t = static_cast<float>(j) / static_cast<float>(xfade);
                const float fade_in = std::sin(t * static_cast<float>(kPi * 0.5));
                const float fade_out = std::cos(t * static_cast<float>(kPi * 0.5));
                const float tail_sample = room_tone[room_tone_len - xfade + j];
                sample = sample * fade_in + tail_sample * fade_out;
            }
            pcm[dst_pos + j] += sample * scale;
        }
        dst_pos += chunk;
    }

    // Re-enforce strict true-peak ceiling [-0.99f, 0.99f] after summing noise floor bed.
    ApplyGainAndSoftLimiter(pcm.data(), pcm.size(), 1.0f, kSoftLimiterKnee, kTruePeakCeiling);
}

}  // namespace

bool IsUnitDeltaImpulse(const float* room_ir, size_t room_ir_len) {
    if (room_ir == nullptr || room_ir_len == 0) {
        return false;
    }
    if (std::fabs(room_ir[0] - 1.0f) > kDeltaEpsilon) {
        return false;
    }
    for (size_t i = 1; i < room_ir_len; ++i) {
        if (std::fabs(room_ir[i]) > kDeltaEpsilon) {
            return false;
        }
    }
    return true;
}

void ConvolveRoomIrDirectScalar(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output) {
    if (input == nullptr || output == nullptr || input_len == 0) {
        return;
    }
    if (room_ir == nullptr || room_ir_len == 0 || IsUnitDeltaImpulse(room_ir, room_ir_len)) {
        if (output != input) {
            std::memcpy(output, input, input_len * sizeof(float));
        }
        return;
    }

    std::fill_n(output, input_len, 0.0f);
    for (size_t k = 0; k < room_ir_len; ++k) {
        const float h = room_ir[k];
        if (h == 0.0f) {
            continue;
        }
        const size_t n_count = (k < input_len) ? (input_len - k) : 0;
        for (size_t n = 0; n < n_count; ++n) {
            output[n + k] += input[n] * h;
        }
    }
}

void ConvolveRoomIrDirectNeon(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output) {
    if (input == nullptr || output == nullptr || input_len == 0) {
        return;
    }
    if (room_ir == nullptr || room_ir_len == 0 || IsUnitDeltaImpulse(room_ir, room_ir_len)) {
        if (output != input) {
            std::memcpy(output, input, input_len * sizeof(float));
        }
        return;
    }

    std::fill_n(output, input_len, 0.0f);
    for (size_t k = 0; k < room_ir_len; ++k) {
        const float h = room_ir[k];
        if (h == 0.0f || k >= input_len) {
            continue;
        }
        const size_t n_count = input_len - k;
        float* dst = output + k;
        size_t n = 0;
#if VOICESTUDIO_HAS_NEON
        const float32x4_t v_h = vdupq_n_f32(h);
        for (; n + 8 <= n_count; n += 8) {
            const float32x4_t x0 = vld1q_f32(input + n);
            const float32x4_t x1 = vld1q_f32(input + n + 4);
            const float32x4_t y0 = vld1q_f32(dst + n);
            const float32x4_t y1 = vld1q_f32(dst + n + 4);
            vst1q_f32(dst + n, vmlaq_f32(y0, x0, v_h));
            vst1q_f32(dst + n + 4, vmlaq_f32(y1, x1, v_h));
        }
        for (; n + 4 <= n_count; n += 4) {
            const float32x4_t x0 = vld1q_f32(input + n);
            const float32x4_t y0 = vld1q_f32(dst + n);
            vst1q_f32(dst + n, vmlaq_f32(y0, x0, v_h));
        }
#endif
        for (; n < n_count; ++n) {
            dst[n] += input[n] * h;
        }
    }
}

void ConvolveRoomIrOverlapAdd(
    const float* input,
    size_t input_len,
    const float* room_ir,
    size_t room_ir_len,
    float* output) {
    if (input == nullptr || output == nullptr || input_len == 0) {
        return;
    }
    if (room_ir == nullptr || room_ir_len == 0 || IsUnitDeltaImpulse(room_ir, room_ir_len)) {
        if (output != input) {
            std::memcpy(output, input, input_len * sizeof(float));
        }
        return;
    }

    const size_t fft_size = std::max<size_t>(2048, NextPowerOfTwo(room_ir_len * 2));
    const size_t block_size = fft_size - room_ir_len + 1;

    std::vector<float> h_re(fft_size, 0.0f);
    std::vector<float> h_im(fft_size, 0.0f);
    std::memcpy(h_re.data(), room_ir, room_ir_len * sizeof(float));
    Radix2Fft(h_re, h_im, /*inverse=*/false);

    std::vector<float> x_re(fft_size, 0.0f);
    std::vector<float> x_im(fft_size, 0.0f);
    std::fill_n(output, input_len, 0.0f);

    for (size_t offset = 0; offset < input_len; offset += block_size) {
        const size_t cur_len = std::min(block_size, input_len - offset);
        std::memcpy(x_re.data(), input + offset, cur_len * sizeof(float));
        std::fill(x_re.begin() + static_cast<std::ptrdiff_t>(cur_len), x_re.end(), 0.0f);
        std::fill(x_im.begin(), x_im.end(), 0.0f);

        Radix2Fft(x_re, x_im, /*inverse=*/false);

        // Complex pointwise multiply: X = X * H (NEON vectorized).
        size_t i = 0;
#if VOICESTUDIO_HAS_NEON
        for (; i + 4 <= fft_size; i += 4) {
            const float32x4_t xr = vld1q_f32(x_re.data() + i);
            const float32x4_t xi = vld1q_f32(x_im.data() + i);
            const float32x4_t hr = vld1q_f32(h_re.data() + i);
            const float32x4_t hi = vld1q_f32(h_im.data() + i);
            const float32x4_t pr = vmlsq_f32(vmulq_f32(xr, hr), xi, hi);
            const float32x4_t pi = vmlaq_f32(vmulq_f32(xr, hi), xi, hr);
            vst1q_f32(x_re.data() + i, pr);
            vst1q_f32(x_im.data() + i, pi);
        }
#endif
        for (; i < fft_size; ++i) {
            const float xr = x_re[i];
            const float xi = x_im[i];
            x_re[i] = xr * h_re[i] - xi * h_im[i];
            x_im[i] = xr * h_im[i] + xi * h_re[i];
        }

        Radix2Fft(x_re, x_im, /*inverse=*/true);

        const size_t write_len = std::min(fft_size, input_len - offset);
        size_t w = 0;
#if VOICESTUDIO_HAS_NEON
        for (; w + 4 <= write_len; w += 4) {
            const float32x4_t prev = vld1q_f32(output + offset + w);
            const float32x4_t add = vld1q_f32(x_re.data() + w);
            vst1q_f32(output + offset + w, vaddq_f32(prev, add));
        }
#endif
        for (; w < write_len; ++w) {
            output[offset + w] += x_re[w];
        }
    }
}

void ConvolveRoomIr(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len) {
    if (pcm.empty() || room_ir == nullptr || room_ir_len == 0) {
        return;
    }
    if (IsUnitDeltaImpulse(room_ir, room_ir_len)) {
        // Exact clean pass-through ONLY for a unit delta impulse.
        return;
    }
    std::vector<float> convolved(pcm.size(), 0.0f);
    if (room_ir_len <= kDirectFirMaxTaps) {
        ConvolveRoomIrDirectNeon(pcm.data(), pcm.size(), room_ir, room_ir_len, convolved.data());
    } else {
        ConvolveRoomIrOverlapAdd(pcm.data(), pcm.size(), room_ir, room_ir_len, convolved.data());
    }
    pcm.swap(convolved);
}

float MeasureIntegratedLufs(
    const float* pcm,
    size_t total_samples,
    int32_t sample_rate_hz) {
    if (pcm == nullptr || total_samples == 0) {
        return kSilenceLufs;
    }

    const BiquadCoeffs pre = MakeKWeightingPreFilter(sample_rate_hz);
    const BiquadCoeffs rlb = MakeKWeightingRlbFilter(sample_rate_hz);
    BiquadState pre_state;
    BiquadState rlb_state;

    std::vector<double> k_sq(total_samples);
    double full_sum_sq = 0.0;
    for (size_t i = 0; i < total_samples; ++i) {
        const double x = std::isfinite(pcm[i]) ? static_cast<double>(pcm[i]) : 0.0;
        const double y1 = pre_state.Process(x, pre);
        const double y2 = rlb_state.Process(y1, rlb);
        const double sq = y2 * y2;
        k_sq[i] = sq;
        full_sum_sq += sq;
    }

    const size_t block_len = static_cast<size_t>(std::max(1, (sample_rate_hz * 4) / 10));  // 400 ms
    const size_t hop_len = static_cast<size_t>(std::max(1, sample_rate_hz / 10));          // 100 ms

    if (total_samples < block_len) {
        const double mean_sq = full_sum_sq / static_cast<double>(total_samples);
        if (mean_sq <= 1e-15) {
            return kSilenceLufs;
        }
        return static_cast<float>(-0.691 + 10.0 * std::log10(mean_sq));
    }

    // Prefix sums allow O(1) exact 400 ms block mean-square calculation.
    std::vector<double> prefix(total_samples + 1, 0.0);
    for (size_t i = 0; i < total_samples; ++i) {
        prefix[i + 1] = prefix[i] + k_sq[i];
    }

    std::vector<double> block_energies;
    block_energies.reserve((total_samples - block_len) / hop_len + 1);
    for (size_t start = 0; start + block_len <= total_samples; start += hop_len) {
        const double z = (prefix[start + block_len] - prefix[start]) / static_cast<double>(block_len);
        block_energies.push_back(z);
    }

    // Stage 1: Absolute gate at -70.0 LUFS.
    double abs_sum = 0.0;
    size_t abs_count = 0;
    for (double z : block_energies) {
        if (z <= 1e-15) {
            continue;
        }
        const double l_j = -0.691 + 10.0 * std::log10(z);
        if (l_j > -70.0) {
            abs_sum += z;
            ++abs_count;
        }
    }
    if (abs_count == 0) {
        return kSilenceLufs;
    }

    // Stage 2: Relative gate at (ungated_above_abs_gate - 10.0 LU).
    const double z_abs_mean = abs_sum / static_cast<double>(abs_count);
    const double relative_threshold = -0.691 + 10.0 * std::log10(z_abs_mean) - 10.0;

    double rel_sum = 0.0;
    size_t rel_count = 0;
    for (double z : block_energies) {
        if (z <= 1e-15) {
            continue;
        }
        const double l_j = -0.691 + 10.0 * std::log10(z);
        if (l_j > -70.0 && l_j > relative_threshold) {
            rel_sum += z;
            ++rel_count;
        }
    }
    if (rel_count == 0) {
        return static_cast<float>(-0.691 + 10.0 * std::log10(z_abs_mean));
    }
    const double z_rel_mean = rel_sum / static_cast<double>(rel_count);
    return static_cast<float>(-0.691 + 10.0 * std::log10(z_rel_mean));
}

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
    float best_sign_change_abs = std::numeric_limits<float>::infinity();
    int64_t best_sign_change_dist = std::numeric_limits<int64_t>::max();

    int64_t best_overall_idx = clamped_target;
    float best_overall_abs = std::isfinite(pcm[clamped_target])
                                 ? std::fabs(pcm[clamped_target])
                                 : std::numeric_limits<float>::infinity();
    int64_t best_overall_dist = 0;

    for (int64_t i = lo; i <= hi; ++i) {
        const float curr = pcm[i];
        if (!std::isfinite(curr)) {
            continue;
        }
        const float abs_val = std::fabs(curr);
        const int64_t dist = std::llabs(i - clamped_target);
        if (abs_val < best_overall_abs - 1e-7f ||
            (std::fabs(abs_val - best_overall_abs) <= 1e-7f && dist < best_overall_dist)) {
            best_overall_abs = abs_val;
            best_overall_idx = i;
            best_overall_dist = dist;
        }

        if (i > lo) {
            const float prev = pcm[i - 1];
            if (!std::isfinite(prev)) {
                continue;
            }
            const bool crosses_zero =
                (prev <= 0.0f && curr >= 0.0f) || (prev >= 0.0f && curr <= 0.0f);
            if (crosses_zero) {
                const float prev_abs = std::fabs(prev);
                const int64_t candidate_idx = (prev_abs <= abs_val) ? (i - 1) : i;
                const float candidate_abs = std::fabs(pcm[candidate_idx]);
                const int64_t candidate_dist = std::llabs(candidate_idx - clamped_target);
                const bool strictly_quieter = candidate_abs < best_sign_change_abs - 1e-7f;
                const bool tie_but_closer =
                    std::fabs(candidate_abs - best_sign_change_abs) <= 1e-7f &&
                    candidate_dist < best_sign_change_dist;
                if (best_sign_change_idx < 0 || strictly_quieter || tie_but_closer) {
                    best_sign_change_idx = candidate_idx;
                    best_sign_change_abs = candidate_abs;
                    best_sign_change_dist = candidate_dist;
                }
            }
        }
    }

    return (best_sign_change_idx >= 0) ? best_sign_change_idx : best_overall_idx;
}

int64_t FindNearestZeroCrossingInFile(
    const std::string& pcm_path,
    int64_t target_sample,
    int32_t scan_radius) {
    if (pcm_path.empty()) {
        return -1;
    }
    std::ifstream in(pcm_path, std::ios::binary | std::ios::ate);
    if (!in.good()) {
        return -1;
    }
    const std::streamoff file_bytes = in.tellg();
    if (file_bytes < static_cast<std::streamoff>(sizeof(float))) {
        return -1;
    }
    const int64_t total_samples = static_cast<int64_t>(file_bytes / static_cast<std::streamoff>(sizeof(float)));
    const int64_t clamped_target = std::clamp<int64_t>(target_sample, 0, total_samples - 1);
    const int64_t radius = std::max<int32_t>(0, scan_radius);
    const int64_t lo = std::max<int64_t>(0, clamped_target - radius);
    const int64_t hi = std::min<int64_t>(total_samples - 1, clamped_target + radius);
    const int64_t window_len = hi - lo + 1;

    std::vector<float> window(static_cast<size_t>(window_len));
    in.seekg(static_cast<std::streamoff>(lo * static_cast<int64_t>(sizeof(float))), std::ios::beg);
    in.read(
        reinterpret_cast<char*>(window.data()),
        static_cast<std::streamsize>(window_len * static_cast<int64_t>(sizeof(float))));
    if (!in.good()) {
        return -1;
    }

    const int64_t local_idx =
        FindNearestZeroCrossing(window.data(), window_len, clamped_target - lo, scan_radius);
    return lo + local_idx;
}

void MasterClausePcm(
    std::vector<float>& pcm,
    const float* room_ir,
    size_t room_ir_len,
    const float* room_tone,
    size_t room_tone_len,
    float target_lufs,
    float noise_floor_dbfs) {
    if (pcm.empty()) {
        return;
    }

    // 1. 12ms Studio Room Impulse Response convolution (clean pass-through ONLY for unit delta).
    ConvolveRoomIr(pcm, room_ir, room_ir_len);

    // 2. ITU-R BS.1770-4 K-weighted integrated loudness normalization to target_lufs (-16.0 LUFS)
    //    with soft true-peak limiter keeping every sample strictly in [-0.99f, 0.99f].
    for (int pass = 0; pass < 2; ++pass) {
        const float measured_lufs = MeasureIntegratedLufs(pcm.data(), pcm.size(), kSampleRateHz);
        if (measured_lufs <= -100.0f) {
            break;
        }
        const float delta_db = target_lufs - measured_lufs;
        if (pass > 0 && std::fabs(delta_db) <= 0.15f) {
            break;
        }
        const float clamped_db = std::clamp(delta_db, -36.0f, 36.0f);
        const float linear_gain = std::pow(10.0f, clamped_db / 20.0f);
        ApplyGainAndSoftLimiter(pcm.data(), pcm.size(), linear_gain, kSoftLimiterKnee, kTruePeakCeiling);
        if (std::fabs(delta_db) <= 0.15f) {
            break;
        }
    }

    // 3. -58 dBFS studio noise floor bed mixing (when room tone buffer is provided).
    MixRoomToneBed(pcm, room_tone, room_tone_len, noise_floor_dbfs);
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
    const std::string tmp_path = path + ".tmp";
    {
        std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
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
        out.flush();
        if (!out.good()) {
            std::remove(tmp_path.c_str());
            return false;
        }
    }
    if (std::rename(tmp_path.c_str(), path.c_str()) != 0) {
        std::remove(tmp_path.c_str());
        return false;
    }
    return true;
}

bool ReadPcmFile44k(
    const std::string& path,
    std::vector<float>& out_pcm) {
    out_pcm.clear();
    if (path.empty()) {
        return false;
    }
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in.good()) {
        return false;
    }
    const std::streamoff bytes = in.tellg();
    if (bytes < 0) {
        return false;
    }
    const size_t sample_count = static_cast<size_t>(bytes) / sizeof(float);
    out_pcm.resize(sample_count);
    if (sample_count == 0) {
        return true;
    }
    in.seekg(0, std::ios::beg);
    in.read(
        reinterpret_cast<char*>(out_pcm.data()),
        static_cast<std::streamsize>(sample_count * sizeof(float)));
    if (!in.good()) {
        out_pcm.clear();
        return false;
    }
    return true;
}

}  // namespace voicestudio::engine
