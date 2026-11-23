#include "verifier_gates.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <vector>

namespace {

using namespace voicestudio::verifier;

constexpr double kPi = 3.14159265358979323846;

int g_failures = 0;

void Expect(bool cond, const std::string& msg) {
    if (!cond) {
        std::cerr << "[FAIL] " << msg << "\n";
        ++g_failures;
    }
}

void ExpectNear(double actual, double expected, double tol, const std::string& msg) {
    if (std::fabs(actual - expected) > tol) {
        std::cerr << "[FAIL] " << msg << " (actual=" << actual << ", expected=" << expected
                  << ", tol=" << tol << ")\n";
        ++g_failures;
    }
}

std::vector<float> MakeRealisticSpeechBuffer(
    size_t total_samples,
    float f0_hz,
    float amplitude,
    bool inject_white_noise_tail,
    bool inject_short_hf_burst_tail,
    uint32_t seed) {
    std::vector<float> pcm(total_samples, 0.0f);
    const size_t tail_samples = static_cast<size_t>((static_cast<int64_t>(kSampleRateHz) * kTailWindowMs) / 1000);
    const size_t speech_end = (total_samples > tail_samples) ? (total_samples - tail_samples) : total_samples;

    // Multi-harmonic speech-like body with smooth raised-cosine fade into quiet studio floor.
    for (size_t i = 0; i < speech_end; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(kSampleRateHz);
        // Gentle syllable-like envelope modulation (5 Hz).
        const double env = 0.65 + 0.35 * std::sin(2.0 * kPi * 5.0 * t);
        double fade = 1.0;
        if (i < 441) {
            fade = static_cast<double>(i) / 441.0;
        } else if (speech_end - i < 882) {
            fade = static_cast<double>(speech_end - i) / 882.0;
        }
        const double wave =
            0.60 * std::sin(2.0 * kPi * f0_hz * t) +
            0.28 * std::sin(2.0 * kPi * (2.0 * f0_hz) * t + 0.3) +
            0.12 * std::sin(2.0 * kPi * (3.0 * f0_hz) * t + 0.7);
        pcm[i] = static_cast<float>(amplitude * env * fade * wave);
    }

    // Quiet -58 dBFS studio noise floor in the final 80 ms tail (RMS ~= 0.00126).
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);
    const float quiet_peak = 0.0015f;  // RMS ~ 0.000866 (-61.2 dBFS <= -45 dBFS)
    for (size_t i = speech_end; i < total_samples; ++i) {
        pcm[i] = dist(rng) * quiet_peak;
    }

    if (inject_white_noise_tail) {
        // Inject white-noise glitch across final 80 ms (RMS ~= 0.12 / sqrt(3) = 0.069 -> -23.2 dBFS).
        for (size_t i = speech_end; i < total_samples; ++i) {
            pcm[i] = dist(rng) * 0.12f;
        }
    } else if (inject_short_hf_burst_tail) {
        // Inject short 12 ms high-frequency (9 kHz) glitch burst near the end of an otherwise silent tail.
        const size_t burst_len = 529;  // ~12 ms @ 44.1 kHz
        const size_t burst_start = total_samples > burst_len + 256 ? total_samples - burst_len - 128 : speech_end;
        for (size_t i = 0; i < burst_len && burst_start + i < total_samples; ++i) {
            const double t = static_cast<double>(i) / static_cast<double>(kSampleRateHz);
            pcm[burst_start + i] = static_cast<float>(0.045 * std::sin(2.0 * kPi * 9000.0 * t));
        }
    }
    return pcm;
}

bool WriteCentroidFile(const std::filesystem::path& path, const std::array<float, kSpeakerEmbeddingDim>& vec) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out.good()) {
        return false;
    }
    for (float v : vec) {
        uint32_t bits = 0;
        std::memcpy(&bits, &v, sizeof(float));
        char bytes[4] = {
            static_cast<char>(bits & 0xFFu),
            static_cast<char>((bits >> 8) & 0xFFu),
            static_cast<char>((bits >> 16) & 0xFFu),
            static_cast<char>((bits >> 24) & 0xFFu),
        };
        out.write(bytes, 4);
    }
    return out.good();
}

class DeterministicTestCtcBackend final : public CtcAlignerBackend {
public:
    DeterministicTestCtcBackend(std::string recognized, float max_phoneme_ms, std::vector<RawWordSpan> spans)
        : recognized_(std::move(recognized)), max_phoneme_ms_(max_phoneme_ms), spans_(std::move(spans)) {}

