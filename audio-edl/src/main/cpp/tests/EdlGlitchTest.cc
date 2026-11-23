#include "edl_player.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <random>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

using voicestudio::edl::AudioSlice;
using voicestudio::edl::EdlPlayer;
using voicestudio::edl::EdlRenderer;
using voicestudio::edl::EdlSnapshot;
using voicestudio::edl::kCrossfadeSamples;
using voicestudio::edl::kSampleRateHz;
using voicestudio::edl::SliceDescriptor;

constexpr float kTwoPi = 6.28318530717958647692f;
constexpr float kMaxAllowedJump = 0.02f;
constexpr double kMaxAllowedSwapMs = 0.5;
constexpr int32_t kBaseGapSamples = 3969;  // 90 ms @ 44.1 kHz

struct TempDirGuard {
    std::filesystem::path dir;
    TempDirGuard() {
        dir = std::filesystem::temp_directory_path() /
              ("voicestudio_edl_glitch_test_" + std::to_string(::getpid()));
        std::filesystem::create_directories(dir);
    }
    ~TempDirGuard() {
        std::error_code ec;
        std::filesystem::remove_all(dir, ec);
    }
};

bool WriteAlignedPcmFile(const std::filesystem::path& path, const std::vector<float>& samples) {
    // Pad sample count to a multiple of 16 floats (64 bytes) to mirror Stage C 64-byte alignment.
    std::vector<float> padded = samples;
    while ((padded.size() * sizeof(float)) % 64 != 0) {
        padded.push_back(0.0f);
    }
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out.good()) {
        return false;
    }
    out.write(
        reinterpret_cast<const char*>(padded.data()),
        static_cast<std::streamsize>(padded.size() * sizeof(float)));
    return out.good();
}

// Synthesizes a smooth -58 dBFS band-limited studio room-tone bed (RMS ~0.001259f).
std::vector<float> GenerateStudioRoomTone(int64_t num_samples) {
    std::vector<float> buf(static_cast<size_t>(num_samples), 0.0f);
    std::mt19937 rng(1337u);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);

    // Simple 1-pole low-pass pink/brownish studio HVAC tone to keep natural slew smooth.
    float lp = 0.0f;
    double sum_sq = 0.0;
    for (int64_t i = 0; i < num_samples; ++i) {
        const float white = dist(rng);
        lp = 0.92f * lp + 0.08f * white;
        const float hum = 0.15f * std::sin(kTwoPi * 60.0f * static_cast<float>(i) / kSampleRateHz);
        buf[static_cast<size_t>(i)] = lp + hum;
        sum_sq += static_cast<double>(buf[static_cast<size_t>(i)]) * buf[static_cast<size_t>(i)];
    }

    const double rms = std::sqrt(sum_sq / static_cast<double>(num_samples));
    const double target_rms = std::pow(10.0, -58.0 / 20.0);  // ~0.0012589
    const float gain = (rms > 1e-12) ? static_cast<float>(target_rms / rms) : 0.0f;
    for (float& s : buf) {
        s *= gain;
    }
    return buf;
}

// Synthesizes a speech-band harmonic signal normalized around -16 LUFS (~0.36 peak,
// natural slew < 0.011 so any unfaded splice or take swap produces a >0.30 jump).
std::vector<float> GenerateClauseTakePcm(
    int64_t num_samples,
    float f0_hz,
    float phase_rad,
    float polarity) {
    std::vector<float> buf(static_cast<size_t>(num_samples), 0.0f);
    for (int64_t i = 0; i < num_samples; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kSampleRateHz);
        const float s1 = 0.28f * std::sin(kTwoPi * f0_hz * t + phase_rad);
        const float s2 = 0.10f * std::sin(kTwoPi * (2.0f * f0_hz) * t + phase_rad * 0.5f);
        buf[static_cast<size_t>(i)] = polarity * (s1 + s2);
    }
    return buf;
}

int64_t FindZeroCrossingNear(const std::vector<float>& pcm, int64_t target, int64_t radius = 88) {
    const int64_t lo = std::max<int64_t>(1, target - radius);
    const int64_t hi = std::min<int64_t>(static_cast<int64_t>(pcm.size()) - 1, target + radius);
    int64_t best_idx = std::clamp<int64_t>(target, 0, static_cast<int64_t>(pcm.size()) - 1);
    float best_abs = std::numeric_limits<float>::max();
    for (int64_t i = lo; i <= hi; ++i) {
        const bool sign_change = (pcm[static_cast<size_t>(i - 1)] <= 0.0f && pcm[static_cast<size_t>(i)] >= 0.0f) ||
                                 (pcm[static_cast<size_t>(i - 1)] >= 0.0f && pcm[static_cast<size_t>(i)] <= 0.0f);
        if (sign_change) {
            const float mag = std::fabs(pcm[static_cast<size_t>(i)]);
            if (mag < best_abs) {
                best_abs = mag;
                best_idx = i;
            }
        }
    }
    return best_idx;
}

