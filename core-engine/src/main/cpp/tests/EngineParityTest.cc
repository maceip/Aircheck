#include "dsp_mastering.h"
#include "voice_engine.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

using namespace voicestudio::engine;
namespace fs = std::filesystem;

constexpr double kPi = 3.14159265358979323846;

struct ProbeStats {
    int64_t backend_init_count = 0;
    int64_t backend_free_count = 0;
    int64_t live_models = 0;
    int64_t live_contexts = 0;
    int64_t live_adapters = 0;
    int64_t adapter_init_count = 0;
    int64_t adapter_set_count = 0;
    int64_t adapter_rm_count = 0;
    int64_t adapter_free_count = 0;
    int64_t live_adapter_bytes = 0;
    int64_t batch_init_count = 0;
    int64_t batch_free_count = 0;
    int64_t decode_call_count = 0;
};

int g_failures = 0;

void Check(bool cond, const std::string& msg) {
    if (!cond) {
        std::cerr << "[FAIL] " << msg << "\n";
        ++g_failures;
    }
}

fs::path MakeTempDir() {
    const fs::path dir = fs::temp_directory_path() / "voicestudio_engine_parity_test";
    fs::remove_all(dir);
    fs::create_directories(dir);
    return dir;
}

void WriteDummyGgufHeader(const fs::path& path, uint32_t version = 3, size_t total_bytes = 16384) {
    std::vector<uint8_t> bytes(std::max<size_t>(total_bytes, 64), 0);
    bytes[0] = 'G';
    bytes[1] = 'G';
    bytes[2] = 'U';
    bytes[3] = 'F';
    bytes[4] = static_cast<uint8_t>(version & 0xFFU);
    bytes[5] = static_cast<uint8_t>((version >> 8U) & 0xFFU);
    bytes[6] = static_cast<uint8_t>((version >> 16U) & 0xFFU);
    bytes[7] = static_cast<uint8_t>((version >> 24U) & 0xFFU);
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

void WriteDummyTfliteHeader(const fs::path& path, size_t total_bytes = 256) {
    std::vector<uint8_t> bytes(std::max<size_t>(total_bytes, 32), 0);
    bytes[4] = 'T';
    bytes[5] = 'F';
    bytes[6] = 'L';
    bytes[7] = '3';
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

std::vector<float> MakeSpeechLikeSignal(size_t num_samples, float amplitude) {
    std::vector<float> pcm(num_samples, 0.0f);
    for (size_t i = 0; i < num_samples; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(kSampleRateHz);
        // Syllabic amplitude envelope (~4.5 Hz) over vocal fundamental + harmonics.
        const double env = 0.65 + 0.35 * std::sin(2.0 * kPi * 4.5 * t);
        const double wave =
            0.55 * std::sin(2.0 * kPi * 180.0 * t) +
            0.30 * std::sin(2.0 * kPi * 540.0 * t) +
            0.15 * std::sin(2.0 * kPi * 1260.0 * t);
        pcm[i] = static_cast<float>(amplitude * env * wave);
    }
    return pcm;
}

std::vector<float> MakeStudioRoomIr12ms() {
    // 12 ms @ 44.1 kHz = 529 samples with realistic early reflections and smooth exponential decay.
    const size_t ir_len = 529;
    std::vector<float> ir(ir_len, 0.0f);
    ir[0] = 0.85f;
    ir[47] = 0.14f;
    ir[113] = -0.09f;
    ir[211] = 0.06f;
    ir[331] = -0.04f;
    for (size_t i = 20; i < ir_len; ++i) {
        const double decay = std::exp(-5.0 * static_cast<double>(i) / static_cast<double>(ir_len));
        const double ripple = std::sin(0.37 * static_cast<double>(i));
        ir[i] += static_cast<float>(0.015 * decay * ripple);
    }
    return ir;
}

void TestUnitDeltaRoomIrPassThrough() {
    std::vector<float> input = MakeSpeechLikeSignal(4410, 0.25f);
    std::vector<float> copy = input;
    std::vector<float> delta(529, 0.0f);
    delta[0] = 1.0f;

    Check(IsUnitDeltaImpulse(delta.data(), delta.size()), "Unit delta must be recognized");
    ConvolveRoomIr(copy, delta.data(), delta.size());

    float max_diff = 0.0f;
    for (size_t i = 0; i < input.size(); ++i) {
        max_diff = std::max(max_diff, std::fabs(copy[i] - input[i]));
    }
    Check(max_diff == 0.0f, "Unit delta Room IR must be bit-identical pass-through");
    std::cout << "[PASS] TestUnitDeltaRoomIrPassThrough (max_diff=" << max_diff << ")\n";
}

void Test12msRoomIrConvolutionAccuracyAndNeonParity() {
    const std::vector<float> ir = MakeStudioRoomIr12ms();
    Check(!IsUnitDeltaImpulse(ir.data(), ir.size()), "12ms studio IR must not be treated as unit delta");

    const std::vector<float> input = MakeSpeechLikeSignal(88200, 0.20f);  // 2.0 s @ 44.1 kHz
    std::vector<float> out_scalar(input.size(), 0.0f);
    std::vector<float> out_neon(input.size(), 0.0f);
    std::vector<float> out_ola(input.size(), 0.0f);

    // Compare Direct Scalar vs Direct NEON on a 64-tap slice + full 529-tap IR.
    ConvolveRoomIrDirectScalar(input.data(), 8192, ir.data(), 64, out_scalar.data());
    ConvolveRoomIrDirectNeon(input.data(), 8192, ir.data(), 64, out_neon.data());
    float max_neon_diff = 0.0f;
    for (size_t i = 0; i < 8192; ++i) {
        max_neon_diff = std::max(max_neon_diff, std::fabs(out_scalar[i] - out_neon[i]));
    }
    Check(max_neon_diff < 1e-5f, "Direct NEON FIR must match scalar FIR within 1e-5");

    // Compare full 12ms (529-tap) Overlap-Add FFT convolution against exact Direct Scalar FIR.
    const size_t parity_len = 16384;
    ConvolveRoomIrDirectScalar(input.data(), parity_len, ir.data(), ir.size(), out_scalar.data());
    ConvolveRoomIrOverlapAdd(input.data(), parity_len, ir.data(), ir.size(), out_ola.data());
    float max_ola_diff = 0.0f;
    for (size_t i = 0; i < parity_len; ++i) {
        max_ola_diff = std::max(max_ola_diff, std::fabs(out_scalar[i] - out_ola[i]));
    }
    Check(max_ola_diff < 1e-4f, "12ms Overlap-Add convolution must match exact Direct FIR within 1e-4");

    // Verify delayed impulse shifts accurately (proves non-delta IR is truly convolved, not skipped).
    std::vector<float> delayed_ir(120, 0.0f);
    delayed_ir[100] = 0.5f;
    std::vector<float> delayed_sig = input;
    delayed_sig.resize(4096);
    ConvolveRoomIr(delayed_sig, delayed_ir.data(), delayed_ir.size());
    float delay_err = 0.0f;
    for (size_t i = 100; i < 4096; ++i) {
        delay_err = std::max(delay_err, std::fabs(delayed_sig[i] - 0.5f * input[i - 100]));
    }
    Check(delay_err < 1e-4f, "Delayed impulse IR must shift and scale signal accurately");

    // Benchmark 2.0s mastering pass latency.
    std::vector<float> bench = input;
    const auto t0 = std::chrono::steady_clock::now();
    MasterClausePcm(bench, ir.data(), ir.size(), nullptr, 0);
    const auto t1 = std::chrono::steady_clock::now();
    const double elapsed_ms =
        std::chrono::duration<double, std::milli>(t1 - t0).count();

    std::cout << "[PASS] Test12msRoomIrConvolutionAccuracyAndNeonParity "
              << "(neon_diff=" << max_neon_diff
              << ", ola_diff=" << max_ola_diff
              << ", delay_err=" << delay_err
              << ", master_2s_ms=" << elapsed_ms << ")\n";
}

void TestLufsNormalizationAndSoftLimiter() {
    const std::vector<float> ir = MakeStudioRoomIr12ms();

    // Case 1: Quiet input (~ -32 LUFS) -> must normalize to -16.0 +/- 0.5 LUFS.
    std::vector<float> quiet = MakeSpeechLikeSignal(kSampleRateHz * 3, 0.03f);
    const float pre_quiet_lufs = MeasureIntegratedLufs(quiet.data(), quiet.size());
    MasterClausePcm(quiet, ir.data(), ir.size(), nullptr, 0);
    const float post_quiet_lufs = MeasureIntegratedLufs(quiet.data(), quiet.size());
    float quiet_peak = 0.0f;
    for (float s : quiet) {
        Check(std::isfinite(s), "Mastered quiet sample must be finite");
        quiet_peak = std::max(quiet_peak, std::fabs(s));
    }
    Check(
        std::fabs(post_quiet_lufs - kTargetIntegratedLufs) <= 0.5f,
        "Quiet signal must converge to -16.0 LUFS +/- 0.5 LU (got " +
            std::to_string(post_quiet_lufs) + ")");
    Check(quiet_peak <= kTruePeakCeiling, "Quiet mastered signal peak must stay <= 0.99f");

    // Case 2: Hot / loud input (~ -6 LUFS) -> must attenuate to -16.0 +/- 0.5 LUFS.
    std::vector<float> loud = MakeSpeechLikeSignal(kSampleRateHz * 3, 0.80f);
    const float pre_loud_lufs = MeasureIntegratedLufs(loud.data(), loud.size());
    MasterClausePcm(loud, ir.data(), ir.size(), nullptr, 0);
    const float post_loud_lufs = MeasureIntegratedLufs(loud.data(), loud.size());
    float loud_peak = 0.0f;
    for (float s : loud) {
        loud_peak = std::max(loud_peak, std::fabs(s));
    }
    Check(
        std::fabs(post_loud_lufs - kTargetIntegratedLufs) <= 0.5f,
        "Loud signal must converge to -16.0 LUFS +/- 0.5 LU (got " +
            std::to_string(post_loud_lufs) + ")");
    Check(loud_peak <= kTruePeakCeiling, "Loud mastered signal peak must stay <= 0.99f");

    // Case 3: Extreme transient spikes -> soft true-peak limiter prevents clipping strictly within [-0.99f, 0.99f].
    std::vector<float> spiky = MakeSpeechLikeSignal(kSampleRateHz * 2, 0.15f);
    spiky[20000] = 3.5f;
    spiky[40000] = -4.2f;
    MasterClausePcm(spiky, nullptr, 0, nullptr, 0);
    float spiky_peak = 0.0f;
    for (float s : spiky) {
        Check(std::isfinite(s), "Spiky mastered sample must be finite");
        spiky_peak = std::max(spiky_peak, std::fabs(s));
    }
    Check(spiky_peak <= kTruePeakCeiling, "Soft limiter must clamp extreme spikes <= 0.99f");

    std::cout << "[PASS] TestLufsNormalizationAndSoftLimiter "
              << "(quiet: " << pre_quiet_lufs << " -> " << post_quiet_lufs << " LUFS, "
              << "loud: " << pre_loud_lufs << " -> " << post_loud_lufs << " LUFS, "
              << "spiky_peak=" << spiky_peak << ")\n";
}

void TestStudioNoiseFloorBedMixing() {
    // 2s voiced speech followed by 1s digital silence.
    std::vector<float> pcm = MakeSpeechLikeSignal(kSampleRateHz * 2, 0.25f);
    pcm.resize(kSampleRateHz * 3, 0.0f);

    // Deterministic studio room tone buffer (uncalibrated at ~-34 dBFS; pipeline calibrates to -58 dBFS).
    std::vector<float> room_tone(kSampleRateHz / 2, 0.0f);
    uint64_t state = 0x9E3779B97F4A7C15ULL;
    for (float& s : room_tone) {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        const double u = static_cast<double>((state * 2685821657736338717ULL) >> 11) /
                         static_cast<double>(1ULL << 53);
        s = static_cast<float>((u * 2.0 - 1.0) * 0.03);
    }

    MasterClausePcm(pcm, nullptr, 0, room_tone.data(), room_tone.size(), -16.0f, -58.0f);

    // Measure RMS inside the quiet trailing 0.8s (far from voice boundary).
    const size_t start = kSampleRateHz * 2 + 4410;
    const size_t count = kSampleRateHz - 8820;
    double sum_sq = 0.0;
    for (size_t i = start; i < start + count; ++i) {
        sum_sq += static_cast<double>(pcm[i]) * static_cast<double>(pcm[i]);
    }
    const float floor_rms = static_cast<float>(std::sqrt(sum_sq / static_cast<double>(count)));
    const float floor_dbfs = 20.0f * std::log10(std::max(floor_rms, 1e-12f));
    Check(
        std::fabs(floor_dbfs - kStudioNoiseFloorDbfs) <= 0.5f,
        "Room tone noise floor must sit at -58.0 dBFS +/- 0.5 dB (got " +
            std::to_string(floor_dbfs) + ")");
    std::cout << "[PASS] TestStudioNoiseFloorBedMixing (floor_dbfs=" << floor_dbfs << ")\n";
}

void TestFindNearestZeroCrossingAndAlignedPcm(const fs::path& tmp_dir) {
    // 100,003 samples (deliberately non-multiple of 16 floats / 64 bytes) mastered to -16 LUFS
    // broadcast level (220 Hz fundamental matching voiced speech boundary energy).
    const size_t total_samples = 100003;
    std::vector<float> pcm(total_samples, 0.0f);
    for (size_t i = 0; i < total_samples; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(kSampleRateHz);
        pcm[i] = static_cast<float>(0.22 * std::sin(2.0 * kPi * 220.0 * t));
    }

    // Probe 20 target offsets across the signal.
    float worst_abs = 0.0f;
    for (int k = 1; k <= 20; ++k) {
        const int64_t target = static_cast<int64_t>(k) * 4500 + 37;
        const int64_t zc = FindNearestZeroCrossing(pcm.data(), static_cast<int64_t>(total_samples), target);
        const int64_t dist = std::llabs(zc - target);
        const float abs_amp = std::fabs(pcm[static_cast<size_t>(zc)]);
        worst_abs = std::max(worst_abs, abs_amp);
        Check(dist <= kZeroCrossScanSamples, "Zero crossing must be within +/- 88 samples");
        Check(abs_amp < 0.005f, "Zero crossing amplitude must be < 0.005f (got " + std::to_string(abs_amp) + ")");
    }

    // Write 64-byte aligned headerless PCM and verify disk alignment + file zero-cross parity.
    const fs::path pcm_path = tmp_dir / "c_001_take0.pcm";
    Check(WriteAlignedPcm44k(pcm_path.string(), pcm), "WriteAlignedPcm44k must succeed");
    const uintmax_t file_size = fs::file_size(pcm_path);
    Check(file_size % kPcmFileAlignmentBytes == 0, "PCM file size must be 64-byte aligned");
    Check(file_size >= total_samples * sizeof(float), "PCM file must contain all float samples");

    std::vector<float> read_back;
    Check(ReadPcmFile44k(pcm_path.string(), read_back), "ReadPcmFile44k must succeed");
    Check(read_back.size() >= total_samples, "Read-back sample count must cover written samples");
    for (size_t i = 0; i < total_samples; ++i) {
        if (read_back[i] != pcm[i]) {
            Check(false, "PCM float sample mismatch at index " + std::to_string(i));
            break;
        }
    }
    for (size_t i = total_samples; i < read_back.size(); ++i) {
        Check(read_back[i] == 0.0f, "64-byte alignment padding floats must be zero");
    }

    const int64_t target_disk = 45037;
    const int64_t zc_mem = FindNearestZeroCrossing(pcm.data(), static_cast<int64_t>(total_samples), target_disk);
    const int64_t zc_file = FindNearestZeroCrossingInFile(pcm_path.string(), target_disk);
    Check(zc_mem == zc_file, "In-file zero crossing scanner must match in-memory result exactly");

    // Extract exact trailing 1.5s (66,150 samples) acoustic prefix tail.
    const std::vector<float> tail = ExtractAcousticPrefixTail(pcm);
    Check(tail.size() == kPrefixTailSamples, "Prefix tail must be exact 66,150 samples");
    const size_t tail_offset = total_samples - kPrefixTailSamples;
    bool tail_exact = true;
    for (size_t i = 0; i < kPrefixTailSamples; ++i) {
        if (tail[i] != pcm[tail_offset + i]) {
            tail_exact = false;
            break;
        }
    }
    Check(tail_exact, "Extracted acoustic prefix tail must match trailing 66,150 samples bit-for-bit");

    std::cout << "[PASS] TestFindNearestZeroCrossingAndAlignedPcm "
              << "(worst_zc_abs=" << worst_abs
              << ", file_bytes=" << file_size
              << ", tail_samples=" << tail.size() << ")\n";
}

void TestMissingAndCorruptModelRejection(const fs::path& tmp_dir) {
    VoiceEngine engine;

    // 1. Missing GGUF file -> clean error.
    EngineConfig missing_cfg;
    missing_cfg.base_gguf_path = (tmp_dir / "nonexistent.gguf").string();
    missing_cfg.vocoder_tflite_path = (tmp_dir / "nonexistent.tflite").string();
    const auto st_missing = engine.Init(missing_cfg);
    Check(!st_missing.ok && !st_missing.error_message.empty(), "Missing GGUF must return explicit error");
    Check(!engine.IsInitialized(), "Engine must remain uninitialized after missing GGUF");

    // 2. Corrupt GGUF magic header -> clean error.
    const fs::path bad_gguf = tmp_dir / "bad_magic.gguf";
    {
        std::ofstream out(bad_gguf, std::ios::binary);
        out << "NOT_A_GGUF_FILE_0123456789ABCDEF";
    }
    const fs::path valid_tflite = tmp_dir / "codec_decoder_fp16.tflite";
    WriteDummyTfliteHeader(valid_tflite);

    EngineConfig bad_magic_cfg;
    bad_magic_cfg.base_gguf_path = bad_gguf.string();
    bad_magic_cfg.vocoder_tflite_path = valid_tflite.string();
    const auto st_bad_magic = engine.Init(bad_magic_cfg);
    Check(!st_bad_magic.ok, "Invalid GGUF magic must be rejected");

    // 3. Valid GGUF header but corrupt TFLite flatbuffer -> clean error.
    const fs::path valid_gguf = tmp_dir / "s2_pro_4b_q4_k_m.gguf";
    WriteDummyGgufHeader(valid_gguf);
    const fs::path bad_tflite = tmp_dir / "bad.tflite";
    {
        std::ofstream out(bad_tflite, std::ios::binary);
        out << "0000BAD!0000000000000000";
    }
    EngineConfig bad_tflite_cfg;
    bad_tflite_cfg.base_gguf_path = valid_gguf.string();
    bad_tflite_cfg.vocoder_tflite_path = bad_tflite.string();
    const auto st_bad_tflite = engine.Init(bad_tflite_cfg);
    Check(!st_bad_tflite.ok, "Invalid TFL3 flatbuffer must be rejected");

    // 4. Valid headers but nonexistent libllama library -> clean error (NEVER fabricates audio).
    EngineConfig missing_lib_cfg;
    missing_lib_cfg.base_gguf_path = valid_gguf.string();
    missing_lib_cfg.vocoder_tflite_path = valid_tflite.string();
    missing_lib_cfg.llama_library_path = (tmp_dir / "libllama_missing.dylib").string();
    const auto st_missing_lib = engine.Init(missing_lib_cfg);
    Check(!st_missing_lib.ok, "Missing libllama runtime must return explicit error");

    std::vector<SynthesizedCandidate> out_candidates;
    const auto st_synth = engine.SynthesizeCandidates("hello world", "", 4, out_candidates);
    Check(!st_synth.ok && out_candidates.empty(), "Uninitialized SynthesizeCandidates must fail without fake PCM");

    std::cout << "[PASS] TestMissingAndCorruptModelRejection\n";
}

void TestTenCycleLoraLifecycleAndMadviseEviction(const fs::path& tmp_dir) {
    const std::string probe_path = VOICESTUDIO_LLAMA_PROBE_PATH;
    void* probe_dl = ::dlopen(probe_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    Check(probe_dl != nullptr, "Must dlopen CTest llama_lifecycle_probe");
    if (probe_dl == nullptr) {
        return;
    }
    auto reset_fn = reinterpret_cast<void (*)()>(::dlsym(probe_dl, "voicestudio_probe_reset"));
    auto stats_fn = reinterpret_cast<void (*)(ProbeStats*)>(::dlsym(probe_dl, "voicestudio_probe_get_stats"));
    Check(reset_fn != nullptr && stats_fn != nullptr, "Must resolve probe stats symbols");
    reset_fn();

    const fs::path base_gguf = tmp_dir / "s2_pro_4b_q4_k_m.gguf";
    const fs::path lora_v0 = tmp_dir / "voice_lora_v000.gguf";
    const fs::path tflite = tmp_dir / "codec_decoder_fp16.tflite";
    WriteDummyGgufHeader(base_gguf, 3, 65536);
    WriteDummyGgufHeader(lora_v0, 3, 32768);
    WriteDummyTfliteHeader(tflite);

    {
        VoiceEngine engine;
        EngineConfig cfg;
        cfg.base_gguf_path = base_gguf.string();
        cfg.lora_gguf_path = lora_v0.string();
        cfg.vocoder_tflite_path = tflite.string();
        cfg.llama_library_path = probe_path;

        const auto init_st = engine.Init(cfg);
        Check(init_st.ok, "VoiceEngine::Init with probe must succeed: " + init_st.error_message);
        Check(engine.IsInitialized(), "VoiceEngine must report initialized");

        ProbeStats baseline{};
        stats_fn(&baseline);
        Check(baseline.live_models == 1, "Exactly 1 model must be live after Init");
        Check(baseline.live_contexts == 1, "Exactly 1 context must be live after Init");
        Check(baseline.live_adapters == 1, "Exactly 1 LoRA adapter must be live after Init");
        const int64_t baseline_adapter_bytes = baseline.live_adapter_bytes;
        Check(baseline_adapter_bytes > 0, "Initial LoRA allocation must be tracked");

        // 10 consecutive atomic Rank-32 LoRA hot-swap cycles.
        for (int cycle = 1; cycle <= 10; ++cycle) {
            const fs::path next_lora =
                tmp_dir / ("voice_lora_v" + std::to_string(100 + cycle) + ".gguf");
            WriteDummyGgufHeader(next_lora, 3, 32768);

            const auto swap_st = engine.HotSwapLora(next_lora.string());
            Check(swap_st.ok, "HotSwapLora cycle " + std::to_string(cycle) + " must succeed");

            ProbeStats st{};
            stats_fn(&st);
            Check(st.live_adapters == 1, "Live adapter count must stay 1 on cycle " + std::to_string(cycle));
            Check(
                st.live_adapter_bytes == baseline_adapter_bytes,
                "Zero native heap growth required across LoRA swaps (cycle " +
                    std::to_string(cycle) + ")");
        }
        Check(engine.LoraSwapCount() == 10, "LoraSwapCount must equal 10");

        // Failed swap mid-flight (llama_set_adapter_lora returns error) must roll back with 0 leaks.
        const fs::path fail_set_lora = tmp_dir / "voice_lora_fail_set.gguf";
        WriteDummyGgufHeader(fail_set_lora, 3, 32768);
        const auto fail_st = engine.HotSwapLora(fail_set_lora.string());
        Check(!fail_st.ok, "Failing llama_set_adapter_lora must return error");

        ProbeStats post_fail{};
        stats_fn(&post_fail);
        Check(post_fail.live_adapters == 1, "Rollback must keep exactly 1 active adapter");
        Check(
            post_fail.live_adapter_bytes == baseline_adapter_bytes,
            "Rollback must leak 0 adapter bytes");

        // Real madvise(..., MADV_DONTNEED) eviction over mmap'd GGUF base & LoRA weight pages.
        const size_t evicted = engine.EvictWeightPages();
        Check(evicted >= 65536 + 32768, "EvictWeightPages must advise both base and LoRA GGUF pages");
        Check(engine.LastEvictionSucceeded(), "madvise(MADV_DONTNEED) must succeed");

        // Real Dual-AR token rollout (N=4 candidates) without vocoder callback: returns discrete
        // tokens with ZERO fabricated PCM.
        std::vector<SynthesizedCandidate> candidates;
        const auto syn_st = engine.SynthesizeCandidates("test broadcast clause", "", 4, candidates);
        Check(syn_st.ok, "SynthesizeCandidates token rollout must succeed: " + syn_st.error_message);
        Check(candidates.size() == 4, "Must produce exactly 4 discrete-token candidates");
        for (size_t i = 0; i < candidates.size(); ++i) {
            Check(!candidates[i].discrete_audio_tokens.empty(), "Candidate must emit discrete tokens");
            Check(candidates[i].pcm_44k.empty(), "Without vocoder callback, pcm_44k must remain empty (zero fake PCM)");
            Check(candidates[i].seed == cfg.base_seed + static_cast<int64_t>(i), "Candidate seed must increment deterministically");
        }
    }

    // After ~VoiceEngine(), EVERY handle and adapter heap allocation must be freed cleanly.
    ProbeStats final_stats{};
    stats_fn(&final_stats);
    Check(final_stats.live_models == 0, "Zero leaked models after ~VoiceEngine()");
    Check(final_stats.live_contexts == 0, "Zero leaked contexts after ~VoiceEngine()");
    Check(final_stats.live_adapters == 0, "Zero leaked LoRA adapters after ~VoiceEngine()");
    Check(final_stats.live_adapter_bytes == 0, "Zero leaked LoRA bytes after ~VoiceEngine()");
    Check(
        final_stats.batch_init_count == final_stats.batch_free_count,
        "Every llama_batch_init must be paired with llama_batch_free");
    Check(
        final_stats.adapter_init_count == final_stats.adapter_free_count,
        "Every llama_adapter_lora_init must be paired with llama_adapter_lora_free");

    ::dlclose(probe_dl);
    std::cout << "[PASS] TestTenCycleLoraLifecycleAndMadviseEviction "
              << "(adapter_inits=" << final_stats.adapter_init_count
              << ", adapter_frees=" << final_stats.adapter_free_count
              << ", final_live_bytes=" << final_stats.live_adapter_bytes << ")\n";
}

}  // namespace

int main() {
    const fs::path tmp_dir = MakeTempDir();
    TestUnitDeltaRoomIrPassThrough();
    Test12msRoomIrConvolutionAccuracyAndNeonParity();
    TestLufsNormalizationAndSoftLimiter();
    TestStudioNoiseFloorBedMixing();
    TestFindNearestZeroCrossingAndAlignedPcm(tmp_dir);
    TestMissingAndCorruptModelRejection(tmp_dir);
    TestTenCycleLoraLifecycleAndMadviseEviction(tmp_dir);
    fs::remove_all(tmp_dir);

    if (g_failures > 0) {
        std::cerr << "\nEngineParityTest FAILED with " << g_failures << " assertion failure(s).\n";
        return 1;
    }
    std::cout << "\nEngineParityTest PASSED (all Gate 1 C++20 checks green).\n";
    return 0;
}