    bool Align(
        const float* /* pcm_44k */,
        size_t /* total_samples */,
        const std::string& /* expected_text */,
        CtcAlignmentOutput* output,
        std::string* /* error_out */) const override {
        output->recognized_text = recognized_;
        output->max_phoneme_duration_ms = max_phoneme_ms_;
        output->word_spans = spans_;
        return true;
    }

private:
    std::string recognized_;
    float max_phoneme_ms_;
    std::vector<RawWordSpan> spans_;
};

class DeterministicTestSpeakerBackend final : public SpeakerEmbeddingBackend {
public:
    explicit DeterministicTestSpeakerBackend(std::array<float, kSpeakerEmbeddingDim> emb)
        : emb_(emb) {}

    bool ExtractEmbedding(
        const float* /* pcm_44k */,
        size_t /* total_samples */,
        std::array<float, kSpeakerEmbeddingDim>* embedding_out,
        std::string* /* error_out */) const override {
        *embedding_out = emb_;
        return true;
    }

private:
    std::array<float, kSpeakerEmbeddingDim> emb_;
};

class DeterministicTestDnsmosBackend final : public DnsmosP808Backend {
public:
    explicit DeterministicTestDnsmosBackend(float score) : score_(score) {}

    bool ScoreAudio(
        const float* /* pcm_44k */,
        size_t /* total_samples */,
        float* score_out,
        std::string* /* error_out */) const override {
        *score_out = score_;
        return true;
    }

private:
    float score_;
};

void TestTailRmsAndSpectralFlux() {
    const size_t total = 44100 * 2;
    const auto clean = MakeRealisticSpeechBuffer(total, 180.0f, 0.25f, false, false, 11);
    const auto noise_tail = MakeRealisticSpeechBuffer(total, 180.0f, 0.25f, true, false, 22);
    const auto hf_burst_tail = MakeRealisticSpeechBuffer(total, 180.0f, 0.25f, false, true, 33);

    const TailAnalysis clean_res = VerifierGates::AnalyzeTail(clean.data(), clean.size());
    Expect(clean_res.tail_rms_db <= -45.0f, "Clean tail RMS should be <= -45 dBFS");
    Expect(!clean_res.hf_glitch_detected, "Clean tail should not trigger HF glitch detection");

    const TailAnalysis noise_res = VerifierGates::AnalyzeTail(noise_tail.data(), noise_tail.size());
    Expect(noise_res.tail_rms_db > -45.0f, "White-noise tail RMS should exceed -45 dBFS");
    Expect(noise_res.hf_glitch_detected, "White-noise tail should trigger HF glitch detection");

    const TailAnalysis burst_res = VerifierGates::AnalyzeTail(hf_burst_tail.data(), hf_burst_tail.size());
    Expect(burst_res.hf_glitch_detected, "Short 9 kHz HF burst in tail should trigger HF spectral flux glitch");
    Expect(burst_res.tail_hf_spectral_flux > 0.008f, "Short 9 kHz burst should produce positive HF spectral flux");
}

void TestLevenshteinCer() {
    ExpectNear(
        VerifierGates::ComputeCer(
            "We ripped out the cloud wrapper and ran it bare-metal.",
            "we ripped out the cloud wrapper and ran it bare metal"),
        0.0f,
        1e-6f,
        "Exact match after normalization should give CER == 0.0");

    const float dropped_word_cer = VerifierGates::ComputeCer(
        "We ripped out the cloud wrapper",
        "We ripped out the wrapper");
    // Reference normalized: "we ripped out the cloud wrapper" (31 chars); dropped "cloud " = 6 deletions.
    ExpectNear(dropped_word_cer, 6.0 / 31.0, 1e-4, "Dropped word CER should equal exact Levenshtein ratio");
    Expect(dropped_word_cer > 0.0f, "Dropped word CER must be > 0.0");

    ExpectNear(VerifierGates::ComputeCer("abcde", "abxde"), 0.2, 1e-6, "Single substitution CER == 0.2");
}