struct DerivativeStats {
    float max_jump = 0.0f;
    size_t max_jump_index = 0;
    bool all_finite_in_range = true;
};

DerivativeStats AnalyzeDerivative(const std::vector<float>& samples) {
    DerivativeStats stats;
    for (size_t i = 0; i < samples.size(); ++i) {
        if (!std::isfinite(samples[i]) || samples[i] < -1.0f || samples[i] > 1.0f) {
            stats.all_finite_in_range = false;
        }
        if (i > 0) {
            const float diff = std::fabs(samples[i] - samples[i - 1]);
            if (diff > stats.max_jump) {
                stats.max_jump = diff;
                stats.max_jump_index = i;
            }
        }
    }
    return stats;
}

bool TestMissingRoomToneFailsHonestly() {
    EdlPlayer player;
    std::string err;
    if (player.Init("/nonexistent/voicestudio/room_tone_44k.pcm", &err)) {
        std::cerr << "[FAIL] Init unexpectedly succeeded for missing room tone file\n";
        return false;
    }
    if (err.empty()) {
        std::cerr << "[FAIL] Init returned empty error string for missing room tone file\n";
        return false;
    }
    if (player.Start(&err)) {
        std::cerr << "[FAIL] Start unexpectedly succeeded without Init\n";
        return false;
    }
    std::cout << "[PASS] TestMissingRoomToneFailsHonestly: " << err << "\n";
    return true;
}

bool TestBoundaryCrossfadesAndRoomTone(
    const std::filesystem::path& room_tone_path,
    const std::filesystem::path& dir) {
    // Two high-contrast opposing-polarity slices to prove 660-sample cosine crossfades eliminate
    // clicks across gap->slice, slice->gap, and direct gap==0 slice->slice boundaries.
    const auto pcm_a = GenerateClauseTakePcm(22050, 140.0f, 0.3f, +1.0f);
    const auto pcm_b = GenerateClauseTakePcm(22050, 175.0f, 1.9f, -1.0f);
    const auto path_a = dir / "boundary_a.pcm";
    const auto path_b = dir / "boundary_b.pcm";
    if (!WriteAlignedPcmFile(path_a, pcm_a) || !WriteAlignedPcmFile(path_b, pcm_b)) {
        std::cerr << "[FAIL] Could not write boundary test PCM files\n";
        return false;
    }

    EdlPlayer player;
    std::string err;
    if (!player.Init(room_tone_path.string(), &err)) {
        std::cerr << "[FAIL] Init failed: " << err << "\n";
        return false;
    }

    // Scenario A: Slice A -> 3969-sample gap (room tone) -> Slice B -> gap == 0 direct splice -> Slice A
    // Intentionally place zero_cross_in/out AWAY from exact zeros to prove equal-power cosine x-fade works
    // even on worst-case non-zero sample boundaries.
    std::vector<SliceDescriptor> descs = {
        {path_a.string(), 1000, 15000, kBaseGapSamples},
        {path_b.string(), 1200, 16000, 0},       // Direct slice-to-slice crossfade with pre/post roll!
        {path_a.string(), 1200, 16000, 2000},
    };
    if (!player.UpdateEdlFromDescriptors(descs, 1.0f, &err)) {
        std::cerr << "[FAIL] UpdateEdlFromDescriptors failed: " << err << "\n";
        return false;
    }
    if (!player.Start(&err)) {
        std::cerr << "[FAIL] Start failed: " << err << "\n";
        return false;
    }

    const int64_t expected_timeline = (15000 - 1000) + kBaseGapSamples + (16000 - 1200) + 0 + (16000 - 1200) + 2000;
    std::vector<float> rendered;
    rendered.reserve(static_cast<size_t>(expected_timeline + 4096));
    std::vector<float> block(256, 0.0f);

    // Render full timeline + pause fade-out + resume fade-in.
    while (static_cast<int64_t>(rendered.size()) < expected_timeline + 1024) {
        player.RenderFrames(block.data(), static_cast<int32_t>(block.size()));
        rendered.insert(rendered.end(), block.begin(), block.end());
    }
    player.Pause();
    for (int i = 0; i < 6; ++i) {
        player.RenderFrames(block.data(), static_cast<int32_t>(block.size()));
        rendered.insert(rendered.end(), block.begin(), block.end());
    }

    // Verify room tone is genuinely mixed inside the gap [14000, 14000 + 3969).
    double gap_sum_sq = 0.0;
    const size_t gap_start = 14000;
    const size_t gap_end = gap_start + static_cast<size_t>(kBaseGapSamples);
    for (size_t i = gap_start; i < gap_end; ++i) {
        gap_sum_sq += static_cast<double>(rendered[i]) * rendered[i];
    }
    const double gap_rms = std::sqrt(gap_sum_sq / static_cast<double>(gap_end - gap_start));
    const double gap_dbfs = 20.0 * std::log10(std::max(gap_rms, 1e-12));
    if (gap_dbfs < -65.0 || gap_dbfs > -50.0) {
        std::cerr << "[FAIL] Expected ~-58 dBFS room tone in gap, measured " << gap_dbfs << " dBFS\n";
        return false;
    }

    const DerivativeStats stats = AnalyzeDerivative(rendered);
    if (!stats.all_finite_in_range || stats.max_jump >= kMaxAllowedJump) {
        std::cerr << "[FAIL] TestBoundaryCrossfadesAndRoomTone max_jump=" << stats.max_jump
                  << " at sample " << stats.max_jump_index
                  << " (limit < " << kMaxAllowedJump << ")\n";
        return false;
    }

    if (player.GetXRunCount() != 0) {
        std::cerr << "[FAIL] Non-zero xrun count: " << player.GetXRunCount() << "\n";
        return false;
    }

    player.Close();
    std::cout << "[PASS] TestBoundaryCrossfadesAndRoomTone: gap_room_tone=" << gap_dbfs
              << " dBFS, max_jump=" << stats.max_jump << " (< " << kMaxAllowedJump << ")\n";
    return true;
}