void TestCtcGreedyAndViterbiAlignment() {
    // Vocab: 0=<blk>, 1=|, 2=h, 3=i, 4=g, 5=o
    const std::vector<std::string> vocab = {"<blk>", "|", "h", "i", "g", "o"};
    const size_t vocab_size = vocab.size();
    const int32_t frame_shift = 882;  // 20 ms @ 44.1 kHz
    const size_t num_frames = 30;     // 600 ms total
    const size_t total_samples = num_frames * static_cast<size_t>(frame_shift);

    // Construct clean log-prob matrix for "hi go":
    // frames 2..5: 'h', 6..10: 'i', 12..14: '|', 16..20: 'g', 21..25: 'o', rest: <blk>
    std::vector<float> log_probs(num_frames * vocab_size, -10.0f);
    auto set_frame = [&](size_t t, size_t tok) {
        log_probs[t * vocab_size + tok] = -0.05f;
    };
    for (size_t t = 0; t < num_frames; ++t) {
        size_t tok = 0;
        if (t >= 2 && t <= 5) tok = 2;       // h (4 frames = 80 ms)
        else if (t >= 6 && t <= 10) tok = 3; // i (5 frames = 100 ms)
        else if (t >= 12 && t <= 14) tok = 1;// |
        else if (t >= 16 && t <= 20) tok = 4;// g (5 frames = 100 ms)
        else if (t >= 21 && t <= 25) tok = 5;// o (5 frames = 100 ms)
        set_frame(t, tok);
    }

    std::string decoded;
    float greedy_max_ms = 0.0f;
    Expect(
        VerifierGates::CtcGreedyDecode(
            log_probs.data(), num_frames, vocab_size, 0, vocab, frame_shift, &decoded, &greedy_max_ms),
        "CTC greedy decode should succeed");
    Expect(decoded == "hi go", "CTC greedy decode should output 'hi go', got: " + decoded);
    ExpectNear(greedy_max_ms, 100.0, 1e-2, "CTC greedy max phoneme duration should be 100 ms");

    std::vector<RawWordSpan> spans;
    float viterbi_max_ms = 0.0f;
    const std::vector<int32_t> target_ids = {2, 3, 1, 4, 5};
    Expect(
        VerifierGates::CtcViterbiForceAlign(
            log_probs.data(),
            num_frames,
            vocab_size,
            0,
            target_ids,
            vocab,
            frame_shift,
            total_samples,
            &spans,
            &viterbi_max_ms),
        "CTC Viterbi forced alignment should succeed");
    Expect(spans.size() == 2, "CTC Viterbi should emit 2 word spans");
    if (spans.size() == 2) {
        Expect(spans[0].word == "hi", "First aligned word should be 'hi'");
        Expect(spans[0].start_sample == 2 * frame_shift, "Word 'hi' start sample matches frame 2");
        Expect(spans[0].end_sample == 11 * frame_shift, "Word 'hi' end sample matches frame 11");
        Expect(spans[1].word == "go", "Second aligned word should be 'go'");
        Expect(spans[1].start_sample == 16 * frame_shift, "Word 'go' start sample matches frame 16");
        Expect(spans[1].end_sample == 26 * frame_shift, "Word 'go' end sample matches frame 26");
    }
    ExpectNear(viterbi_max_ms, 100.0, 1e-2, "CTC Viterbi max phoneme duration should be 100 ms");

    // Dragged phoneme test: 22 frames (440 ms > 380 ms) on 'i'.
    const size_t drag_frames = 45;
    std::vector<float> drag_lp(drag_frames * vocab_size, -10.0f);
    for (size_t t = 0; t < drag_frames; ++t) {
        size_t tok = 0;
        if (t >= 2 && t <= 4) tok = 2;
        else if (t >= 5 && t <= 26) tok = 3;  // 22 frames * 20 ms = 440 ms
        else if (t >= 28 && t <= 30) tok = 1;
        else if (t >= 32 && t <= 35) tok = 4;
        else if (t >= 36 && t <= 40) tok = 5;
        drag_lp[t * vocab_size + tok] = -0.05f;
    }
    float drag_max_ms = 0.0f;
    std::vector<RawWordSpan> drag_spans;
    Expect(
        VerifierGates::CtcViterbiForceAlign(
            drag_lp.data(),
            drag_frames,
            vocab_size,
            0,
            target_ids,
            vocab,
            frame_shift,
            drag_frames * static_cast<size_t>(frame_shift),
            &drag_spans,
            &drag_max_ms),
        "CTC Viterbi dragged phoneme alignment should succeed");
    Expect(drag_max_ms > 380.0f, "Dragged phoneme duration must exceed 380 ms (got " + std::to_string(drag_max_ms) + ")");
}

void TestCentroidAndCosineSimilarity(const std::filesystem::path& temp_dir) {
    std::array<float, kSpeakerEmbeddingDim> centroid_raw{};
    for (size_t i = 0; i < kSpeakerEmbeddingDim; ++i) {
        centroid_raw[i] = std::sin(static_cast<float>(i + 1) * 0.13f);
    }
    const auto centroid_path = temp_dir / "master_centroid.bin";
    Expect(WriteCentroidFile(centroid_path, centroid_raw), "Writing 256-float centroid binary should succeed");

    std::array<float, kSpeakerEmbeddingDim> loaded{};
    std::string err;
    Expect(VerifierGates::LoadMasterCentroid(centroid_path.string(), &loaded, &err),
           "LoadMasterCentroid should read valid 1024-byte centroid: " + err);

    const float self_sim = VerifierGates::CosineSimilarity(loaded.data(), centroid_raw.data(), kSpeakerEmbeddingDim);
    ExpectNear(self_sim, 1.0, 1e-5, "Identical speaker embedding cosine sim should be 1.0");

    // Orthogonal / perturbed embedding below 0.86 threshold.
    std::array<float, kSpeakerEmbeddingDim> imposter{};
    for (size_t i = 0; i < kSpeakerEmbeddingDim; ++i) {
        imposter[i] = std::cos(static_cast<float>(i + 1) * 0.71f);
    }
    const float imposter_sim = VerifierGates::CosineSimilarity(loaded.data(), imposter.data(), kSpeakerEmbeddingDim);
    Expect(imposter_sim < 0.86f, "Imposter speaker embedding cosine sim should be < 0.86");

    // Malformed centroid file size should be rejected cleanly.
    const auto bad_path = temp_dir / "bad_centroid.bin";
    {
        std::ofstream bad(bad_path, std::ios::binary);
        bad.write("short", 5);
    }
    Expect(!VerifierGates::LoadMasterCentroid(bad_path.string(), &loaded, &err),
           "Malformed centroid file size must fail cleanly");
}

void TestCadenceSyllables() {
    const std::string sentence = "We ripped out the cloud wrapper and ran it bare-metal.";
    const int32_t syllables = VerifierGates::CountTranscriptSyllables(sentence);
    Expect(syllables == 13, "Expected 13 syllables for canonical test clause, got " + std::to_string(syllables));

    // 13 syllables over 2.5 seconds (110,250 samples) -> 5.2 syl/s (inside 4.2..6.1).
    const float good_sps = VerifierGates::ComputeSyllablesPerSec(sentence, 110250);
    ExpectNear(good_sps, 5.2, 1e-3, "13 syllables / 2.5s should equal 5.2 syl/s");
    Expect(good_sps >= 4.2f && good_sps <= 6.1f, "5.2 syl/s must be within 4.2..6.1");

    // Too fast: 13 syllables over 1.5s -> 8.67 syl/s (> 6.1).
    const float fast_sps = VerifierGates::ComputeSyllablesPerSec(sentence, 66150);
    Expect(fast_sps > 6.1f, "Fast cadence should exceed 6.1 syl/s");

    // Too slow: 13 syllables over 4.0s -> 3.25 syl/s (< 4.2).
    const float slow_sps = VerifierGates::ComputeSyllablesPerSec(sentence, 176400);
    Expect(slow_sps < 4.2f, "Slow cadence should fall below 4.2 syl/s");
}