bool TestGate3MultiThreaded50SwapsAndGapSweep(
    const std::filesystem::path& room_tone_path,
    const std::filesystem::path& dir) {
    constexpr int kNumClauses = 5;
    constexpr int kTakesPerClause = 3;
    constexpr int kNumMutations = 50;
    constexpr int64_t kClauseTotalSamples = 44100;  // 1.0s @ 44.1 kHz per take

    struct TakeInfo {
        std::string path;
        int64_t zc_in = 0;
        int64_t zc_out = 0;
    };

    std::vector<std::vector<TakeInfo>> takes(kNumClauses, std::vector<TakeInfo>(kTakesPerClause));
    std::vector<std::string> all_paths;
    all_paths.reserve(kNumClauses * kTakesPerClause);

    for (int c = 0; c < kNumClauses; ++c) {
        for (int t = 0; t < kTakesPerClause; ++t) {
            const float f0 = 110.0f + 16.0f * static_cast<float>(c) + 11.0f * static_cast<float>(t);
            const float phase = 0.7f * static_cast<float>(c) + 2.1f * static_cast<float>(t);
            const float polarity = ((c + t) % 2 == 0) ? 1.0f : -1.0f;
            const auto pcm = GenerateClauseTakePcm(kClauseTotalSamples, f0, phase, polarity);
            const auto file_path = dir / ("clause_" + std::to_string(c) + "_take_" + std::to_string(t) + ".pcm");
            if (!WriteAlignedPcmFile(file_path, pcm)) {
                std::cerr << "[FAIL] Failed writing take PCM file: " << file_path << "\n";
                return false;
            }
            TakeInfo info;
            info.path = file_path.string();
            info.zc_in = FindZeroCrossingNear(pcm, 1500 + t * 250);
            info.zc_out = FindZeroCrossingNear(pcm, kClauseTotalSamples - 1500 - t * 250);
            takes[c][t] = info;
            all_paths.push_back(info.path);
        }
    }

    EdlPlayer player;
    std::string err;
    if (!player.Init(room_tone_path.string(), &err)) {
        std::cerr << "[FAIL] EdlPlayer::Init failed: " << err << "\n";
        return false;
    }
    if (!player.PreloadFiles(all_paths, &err)) {
        std::cerr << "[FAIL] EdlPlayer::PreloadFiles failed: " << err << "\n";
        return false;
    }

    auto make_descriptors = [&](int mutation_idx, float gap_scale) {
        std::vector<SliceDescriptor> descs;
        descs.reserve(kNumClauses);
        for (int c = 0; c < kNumClauses; ++c) {
            const int take_idx = (c + mutation_idx) % kTakesPerClause;
            const auto& info = takes[c][take_idx];
            SliceDescriptor d;
            d.pcm_file_path = info.path;
            d.zero_cross_in_sample = info.zc_in;
            d.zero_cross_out_sample = info.zc_out;
            d.gap_after_samples = static_cast<int32_t>(static_cast<float>(kBaseGapSamples) * gap_scale);
            descs.push_back(std::move(d));
        }
        return descs;
    };

    // Publish initial snapshot before starting playback.
    if (!player.UpdateEdlFromDescriptors(make_descriptors(0, 1.0f), 1.0f, &err)) {
        std::cerr << "[FAIL] Initial UpdateEdlFromDescriptors failed: " << err << "\n";
        return false;
    }
    if (!player.Start(&err)) {
        std::cerr << "[FAIL] EdlPlayer::Start failed: " << err << "\n";
        return false;
    }

    // Dedicated real-time audio render thread simulating continuous 192-frame (~4.35 ms) Oboe callbacks.
    constexpr int32_t kBurstFrames = 192;
    std::atomic<bool> stop_audio{false};
    std::atomic<uint64_t> callbacks_rendered{0};
    std::vector<float> rendered_audio;
    rendered_audio.reserve(44100 * 8);

    std::thread audio_thread([&]() {
        std::array<float, kBurstFrames> burst{};
        while (!stop_audio.load(std::memory_order_acquire)) {
            player.RenderFrames(burst.data(), kBurstFrames);
            rendered_audio.insert(rendered_audio.end(), burst.begin(), burst.end());
            callbacks_rendered.fetch_add(1, std::memory_order_release);
            // Brief yield so mutator thread interleaves cleanly across all 5 clauses.
            std::this_thread::yield();
        }
    });

    // Wait for audio thread to begin rendering.
    while (callbacks_rendered.load(std::memory_order_acquire) < 5) {
        std::this_thread::yield();
    }

    double max_atomic_swap_ms = 0.0;
    double sum_atomic_swap_ms = 0.0;
    double max_full_swap_ms = 0.0;
    double sum_full_swap_ms = 0.0;

    for (int i = 1; i <= kNumMutations; ++i) {
        // Sweep globalGapScale smoothly from 1.0f -> 0.25f across 50 mutations while swapping takes.
        const float progress = static_cast<float>(i - 1) / static_cast<float>(kNumMutations - 1);
        const float gap_scale = 1.0f - 0.75f * progress;
        const auto descs = make_descriptors(i, gap_scale);

        const auto full_t0 = std::chrono::steady_clock::now();
        auto snap = player.BuildSnapshot(descs, gap_scale, &err);
        if (!snap) {
            stop_audio.store(true, std::memory_order_release);
            audio_thread.join();
            std::cerr << "[FAIL] BuildSnapshot failed on mutation " << i << ": " << err << "\n";
            return false;
        }

        const auto atomic_t0 = std::chrono::steady_clock::now();
        player.UpdateEdlSnapshot(snap.release());
        const auto atomic_t1 = std::chrono::steady_clock::now();

        const double atomic_ms =
            std::chrono::duration<double, std::milli>(atomic_t1 - atomic_t0).count();
        const double full_ms =
            std::chrono::duration<double, std::milli>(atomic_t1 - full_t0).count();

        max_atomic_swap_ms = std::max(max_atomic_swap_ms, atomic_ms);
        sum_atomic_swap_ms += atomic_ms;
        max_full_swap_ms = std::max(max_full_swap_ms, full_ms);
        sum_full_swap_ms += full_ms;

        // Exercise live mid-playback seeks on mutations 25 and 45 as well.
        if (i == 25) {
            player.SeekToSample(90000);
        } else if (i == 45) {
            player.SeekToSample(170000);
        }

        // Mix rapid overlapping-crossfade swaps (1 callback = 4.35 ms < 15 ms x-fade) with
        // longer traversal intervals (~32 callbacks = 6,144 samples) so all 5 clauses & 4 gaps are traversed.
        const uint64_t step_callbacks = (i % 4 == 0) ? 1 : 32;
        const uint64_t target_cb = callbacks_rendered.load(std::memory_order_acquire) + step_callbacks;
        while (callbacks_rendered.load(std::memory_order_acquire) < target_cb) {
            std::this_thread::yield();
        }
    }

    // Render a bit past the final mutation so final crossfade completes, then stop audio thread.
    const uint64_t tail_cb = callbacks_rendered.load(std::memory_order_acquire) + 20;
    while (callbacks_rendered.load(std::memory_order_acquire) < tail_cb) {
        std::this_thread::yield();
    }

    stop_audio.store(true, std::memory_order_release);
    audio_thread.join();

    const int32_t xruns = player.GetXRunCount();
    const size_t pending_before_close = player.renderer().ReclaimRetired();
    const DerivativeStats stats = AnalyzeDerivative(rendered_audio);

    const double avg_atomic_swap_ms = sum_atomic_swap_ms / static_cast<double>(kNumMutations);
    const double avg_full_swap_ms = sum_full_swap_ms / static_cast<double>(kNumMutations);

    std::cout << "[INFO] Gate 3 Stress Metrics:\n"
              << "  mutations                = " << kNumMutations << " (globalGapScale 1.00 -> 0.25 + take swaps)\n"
              << "  callbacks_rendered       = " << callbacks_rendered.load() << "\n"
              << "  total_samples_rendered   = " << rendered_audio.size() << "\n"
              << "  max_first_derivative     = " << stats.max_jump << " (at sample " << stats.max_jump_index << ", limit < " << kMaxAllowedJump << ")\n"
              << "  atomic_swap_ms (avg/max) = " << avg_atomic_swap_ms << " / " << max_atomic_swap_ms << " ms (limit < " << kMaxAllowedSwapMs << " ms)\n"
              << "  full_swap_ms   (avg/max) = " << avg_full_swap_ms << " / " << max_full_swap_ms << " ms (limit < " << kMaxAllowedSwapMs << " ms)\n"
              << "  xrun_count               = " << xruns << "\n"
              << "  pending_retired_after    = " << pending_before_close << "\n";

    player.Close();
    const size_t remaining_cached = player.mmap_registry().CachedFileCount();
    const size_t remaining_retired = player.renderer().PendingRetiredCount();

    if (!stats.all_finite_in_range) {
        std::cerr << "[FAIL] Non-finite or out-of-range sample detected!\n";
        return false;
    }
    if (stats.max_jump >= kMaxAllowedJump) {
        std::cerr << "[FAIL] Max first-derivative jump " << stats.max_jump
                  << " >= " << kMaxAllowedJump << " at sample " << stats.max_jump_index << "\n";
        return false;
    }
    if (max_atomic_swap_ms >= kMaxAllowedSwapMs) {
        std::cerr << "[FAIL] Max atomic swap latency " << max_atomic_swap_ms
                  << " ms >= " << kMaxAllowedSwapMs << " ms\n";
        return false;
    }
    if (max_full_swap_ms >= kMaxAllowedSwapMs) {
        std::cerr << "[FAIL] Max full cached swap latency " << max_full_swap_ms
                  << " ms >= " << kMaxAllowedSwapMs << " ms\n";
        return false;
    }
    if (xruns != 0) {
        std::cerr << "[FAIL] Expected 0 xruns, got " << xruns << "\n";
        return false;
    }
    if (pending_before_close != 0) {
        std::cerr << "[FAIL] Expected 0 pending retired snapshots after audio thread settled, got "
                  << pending_before_close << "\n";
        return false;
    }
    if (remaining_cached != 0 || remaining_retired != 0) {
        std::cerr << "[FAIL] Resource leak after Close(): cached=" << remaining_cached
                  << " retired=" << remaining_retired << "\n";
        return false;
    }

    std::cout << "[PASS] TestGate3MultiThreaded50SwapsAndGapSweep\n";
    return true;
}

}  // namespace

int main() {
    TempDirGuard tmp;
    const auto room_tone_path = tmp.dir / "room_tone_44k.pcm";
    const auto room_tone = GenerateStudioRoomTone(kSampleRateHz * 2);  // 2.0s seamless loop
    if (!WriteAlignedPcmFile(room_tone_path, room_tone)) {
        std::cerr << "[FAIL] Failed to write room_tone_44k.pcm\n";
        return 1;
    }

    if (!TestMissingRoomToneFailsHonestly()) {
        return 1;
    }
    if (!TestBoundaryCrossfadesAndRoomTone(room_tone_path, tmp.dir)) {
        return 1;
    }
    if (!TestGate3MultiThreaded50SwapsAndGapSweep(room_tone_path, tmp.dir)) {
        return 1;
    }

    std::cout << "ALL EdlGlitchTest GATE 3 CHECKS PASSED\n";
    return 0;
}