void TestZeroCrossingAndYinF0Peak() {
    const size_t total = 44100 * 2;
    const auto pcm = MakeRealisticSpeechBuffer(total, 175.0f, 0.20f, false, false, 44);

    // Scan multiple word boundaries across realistic 44.1 kHz speech waveform.
    const std::vector<int64_t> targets = {6000, 18240, 31680, 52000, 74500};
    for (int64_t target : targets) {
        const int64_t zc = VerifierGates::FindNearestZeroCrossing(
            pcm.data(), static_cast<int64_t>(pcm.size()), target, kZeroCrossScanSamples);
        Expect(std::llabs(zc - target) <= kZeroCrossScanSamples,
               "Zero crossing must stay within +/- 88 samples of target");
        Expect(std::fabs(pcm[static_cast<size_t>(zc)]) < kZeroCrossMaxAbsAmplitude,
               "Zero crossing amplitude must be < 0.005f, got " + std::to_string(std::fabs(pcm[static_cast<size_t>(zc)])));
    }

    // YIN F0 peak test: word span [10000, 30000] with unvoiced quiet edges and strong 180 Hz voiced vowel
    // strictly centered around sample 22000..25000.
    std::vector<float> f0_buf(44100, 0.0f);
    std::mt19937 rng(99);
    std::uniform_real_distribution<float> noise(-0.02f, 0.02f);
    for (size_t i = 10000; i <= 30000; ++i) {
        f0_buf[i] = noise(rng);
    }
    for (size_t i = 21500; i <= 25500; ++i) {
        const double t = static_cast<double>(i - 21500) / static_cast<double>(kSampleRateHz);
        const double env = std::sin(kPi * static_cast<double>(i - 21500) / 4000.0);
        f0_buf[i] = static_cast<float>(0.45 * env * std::sin(2.0 * kPi * 180.0 * t));
    }

    const int64_t f0_peak = VerifierGates::FindF0PeakSample(
        f0_buf.data(), static_cast<int64_t>(f0_buf.size()), 10000, 30000);
    Expect(f0_peak >= 10000 && f0_peak <= 30000, "YIN f0_peak_sample must stay inside [start, end]");
    Expect(f0_peak >= 21500 && f0_peak <= 25500,
           "YIN f0_peak_sample should locate the voiced 180 Hz vowel nucleus, got " + std::to_string(f0_peak));
}

void TestMissingModelsAndFullGateVerification(const std::filesystem::path& temp_dir) {
    // Constitutional Law 2: missing sherpa_ctc_model_dir / ONNX models MUST fail cleanly.
    VerifierGates missing_verifier;
    VerifierConfig bad_cfg{
        (temp_dir / "nonexistent_ctc_dir").string(),
        (temp_dir / "missing_wespeaker.onnx").string(),
        (temp_dir / "missing_dnsmos.onnx").string(),
        (temp_dir / "missing_centroid.bin").string(),
    };
    std::string err;
    Expect(!missing_verifier.Init(bad_cfg, &err), "Init must fail when model files are missing");
    Expect(!err.empty(), "Init failure must populate explicit error message");

    const std::string clause = "We ripped out the cloud wrapper and ran it bare-metal.";
    const size_t total = 114660;  // 2.60s total (2.52s speech + 80ms quiet tail -> ~5.16 syl/s)
    const auto good_pcm = MakeRealisticSpeechBuffer(total, 165.0f, 0.22f, false, false, 101);
    const auto bad_tail_pcm = MakeRealisticSpeechBuffer(total, 165.0f, 0.22f, true, false, 202);

    GateResult uninit_res;
    Expect(
        !missing_verifier.VerifyCandidate(
            good_pcm.data(), good_pcm.size(), clause, VerifierThresholds{}, &uninit_res, &err),
        "VerifyCandidate on uninitialized verifier must return false (no fabricated CER=0)");
    Expect(!uninit_res.passed_all_gates, "Uninitialized verifier must not pass gates");

    // Now test full 4-gate pipeline with real centroid binary and real waveforms.
    std::array<float, kSpeakerEmbeddingDim> master{};
    for (size_t i = 0; i < kSpeakerEmbeddingDim; ++i) {
        master[i] = std::cos(static_cast<float>(i + 1) * 0.19f);
    }
    const auto centroid_path = temp_dir / "gate_centroid.bin";
    Expect(WriteCentroidFile(centroid_path, master), "Write gate_centroid.bin");

    const std::vector<RawWordSpan> spans = {
        {"We", 441, 9000},
        {"ripped", 9200, 19000},
        {"out", 19200, 27000},
        {"the", 27200, 34000},
        {"cloud", 34200, 46000},
        {"wrapper", 46200, 62000},
        {"and", 62200, 70000},
        {"ran", 70200, 80000},
        {"it", 80200, 88000},
        {"bare-metal", 88200, 110691},
    };

    // 1. Known-good candidate -> MUST pass all 4 gates with zero-crossing |pcm[idx]| < 0.005f.
    VerifierGates good_verifier;
    Expect(
        good_verifier.InitWithBackends(
            centroid_path.string(),
            std::make_shared<DeterministicTestCtcBackend>(clause, 210.0f, spans),
            std::make_shared<DeterministicTestSpeakerBackend>(master),
            std::make_shared<DeterministicTestDnsmosBackend>(4.45f),
            &err),
        "InitWithBackends should succeed: " + err);

    GateResult good_res;
    Expect(
        good_verifier.VerifyCandidate(
            good_pcm.data(), good_pcm.size(), clause, VerifierThresholds{}, &good_res, &err),
        "VerifyCandidate on good buffer should succeed: " + err);
    Expect(good_res.passed_all_gates, "Known-good candidate must pass all 4 gates (reason=" + good_res.failure_reason + ")");
    ExpectNear(good_res.cer, 0.0, 1e-6, "Known-good candidate CER == 0.0");
    Expect(good_res.speaker_cosine_sim >= 0.86f, "Known-good speaker cosine >= 0.86");
    Expect(good_res.dnsmos_p808 >= 4.10f, "Known-good DNSMOS >= 4.10");
    Expect(good_res.tail_rms_db <= -45.0f, "Known-good tail RMS <= -45 dBFS");
    Expect(good_res.syllables_per_sec >= 4.2f && good_res.syllables_per_sec <= 6.1f, "Known-good cadence in 4.2..6.1");
    Expect(good_res.tokens.size() == spans.size(), "All word spans aligned");
    for (const auto& tok : good_res.tokens) {
        Expect(std::fabs(good_pcm[static_cast<size_t>(tok.zero_cross_in)]) < kZeroCrossMaxAbsAmplitude,
               "Token zero_cross_in amplitude < 0.005f for word " + tok.word);
        Expect(std::fabs(good_pcm[static_cast<size_t>(tok.zero_cross_out)]) < kZeroCrossMaxAbsAmplitude,
               "Token zero_cross_out amplitude < 0.005f for word " + tok.word);
        Expect(tok.f0_peak_sample >= tok.start_sample && tok.f0_peak_sample <= tok.end_sample,
               "Token f0_peak_sample inside [start_sample, end_sample]");
    }

    // 2. Corrupted #1: Dropped word ("cloud" omitted) -> hard rejected.
    VerifierGates dropped_word_verifier;
    Expect(
        dropped_word_verifier.InitWithBackends(
            centroid_path.string(),
            std::make_shared<DeterministicTestCtcBackend>(
                "We ripped out the wrapper and ran it bare-metal.", 210.0f, spans),
            std::make_shared<DeterministicTestSpeakerBackend>(master),
            std::make_shared<DeterministicTestDnsmosBackend>(4.45f),
            &err),
        "Init dropped-word verifier");
    GateResult dropped_res;
    Expect(
        dropped_word_verifier.VerifyCandidate(
            good_pcm.data(), good_pcm.size(), clause, VerifierThresholds{}, &dropped_res, &err),
        "VerifyCandidate runs on dropped-word take");
    Expect(!dropped_res.passed_all_gates, "Dropped-word candidate MUST be hard-rejected");
    Expect(dropped_res.cer > 0.0f, "Dropped-word candidate must have CER > 0.0");

    // 3. Corrupted #2: White-noise tail glitch -> hard rejected.
    GateResult tail_glitch_res;
    Expect(
        good_verifier.VerifyCandidate(
            bad_tail_pcm.data(), bad_tail_pcm.size(), clause, VerifierThresholds{}, &tail_glitch_res, &err),
        "VerifyCandidate runs on white-noise tail take");
    Expect(!tail_glitch_res.passed_all_gates, "White-noise tail glitch candidate MUST be hard-rejected");
    Expect(tail_glitch_res.tail_rms_db > -45.0f, "White-noise tail glitch tail_rms_db must exceed -45 dBFS");
}

}  // namespace

int main() {
    const auto temp_dir = std::filesystem::temp_directory_path() / "voicestudio_verifier_core_test";
    std::error_code ec;
    std::filesystem::remove_all(temp_dir, ec);
    std::filesystem::create_directories(temp_dir, ec);

    TestTailRmsAndSpectralFlux();
    TestLevenshteinCer();
    TestCtcGreedyAndViterbiAlignment();
    TestCentroidAndCosineSimilarity(temp_dir);
    TestCadenceSyllables();
    TestZeroCrossingAndYinF0Peak();
    TestMissingModelsAndFullGateVerification(temp_dir);

    std::filesystem::remove_all(temp_dir, ec);

    if (g_failures == 0) {
        std::cout << "[PASS] All VerifierCoreTest Gate-2 C++ checks passed.\n";
        return 0;
    }
    std::cerr << "[FAIL] VerifierCoreTest finished with " << g_failures << " failure(s).\n";
    return 1;
}
