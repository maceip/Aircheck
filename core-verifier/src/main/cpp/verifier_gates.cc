#include "verifier_gates.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <numeric>
#include <sstream>

namespace voicestudio::verifier {

namespace {

constexpr double kPi = 3.14159265358979323846;

bool FileReadableNonEmpty(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    std::error_code ec;
    if (!std::filesystem::is_regular_file(path, ec) || ec) {
        return false;
    }
    const auto sz = std::filesystem::file_size(path, ec);
    if (ec || sz == 0) {
        return false;
    }
    std::ifstream in(path, std::ios::binary);
    return in.good();
}

bool ValidateSherpaCtcDir(const std::string& dir_path, std::string* error_out) {
    if (dir_path.empty()) {
        if (error_out) {
            *error_out = "sherpa_ctc_model_dir must not be empty";
        }
        return false;
    }
    std::error_code ec;
    if (!std::filesystem::is_directory(dir_path, ec) || ec) {
        if (error_out) {
            *error_out = "Missing sherpa_ctc_model_dir directory: " + dir_path;
        }
        return false;
    }

    const auto tokens_path = std::filesystem::path(dir_path) / "tokens.txt";
    if (!FileReadableNonEmpty(tokens_path.string())) {
        if (error_out) {
            *error_out = "Missing tokens.txt in sherpa_ctc_model_dir: " + dir_path;
        }
        return false;
    }

    bool found_onnx = false;
    for (const auto& entry : std::filesystem::directory_iterator(dir_path, ec)) {
        if (ec) {
            break;
        }
        if (entry.is_regular_file(ec) && entry.path().extension() == ".onnx") {
            const auto sz = entry.file_size(ec);
            if (!ec && sz > 0) {
                found_onnx = true;
                break;
            }
        }
    }
    if (!found_onnx) {
        if (error_out) {
            *error_out = "No non-empty .onnx CTC model found in sherpa_ctc_model_dir: " + dir_path;
        }
        return false;
    }
    return true;
}

bool IsAsciiVowel(char c) {
    return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
}

// In-place radix-2 Cooley-Tukey FFT on power-of-two N.
void Radix2Fft(std::vector<double>& real, std::vector<double>& imag) {
    const size_t n = real.size();
    if (n <= 1) {
        return;
    }

    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * kPi / static_cast<double>(len);
        const double wlen_r = std::cos(ang);
        const double wlen_i = std::sin(ang);
        const size_t half = len >> 1;
        for (size_t i = 0; i < n; i += len) {
            double wr = 1.0;
            double wi = 0.0;
            for (size_t j = 0; j < half; ++j) {
                const double ur = real[i + j];
                const double ui = imag[i + j];
                const double vr = real[i + j + half] * wr - imag[i + j + half] * wi;
                const double vi = real[i + j + half] * wi + imag[i + j + half] * wr;
                real[i + j] = ur + vr;
                imag[i + j] = ui + vi;
                real[i + j + half] = ur - vr;
                imag[i + j + half] = ui - vi;
                const double next_wr = wr * wlen_r - wi * wlen_i;
                wi = wr * wlen_i + wi * wlen_r;
                wr = next_wr;
            }
        }
    }
}

bool IsWordBoundaryPiece(const std::string& token) {
    if (token.empty() || token == " " || token == "|") {
        return true;
    }
    // UTF-8 SentencePiece word-boundary prefix "▁" (0xE2 0x96 0x81).
    if (token.size() == 3 &&
        static_cast<unsigned char>(token[0]) == 0xE2 &&
        static_cast<unsigned char>(token[1]) == 0x96 &&
        static_cast<unsigned char>(token[2]) == 0x81) {
        return true;
    }
    return false;
}

bool StartsWithSentencePieceBoundary(const std::string& token) {
    return token.size() >= 3 &&
        static_cast<unsigned char>(token[0]) == 0xE2 &&
        static_cast<unsigned char>(token[1]) == 0x96 &&
        static_cast<unsigned char>(token[2]) == 0x81;
}

std::string StripSentencePiecePrefix(const std::string& token) {
    if (StartsWithSentencePieceBoundary(token)) {
        return token.substr(3);
    }
    return token;
}

}  // namespace

VerifierGates::VerifierGates() = default;
VerifierGates::~VerifierGates() = default;

bool VerifierGates::Init(const VerifierConfig& config, std::string* error_out) {
    initialized_ = false;
    has_centroid_ = false;
    ctc_backend_.reset();
    speaker_backend_.reset();
    dnsmos_backend_.reset();

    if (!ValidateSherpaCtcDir(config.sherpa_ctc_model_dir, error_out)) {
        return false;
    }
    if (!FileReadableNonEmpty(config.wespeaker_onnx_path)) {
        if (error_out) {
            *error_out = "Missing WeSpeaker ONNX model: " + config.wespeaker_onnx_path;
        }
        return false;
    }
    if (!FileReadableNonEmpty(config.dnsmos_p808_onnx_path)) {
        if (error_out) {
            *error_out = "Missing DNSMOS p808 ONNX model: " + config.dnsmos_p808_onnx_path;
        }
        return false;
    }

    std::array<float, kSpeakerEmbeddingDim> centroid{};
    if (!LoadMasterCentroid(config.master_centroid_bin_path, &centroid, error_out)) {
        return false;
    }

    config_ = config;
    master_centroid_ = centroid;
    has_centroid_ = true;
    initialized_ = true;
    return true;
}

bool VerifierGates::InitWithBackends(
    const std::string& master_centroid_bin_path,
    std::shared_ptr<CtcAlignerBackend> ctc_backend,
    std::shared_ptr<SpeakerEmbeddingBackend> speaker_backend,
    std::shared_ptr<DnsmosP808Backend> dnsmos_backend,
    std::string* error_out) {
    initialized_ = false;
    has_centroid_ = false;
    ctc_backend_.reset();
    speaker_backend_.reset();
    dnsmos_backend_.reset();

    if (!ctc_backend || !speaker_backend || !dnsmos_backend) {
        if (error_out) {
            *error_out = "All 3 inference backends (CTC, WeSpeaker, DNSMOS) must be non-null";
        }
        return false;
    }

    std::array<float, kSpeakerEmbeddingDim> centroid{};
    if (!LoadMasterCentroid(master_centroid_bin_path, &centroid, error_out)) {
        return false;
    }

    master_centroid_ = centroid;
    has_centroid_ = true;
    ctc_backend_ = std::move(ctc_backend);
    speaker_backend_ = std::move(speaker_backend);
    dnsmos_backend_ = std::move(dnsmos_backend);
    initialized_ = true;
    return true;
}

bool VerifierGates::VerifyCandidate(
    const float* pcm_44k,
    size_t total_samples,
    const std::string& expected_text,
    const VerifierThresholds& thresholds,
    GateResult* result_out,
    std::string* error_out) const {
    if (result_out == nullptr) {
        if (error_out) {
            *error_out = "result_out must not be null";
        }
        return false;
    }
    *result_out = GateResult{};
    if (pcm_44k == nullptr || total_samples == 0) {
        result_out->failure_reason = "EMPTY_AUDIO_BUFFER";
        if (error_out) {
            *error_out = "pcm_44k buffer is null or empty";
        }
        return false;
    }

    const TailAnalysis tail = AnalyzeTail(pcm_44k, total_samples, thresholds, kTailWindowMs, kSampleRateHz);
    result_out->tail_rms_db = tail.tail_rms_db;
    result_out->tail_hf_spectral_flux = tail.tail_hf_spectral_flux;
    result_out->tail_hf_glitch_detected = tail.hf_glitch_detected;

    if (!initialized_ || !has_centroid_) {
        result_out->passed_all_gates = false;
        result_out->failure_reason = "VERIFIER_NOT_INITIALIZED";
        if (error_out) {
            *error_out = "VerifierGates is not initialized";
        }
        return false;
    }

    if (!ctc_backend_ || !speaker_backend_ || !dnsmos_backend_) {
        // Constitutional Law 2: Never fabricate passing CER / WeSpeaker / DNSMOS metrics.
        result_out->passed_all_gates = false;
        result_out->failure_reason = "NATIVE_INFERENCE_BACKENDS_UNBOUND";
        if (error_out) {
            *error_out =
                "Native sherpa-onnx/ONNX Runtime C++ backends are not bound; run inference via "
                "Kotlin OrtSession + EvaluateWithModelOutputs or bind native C API backends";
        }
        return false;
    }

    CtcAlignmentOutput ctc_out;
    std::string backend_err;
    if (!ctc_backend_->Align(pcm_44k, total_samples, expected_text, &ctc_out, &backend_err)) {
        result_out->passed_all_gates = false;
        result_out->failure_reason = "CTC_ALIGNMENT_ERROR";
        if (error_out) {
            *error_out = "CTC aligner failed: " + backend_err;
        }
        return false;
    }

    std::array<float, kSpeakerEmbeddingDim> speaker_emb{};
    if (!speaker_backend_->ExtractEmbedding(pcm_44k, total_samples, &speaker_emb, &backend_err)) {
        result_out->passed_all_gates = false;
        result_out->failure_reason = "WESPEAKER_INFERENCE_ERROR";
        if (error_out) {
            *error_out = "WeSpeaker inference failed: " + backend_err;
        }
        return false;
    }

    float dnsmos_score = 0.0f;
    if (!dnsmos_backend_->ScoreAudio(pcm_44k, total_samples, &dnsmos_score, &backend_err)) {
        result_out->passed_all_gates = false;
        result_out->failure_reason = "DNSMOS_INFERENCE_ERROR";
        if (error_out) {
            *error_out = "DNSMOS p808 inference failed: " + backend_err;
        }
        return false;
    }

    return EvaluateWithModelOutputs(
        pcm_44k,
        total_samples,
        expected_text,
        ctc_out,
        speaker_emb,
        dnsmos_score,
        thresholds,
        result_out,
        error_out);
}

bool VerifierGates::EvaluateWithModelOutputs(
    const float* pcm_44k,
    size_t total_samples,
    const std::string& expected_text,
    const CtcAlignmentOutput& ctc_output,
    const std::array<float, kSpeakerEmbeddingDim>& speaker_embedding,
    float dnsmos_p808_score,
    const VerifierThresholds& thresholds,
    GateResult* result_out,
    std::string* error_out) const {
    if (result_out == nullptr) {
        if (error_out) {
            *error_out = "result_out must not be null";
        }
        return false;
    }
    *result_out = GateResult{};

    if (pcm_44k == nullptr || total_samples == 0) {
        result_out->failure_reason = "EMPTY_AUDIO_BUFFER";
        if (error_out) {
            *error_out = "pcm_44k buffer is null or empty";
        }
        return false;
    }
    if (!has_centroid_) {
        result_out->failure_reason = "MISSING_MASTER_CENTROID";
        if (error_out) {
            *error_out = "Master speaker centroid has not been loaded";
        }
        return false;
    }
    if (!std::isfinite(dnsmos_p808_score)) {
        result_out->failure_reason = "NON_FINITE_DNSMOS";
        if (error_out) {
            *error_out = "DNSMOS p808 score is not finite";
        }
        return false;
    }

    // Gate 1: Exact Levenshtein CER & max phoneme duration.
    const float cer = ComputeCer(expected_text, ctc_output.recognized_text);
    const float max_phoneme_ms = ctc_output.max_phoneme_duration_ms;

    // Gate 2: L2-normalized WeSpeaker ResNet34 cosine similarity vs 256-float master centroid.
    const float speaker_sim = CosineSimilarity(
        speaker_embedding.data(),
        master_centroid_.data(),
        kSpeakerEmbeddingDim);

    // Gate 3: Microsoft DNSMOS p808 + final-80ms tail RMS & HF spectral flux glitch check.
    const TailAnalysis tail = AnalyzeTail(pcm_44k, total_samples, thresholds, kTailWindowMs, kSampleRateHz);

    // Zero-crossing (+/- 88 samples) & YIN F0 peak scan on every aligned word span.
    std::vector<WordAlignment> aligned_tokens = ScanWordBoundaries(
        pcm_44k,
        static_cast<int64_t>(total_samples),
        ctc_output.word_spans,
        kZeroCrossScanSamples,
        kSampleRateHz);

    // Gate 4: Cadence (syllables/sec over active speech span).
    int64_t active_speech_samples = static_cast<int64_t>(total_samples);
    if (!aligned_tokens.empty()) {
        const int64_t span = aligned_tokens.back().end_sample - aligned_tokens.front().start_sample;
        if (span > 0) {
            active_speech_samples = span;
        }
    }
    const float syllables_per_sec =
        ComputeSyllablesPerSec(expected_text, active_speech_samples, kSampleRateHz);

    result_out->cer = cer;
    result_out->max_phoneme_duration_ms = max_phoneme_ms;
    result_out->speaker_cosine_sim = speaker_sim;
    result_out->dnsmos_p808 = dnsmos_p808_score;
    result_out->tail_rms_db = tail.tail_rms_db;
    result_out->tail_hf_spectral_flux = tail.tail_hf_spectral_flux;
    result_out->tail_hf_glitch_detected = tail.hf_glitch_detected;
    result_out->syllables_per_sec = syllables_per_sec;
    result_out->tokens = std::move(aligned_tokens);

    std::vector<std::string> failures;
    if (cer > thresholds.max_cer) {
        failures.emplace_back("SKIPPED_WORD_OR_CER");
    }
    if (max_phoneme_ms > thresholds.max_phoneme_duration_ms) {
        failures.emplace_back("DRAGGED_PHONEME");
    }
    if (speaker_sim < thresholds.min_speaker_cosine_sim) {
        failures.emplace_back("SPEAKER_SIMILARITY_LOW");
    }
    if (dnsmos_p808_score < thresholds.min_dnsmos_p808) {
        failures.emplace_back("DNSMOS_LOW");
    }
    if (tail.tail_rms_db > thresholds.max_tail_rms_db) {
        failures.emplace_back("TAIL_RMS_EXCEEDED");
    }
    if (tail.hf_glitch_detected) {
        failures.emplace_back("TAIL_HF_GLITCH");
    }
    if (syllables_per_sec < thresholds.min_syllables_per_sec ||
        syllables_per_sec > thresholds.max_syllables_per_sec) {
        failures.emplace_back("CADENCE_OUT_OF_BAND");
    }

    if (failures.empty()) {
        result_out->passed_all_gates = true;
        result_out->failure_reason.clear();
    } else {
        result_out->passed_all_gates = false;
        std::ostringstream oss;
        for (size_t i = 0; i < failures.size(); ++i) {
            if (i > 0) {
                oss << "|";
            }
            oss << failures[i];
        }
        result_out->failure_reason = oss.str();
    }
    return true;
}

bool VerifierGates::IsInitialized() const {
    return initialized_;
}

bool VerifierGates::HasMasterCentroid() const {
    return has_centroid_;
}

const std::array<float, kSpeakerEmbeddingDim>& VerifierGates::MasterCentroid() const {
    return master_centroid_;
}

bool VerifierGates::HasNativeInferenceRuntimes() const {
    return ctc_backend_ != nullptr && speaker_backend_ != nullptr && dnsmos_backend_ != nullptr;
}

std::string VerifierGates::NormalizeTranscript(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    bool last_was_space = true;
    for (size_t i = 0; i < text.size(); ++i) {
        const unsigned char uc = static_cast<unsigned char>(text[i]);
        if (std::isalnum(uc)) {
            out.push_back(static_cast<char>(std::tolower(uc)));
            last_was_space = false;
        } else if (uc == '\'' && !out.empty() && !last_was_space &&
                   i + 1 < text.size() && std::isalpha(static_cast<unsigned char>(text[i + 1]))) {
            out.push_back('\'');
            last_was_space = false;
        } else if (std::isspace(uc) || uc == '-') {
            if (!last_was_space) {
                out.push_back(' ');
                last_was_space = true;
            }
        }
    }
    while (!out.empty() && out.back() == ' ') {
        out.pop_back();
    }
    return out;
}

float VerifierGates::ComputeCer(
    const std::string& expected_text,
    const std::string& recognized_text) {
    const std::string ref = NormalizeTranscript(expected_text);
    const std::string hyp = NormalizeTranscript(recognized_text);

    if (ref.empty()) {
        return hyp.empty() ? 0.0f : 1.0f;
    }
    if (ref == hyp) {
        return 0.0f;
    }

    const size_t n = ref.size();
    const size_t m = hyp.size();
    std::vector<size_t> prev(m + 1);
    std::vector<size_t> curr(m + 1);
    std::iota(prev.begin(), prev.end(), size_t{0});

    for (size_t i = 1; i <= n; ++i) {
        curr[0] = i;
        for (size_t j = 1; j <= m; ++j) {
            const size_t cost = (ref[i - 1] == hyp[j - 1]) ? 0 : 1;
            curr[j] = std::min({
                prev[j] + 1,         // deletion
                curr[j - 1] + 1,     // insertion
                prev[j - 1] + cost   // substitution
            });
        }
        std::swap(prev, curr);
    }

    return static_cast<float>(static_cast<double>(prev[m]) / static_cast<double>(n));
}

bool VerifierGates::CtcGreedyDecode(
    const float* log_probs,
    size_t num_frames,
    size_t vocab_size,
    int32_t blank_id,
    const std::vector<std::string>& vocab,
    int32_t frame_shift_samples,
    std::string* decoded_text_out,
    float* max_phoneme_duration_ms_out,
    int32_t sample_rate_hz) {
    if (log_probs == nullptr || num_frames == 0 || vocab_size == 0 ||
        vocab.size() < vocab_size || frame_shift_samples <= 0 || sample_rate_hz <= 0 ||
        decoded_text_out == nullptr || max_phoneme_duration_ms_out == nullptr) {
        return false;
    }

    std::string decoded;
    int32_t prev_id = -1;
    size_t run_frames = 0;
    size_t max_non_blank_run = 0;

    for (size_t t = 0; t < num_frames; ++t) {
        const float* frame = log_probs + t * vocab_size;
        int32_t best_id = 0;
        float best_val = frame[0];
        for (size_t v = 1; v < vocab_size; ++v) {
            if (frame[v] > best_val) {
                best_val = frame[v];
                best_id = static_cast<int32_t>(v);
            }
        }

        if (best_id == prev_id) {
            if (best_id != blank_id) {
                ++run_frames;
                max_non_blank_run = std::max(max_non_blank_run, run_frames);
            }
        } else {
            if (best_id != blank_id) {
                run_frames = 1;
                max_non_blank_run = std::max(max_non_blank_run, run_frames);
                const std::string& tok = vocab[static_cast<size_t>(best_id)];
                if (IsWordBoundaryPiece(tok)) {
                    if (!decoded.empty() && decoded.back() != ' ') {
                        decoded.push_back(' ');
                    }
                } else if (StartsWithSentencePieceBoundary(tok)) {
                    if (!decoded.empty() && decoded.back() != ' ') {
                        decoded.push_back(' ');
                    }
                    decoded += StripSentencePiecePrefix(tok);
                } else {
                    decoded += tok;
                }
            } else {
                run_frames = 0;
            }
            prev_id = best_id;
        }
    }

    while (!decoded.empty() && decoded.back() == ' ') {
        decoded.pop_back();
    }

    const double frame_ms =
        (static_cast<double>(frame_shift_samples) * 1000.0) / static_cast<double>(sample_rate_hz);
    *decoded_text_out = std::move(decoded);
    *max_phoneme_duration_ms_out = static_cast<float>(static_cast<double>(max_non_blank_run) * frame_ms);
    return true;
}

bool VerifierGates::CtcViterbiForceAlign(
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
    int32_t sample_rate_hz) {
    if (log_probs == nullptr || num_frames == 0 || vocab_size == 0 ||
        blank_id < 0 || static_cast<size_t>(blank_id) >= vocab_size ||
        target_token_ids.empty() || vocab.size() < vocab_size ||
        frame_shift_samples <= 0 || total_samples == 0 || sample_rate_hz <= 0 ||
        word_spans_out == nullptr || max_phoneme_duration_ms_out == nullptr) {
        return false;
    }

    for (int32_t id : target_token_ids) {
        if (id < 0 || static_cast<size_t>(id) >= vocab_size || id == blank_id) {
            return false;
        }
    }

    const size_t num_tokens = target_token_ids.size();
    const size_t num_states = 2 * num_tokens + 1;
    if (num_frames < num_tokens) {
        return false;
    }

    std::vector<int32_t> ext(num_states, blank_id);
    for (size_t i = 0; i < num_tokens; ++i) {
        ext[2 * i + 1] = target_token_ids[i];
    }

    const double neg_inf = -std::numeric_limits<double>::infinity();
    std::vector<double> prev_dp(num_states, neg_inf);
    std::vector<double> curr_dp(num_states, neg_inf);
    std::vector<int32_t> backptr(num_frames * num_states, -1);

    prev_dp[0] = static_cast<double>(log_probs[blank_id]);
    if (num_states > 1) {
        prev_dp[1] = static_cast<double>(log_probs[ext[1]]);
    }

    for (size_t t = 1; t < num_frames; ++t) {
        std::fill(curr_dp.begin(), curr_dp.end(), neg_inf);
        const float* frame = log_probs + t * vocab_size;
        for (size_t s = 0; s < num_states; ++s) {
            double best_score = prev_dp[s];
            int32_t best_prev = static_cast<int32_t>(s);

            if (s >= 1 && prev_dp[s - 1] > best_score) {
                best_score = prev_dp[s - 1];
                best_prev = static_cast<int32_t>(s - 1);
            }
            if (s >= 2 && ext[s] != blank_id && ext[s] != ext[s - 2] &&
                prev_dp[s - 2] > best_score) {
                best_score = prev_dp[s - 2];
                best_prev = static_cast<int32_t>(s - 2);
            }

            if (std::isfinite(best_score)) {
                curr_dp[s] = best_score + static_cast<double>(frame[ext[s]]);
                backptr[t * num_states + s] = best_prev;
            }
        }
        std::swap(prev_dp, curr_dp);
    }

    size_t end_state = num_states - 1;
    if (num_states >= 2 && prev_dp[num_states - 2] > prev_dp[num_states - 1]) {
        end_state = num_states - 2;
    }
    if (!std::isfinite(prev_dp[end_state])) {
        return false;
    }

    std::vector<size_t> state_path(num_frames, 0);
    size_t cur = end_state;
    for (size_t t = num_frames; t-- > 0;) {
        state_path[t] = cur;
        if (t > 0) {
            const int32_t prev = backptr[t * num_states + cur];
            if (prev < 0) {
                return false;
            }
            cur = static_cast<size_t>(prev);
        }
    }

    struct TokenSpan {
        size_t first_frame = std::numeric_limits<size_t>::max();
        size_t last_frame = 0;
        bool seen = false;
    };
    std::vector<TokenSpan> token_spans(num_tokens);
    for (size_t t = 0; t < num_frames; ++t) {
        const size_t s = state_path[t];
        if ((s & 1u) == 1u) {
            const size_t tok_idx = (s - 1) / 2;
            token_spans[tok_idx].seen = true;
            token_spans[tok_idx].first_frame = std::min(token_spans[tok_idx].first_frame, t);
            token_spans[tok_idx].last_frame = std::max(token_spans[tok_idx].last_frame, t);
        }
    }

    const double frame_ms =
        (static_cast<double>(frame_shift_samples) * 1000.0) / static_cast<double>(sample_rate_hz);
    float max_dur_ms = 0.0f;

    word_spans_out->clear();
    std::string active_word;
    size_t active_start_frame = 0;
    size_t active_end_frame = 0;
    bool in_word = false;

    auto flush_word = [&]() {
        if (!in_word || active_word.empty()) {
            in_word = false;
            active_word.clear();
            return;
        }
        const int64_t start_sample = std::clamp<int64_t>(
            static_cast<int64_t>(active_start_frame) * frame_shift_samples,
            0,
            static_cast<int64_t>(total_samples - 1));
        const int64_t end_sample = std::clamp<int64_t>(
            static_cast<int64_t>(active_end_frame + 1) * frame_shift_samples,
            start_sample,
            static_cast<int64_t>(total_samples - 1));
        word_spans_out->push_back(RawWordSpan{active_word, start_sample, end_sample});
        active_word.clear();
        in_word = false;
    };

    for (size_t i = 0; i < num_tokens; ++i) {
        if (!token_spans[i].seen) {
            return false;
        }
        const std::string& piece = vocab[static_cast<size_t>(target_token_ids[i])];
        if (!IsWordBoundaryPiece(piece)) {
            const size_t span_frames = token_spans[i].last_frame - token_spans[i].first_frame + 1;
            const float dur_ms = static_cast<float>(static_cast<double>(span_frames) * frame_ms);
            max_dur_ms = std::max(max_dur_ms, dur_ms);
        }

        if (IsWordBoundaryPiece(piece)) {
            flush_word();
        } else if (StartsWithSentencePieceBoundary(piece)) {
            flush_word();
            active_word = StripSentencePiecePrefix(piece);
            active_start_frame = token_spans[i].first_frame;
            active_end_frame = token_spans[i].last_frame;
            in_word = !active_word.empty();
        } else {
            if (!in_word) {
                active_start_frame = token_spans[i].first_frame;
                in_word = true;
            }
            active_word += piece;
            active_end_frame = token_spans[i].last_frame;
        }
    }
    flush_word();

    *max_phoneme_duration_ms_out = max_dur_ms;
    return true;
}

bool VerifierGates::LoadMasterCentroid(
    const std::string& path,
    std::array<float, kSpeakerEmbeddingDim>* centroid_out,
    std::string* error_out) {
    if (centroid_out == nullptr) {
        if (error_out) {
            *error_out = "centroid_out must not be null";
        }
        return false;
    }
    if (path.empty()) {
        if (error_out) {
            *error_out = "master_centroid_bin_path must not be empty";
        }
        return false;
    }

    constexpr size_t kExpectedBytes = kSpeakerEmbeddingDim * sizeof(float);
    std::error_code ec;
    if (!std::filesystem::is_regular_file(path, ec) || ec) {
        if (error_out) {
            *error_out = "Missing master speaker centroid file: " + path;
        }
        return false;
    }
    const auto file_bytes = std::filesystem::file_size(path, ec);
    if (ec || file_bytes != kExpectedBytes) {
        if (error_out) {
            *error_out = "Invalid master_centroid.bin size (expected 1024 bytes for 256 float32s): " + path;
        }
        return false;
    }

    std::ifstream in(path, std::ios::binary);
    if (!in.good()) {
        if (error_out) {
            *error_out = "Failed to open master speaker centroid: " + path;
        }
        return false;
    }

    std::array<uint8_t, kExpectedBytes> raw{};
    in.read(reinterpret_cast<char*>(raw.data()), static_cast<std::streamsize>(kExpectedBytes));
    if (in.gcount() != static_cast<std::streamsize>(kExpectedBytes)) {
        if (error_out) {
            *error_out = "Short read while loading master_centroid.bin: " + path;
        }
        return false;
    }

    double norm_sq = 0.0;
    for (size_t i = 0; i < kSpeakerEmbeddingDim; ++i) {
        // Explicit little-endian IEEE-754 decode.
        const uint32_t bits =
            static_cast<uint32_t>(raw[i * 4 + 0]) |
            (static_cast<uint32_t>(raw[i * 4 + 1]) << 8) |
            (static_cast<uint32_t>(raw[i * 4 + 2]) << 16) |
            (static_cast<uint32_t>(raw[i * 4 + 3]) << 24);
        float value = 0.0f;
        std::memcpy(&value, &bits, sizeof(float));
        if (!std::isfinite(value)) {
            if (error_out) {
                *error_out = "Non-finite value in master_centroid.bin: " + path;
            }
            return false;
        }
        (*centroid_out)[i] = value;
        norm_sq += static_cast<double>(value) * static_cast<double>(value);
    }

    const double norm = std::sqrt(norm_sq);
    if (norm <= 1e-12) {
        if (error_out) {
            *error_out = "Zero-norm vector in master_centroid.bin: " + path;
        }
        return false;
    }

    // Store L2-normalized centroid for exact cosine dot product.
    for (float& v : *centroid_out) {
        v = static_cast<float>(static_cast<double>(v) / norm);
    }
    return true;
}

float VerifierGates::CosineSimilarity(const float* a, const float* b, size_t dim) {
    if (a == nullptr || b == nullptr || dim == 0) {
        return 0.0f;
    }
    double dot = 0.0;
    double norm_a = 0.0;
    double norm_b = 0.0;
    for (size_t i = 0; i < dim; ++i) {
        if (!std::isfinite(a[i]) || !std::isfinite(b[i])) {
            return 0.0f;
        }
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
    return static_cast<float>(std::clamp(dot / denom, -1.0, 1.0));
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

TailAnalysis VerifierGates::AnalyzeTail(
    const float* pcm_44k,
    size_t total_samples,
    const VerifierThresholds& thresholds,
    int32_t tail_ms,
    int32_t sample_rate_hz) {
    TailAnalysis analysis;
    analysis.tail_rms_db = ComputeTailRmsDb(pcm_44k, total_samples, tail_ms, sample_rate_hz);
    if (pcm_44k == nullptr || total_samples == 0 || tail_ms <= 0 || sample_rate_hz <= 0) {
        return analysis;
    }

    const size_t window_samples = std::min(
        total_samples,
        static_cast<size_t>((static_cast<int64_t>(sample_rate_hz) * tail_ms) / 1000));
    if (window_samples == 0) {
        return analysis;
    }
    const size_t tail_start = total_samples - window_samples;

    // 1. Peak short-window (10 ms) RMS inside the 80 ms tail catches short impulse clicks/pops.
    const size_t sub_win = std::max<size_t>(
        64,
        std::min(window_samples, static_cast<size_t>(sample_rate_hz / 100)));
    const size_t sub_hop = std::max<size_t>(32, sub_win / 2);
    double peak_sub_rms = 0.0;
    for (size_t pos = tail_start; pos + sub_win <= total_samples; pos += sub_hop) {
        double sq = 0.0;
        for (size_t i = 0; i < sub_win; ++i) {
            const double s = static_cast<double>(pcm_44k[pos + i]);
            sq += s * s;
        }
        peak_sub_rms = std::max(peak_sub_rms, std::sqrt(sq / static_cast<double>(sub_win)));
    }
    // Check final aligned sub-window touching end of buffer.
    if (total_samples >= sub_win) {
        const size_t pos = total_samples - sub_win;
        double sq = 0.0;
        for (size_t i = 0; i < sub_win; ++i) {
            const double s = static_cast<double>(pcm_44k[pos + i]);
            sq += s * s;
        }
        peak_sub_rms = std::max(peak_sub_rms, std::sqrt(sq / static_cast<double>(sub_win)));
    }
    analysis.tail_peak_frame_rms_db =
        (peak_sub_rms <= 1e-6) ? -120.0f : static_cast<float>(20.0 * std::log10(peak_sub_rms));

    // 2. Short-time 256-pt Hann FFT across tail for >= 4 kHz energy & positive spectral flux.
    constexpr size_t kFftSize = 256;
    constexpr size_t kFftHop = 128;
    if (window_samples >= kFftSize) {
        std::vector<double> hann(kFftSize);
        double hann_energy = 0.0;
        for (size_t i = 0; i < kFftSize; ++i) {
            hann[i] = 0.5 * (1.0 - std::cos((2.0 * kPi * static_cast<double>(i)) /
                                            static_cast<double>(kFftSize - 1)));
            hann_energy += hann[i] * hann[i];
        }

        const size_t nyquist_bin = kFftSize / 2;
        const size_t hf_start_bin = std::clamp<size_t>(
            static_cast<size_t>(std::ceil((4000.0 * static_cast<double>(kFftSize)) /
                                          static_cast<double>(sample_rate_hz))),
            1,
            nyquist_bin);

        std::vector<double> real(kFftSize);
        std::vector<double> imag(kFftSize);
        std::vector<double> prev_mag(nyquist_bin + 1, 0.0);
        std::vector<double> curr_mag(nyquist_bin + 1, 0.0);
        bool has_prev = false;
        double max_hf_flux = 0.0;
        double max_hf_rms = 0.0;

        for (size_t pos = tail_start; pos + kFftSize <= total_samples; pos += kFftHop) {
            for (size_t i = 0; i < kFftSize; ++i) {
                real[i] = static_cast<double>(pcm_44k[pos + i]) * hann[i];
                imag[i] = 0.0;
            }
            Radix2Fft(real, imag);

            double hf_power = 0.0;
            for (size_t k = hf_start_bin; k <= nyquist_bin; ++k) {
                const double mag_raw = std::hypot(real[k], imag[k]);
                curr_mag[k] = mag_raw / static_cast<double>(kFftSize);
                const double weight = (k == nyquist_bin) ? 1.0 : 2.0;
                hf_power += weight * mag_raw * mag_raw;
            }
            const double hf_rms = std::sqrt(hf_power / (static_cast<double>(kFftSize) * hann_energy));
            max_hf_rms = std::max(max_hf_rms, hf_rms);

            if (has_prev) {
                double flux_sq = 0.0;
                for (size_t k = hf_start_bin; k <= nyquist_bin; ++k) {
                    const double diff = curr_mag[k] - prev_mag[k];
                    if (diff > 0.0) {
                        flux_sq += diff * diff;
                    }
                }
                max_hf_flux = std::max(max_hf_flux, std::sqrt(flux_sq));
            }
            prev_mag.swap(curr_mag);
            has_prev = true;
        }

        analysis.tail_hf_spectral_flux = static_cast<float>(max_hf_flux);
        analysis.tail_hf_energy_db =
            (max_hf_rms <= 1e-6) ? -120.0f : static_cast<float>(20.0 * std::log10(max_hf_rms));
    }

    const bool hf_burst =
        (analysis.tail_hf_energy_db > thresholds.max_tail_hf_energy_db &&
         analysis.tail_hf_spectral_flux > thresholds.max_tail_hf_spectral_flux);
    const bool impulsive_click =
        (analysis.tail_peak_frame_rms_db > thresholds.max_tail_peak_frame_rms_db);
    analysis.hf_glitch_detected = hf_burst || impulsive_click;
    return analysis;
}

int32_t VerifierGates::CountWordSyllables(const std::string& word) {
    std::string clean;
    clean.reserve(word.size());
    for (unsigned char uc : word) {
        if (std::isalpha(uc)) {
            clean.push_back(static_cast<char>(std::tolower(uc)));
        }
    }
    if (clean.empty()) {
        return 0;
    }
    if (clean.size() <= 2) {
        return 1;
    }

    // Track whether "-le" preceded by consonant preserves a syllable (e.g., "little", "table").
    const bool ends_with_consonant_le =
        clean.size() >= 3 &&
        clean[clean.size() - 2] == 'l' &&
        clean[clean.size() - 1] == 'e' &&
        !IsAsciiVowel(clean[clean.size() - 3]);

    // Trim silent suffixes ("-ed" unless preceded by t/d, "-es" unless preceded by sibilant, trailing "-e").
    std::string stem = clean;
    if (stem.size() > 3 && stem.ends_with("ed")) {
        const char before = stem[stem.size() - 3];
        if (before != 't' && before != 'd') {
            stem.resize(stem.size() - 2);
        }
    } else if (stem.size() > 3 && stem.ends_with("es")) {
        const char before = stem[stem.size() - 3];
        const bool sibilant =
            before == 's' || before == 'x' || before == 'z' || before == 'c' || before == 'g' ||
            (stem.size() > 4 && before == 'h' &&
             (stem[stem.size() - 4] == 'c' || stem[stem.size() - 4] == 's'));
        if (!sibilant) {
            stem.resize(stem.size() - 2);
        }
    } else if (stem.size() > 2 && stem.back() == 'e' && !ends_with_consonant_le) {
        stem.pop_back();
    }

    int32_t syllables = 0;
    bool prev_vowel = false;
    for (size_t i = 0; i < stem.size(); ++i) {
        const bool is_vowel = IsAsciiVowel(stem[i]);
        if (is_vowel && !prev_vowel) {
            ++syllables;
        }
        prev_vowel = is_vowel;
    }

    // Handle common vowel hiatus pairs (e.g., "audio", "studio", "quiet", "science", "idea")
    // while excluding "-tion", "-sion", "-cious", "-tious".
    for (size_t i = 0; i + 1 < stem.size(); ++i) {
        const char a = stem[i];
        const char b = stem[i + 1];
        const bool is_hiatus =
            (a == 'i' && (b == 'a' || b == 'o' || b == 'u')) ||
            (a == 'e' && (b == 'o' || (b == 'a' && i + 2 == stem.size()))) ||
            (a == 'u' && b == 'a');
        if (!is_hiatus) {
            continue;
        }
        const bool is_tion_sion_cious =
            (i >= 1 && (stem[i - 1] == 't' || stem[i - 1] == 's' || stem[i - 1] == 'c') &&
             a == 'i' && b == 'o');
        if (!is_tion_sion_cious) {
            ++syllables;
        }
    }

    return std::max<int32_t>(1, syllables);
}

int32_t VerifierGates::CountTranscriptSyllables(const std::string& text) {
    const std::string norm = NormalizeTranscript(text);
    if (norm.empty()) {
        return 0;
    }
    int32_t total = 0;
    std::string token;
    for (char ch : norm) {
        if (ch == ' ') {
            if (!token.empty()) {
                total += CountWordSyllables(token);
                token.clear();
            }
        } else {
            token.push_back(ch);
        }
    }
    if (!token.empty()) {
        total += CountWordSyllables(token);
    }
    return total;
}

float VerifierGates::ComputeSyllablesPerSec(
    const std::string& expected_text,
    int64_t active_speech_samples,
    int32_t sample_rate_hz) {
    if (active_speech_samples <= 0 || sample_rate_hz <= 0) {
        return 0.0f;
    }
    const int32_t syllables = CountTranscriptSyllables(expected_text);
    if (syllables <= 0) {
        return 0.0f;
    }
    const double duration_sec =
        static_cast<double>(active_speech_samples) / static_cast<double>(sample_rate_hz);
    if (duration_sec <= 1e-6) {
        return 0.0f;
    }
    return static_cast<float>(static_cast<double>(syllables) / duration_sec);
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
    int64_t best_dist = 0;
    bool found_sign_change = false;

    for (int64_t i = lo + 1; i <= hi; ++i) {
        const float prev = pcm_44k[i - 1];
        const float curr = pcm_44k[i];
        if ((prev <= 0.0f && curr >= 0.0f) || (prev >= 0.0f && curr <= 0.0f)) {
            const float abs_prev = std::fabs(prev);
            const float abs_curr = std::fabs(curr);
            int64_t cand = (abs_prev < abs_curr) ? (i - 1) : i;
            if (abs_prev == abs_curr) {
                cand = (std::llabs((i - 1) - clamped_target) <= std::llabs(i - clamped_target))
                           ? (i - 1)
                           : i;
            }
            const float cand_abs = std::fabs(pcm_44k[cand]);
            const int64_t cand_dist = std::llabs(cand - clamped_target);

            const bool better =
                !found_sign_change ||
                (cand_abs + 1e-6f < best_abs) ||
                (std::fabs(cand_abs - best_abs) <= 1e-6f && cand_dist < best_dist);
            if (better) {
                found_sign_change = true;
                best_idx = cand;
                best_abs = cand_abs;
                best_dist = cand_dist;
            }
        }
    }
    if (found_sign_change) {
        return best_idx;
    }

    best_dist = std::numeric_limits<int64_t>::max();
    for (int64_t i = lo; i <= hi; ++i) {
        const float v = std::fabs(pcm_44k[i]);
        const int64_t dist = std::llabs(i - clamped_target);
        if (v + 1e-6f < best_abs || (std::fabs(v - best_abs) <= 1e-6f && dist < best_dist)) {
            best_abs = v;
            best_idx = i;
            best_dist = dist;
        }
    }
    return best_idx;
}

int64_t VerifierGates::FindF0PeakSample(
    const float* pcm_44k,
    int64_t total_samples,
    int64_t start_sample,
    int64_t end_sample,
    int32_t sample_rate_hz) {
    if (pcm_44k == nullptr || total_samples <= 0) {
        return 0;
    }
    const int64_t lo = std::clamp<int64_t>(start_sample, 0, total_samples - 1);
    const int64_t hi = std::clamp<int64_t>(end_sample, lo, total_samples - 1);
    const int64_t span_len = hi - lo + 1;
    if (span_len <= 1 || sample_rate_hz <= 0) {
        return lo;
    }

    auto find_abs_peak_in_range = [&](int64_t range_lo, int64_t range_hi) -> int64_t {
        const int64_t rlo = std::clamp<int64_t>(range_lo, lo, hi);
        const int64_t rhi = std::clamp<int64_t>(range_hi, rlo, hi);
        int64_t peak_idx = rlo;
        float peak_amp = std::fabs(pcm_44k[rlo]);
        for (int64_t i = rlo + 1; i <= rhi; ++i) {
            const float a = std::fabs(pcm_44k[i]);
            if (a > peak_amp) {
                peak_amp = a;
                peak_idx = i;
            }
        }
        return peak_idx;
    };

    // Short span fallback: peak amplitude directly inside word.
    if (span_len < 256) {
        return find_abs_peak_in_range(lo, hi);
    }

    // YIN pitch-energy peak tracking across overlapping frames in [lo, hi].
    const int64_t frame_len = std::min<int64_t>(1280, span_len);
    const int64_t half_win = frame_len / 2;
    const int64_t hop = std::max<int64_t>(128, frame_len / 4);
    const int64_t tau_min = std::clamp<int64_t>(sample_rate_hz / 450, 2, half_win - 1);
    const int64_t tau_max = std::clamp<int64_t>(sample_rate_hz / 75, tau_min, half_win - 1);

    std::vector<double> diff(static_cast<size_t>(tau_max + 1), 0.0);
    std::vector<double> cmndf(static_cast<size_t>(tau_max + 1), 1.0);

    double best_voiced_score = -1.0;
    int64_t best_voiced_center = lo + span_len / 2;
    double best_rms = -1.0;
    int64_t best_rms_center = lo + span_len / 2;

    auto evaluate_frame = [&](int64_t f_start) {
        double energy_sq = 0.0;
        for (int64_t i = 0; i < frame_len; ++i) {
            const double s = static_cast<double>(pcm_44k[f_start + i]);
            energy_sq += s * s;
        }
        const double rms = std::sqrt(energy_sq / static_cast<double>(frame_len));
        const int64_t center = f_start + frame_len / 2;

        if (rms > best_rms) {
            best_rms = rms;
            best_rms_center = center;
        }
        if (rms <= 1e-5) {
            return;
        }

        // YIN step 1 & 2: difference function & cumulative mean normalized difference.
        double running_sum = 0.0;
        diff[0] = 0.0;
        cmndf[0] = 1.0;
        for (int64_t tau = 1; tau <= tau_max; ++tau) {
            double d = 0.0;
            for (int64_t j = 0; j < half_win; ++j) {
                const double delta =
                    static_cast<double>(pcm_44k[f_start + j]) -
                    static_cast<double>(pcm_44k[f_start + j + tau]);
                d += delta * delta;
            }
            diff[static_cast<size_t>(tau)] = d;
            running_sum += d;
            cmndf[static_cast<size_t>(tau)] =
                (running_sum > 1e-12) ? (d * static_cast<double>(tau) / running_sum) : 1.0;
        }

        // YIN step 3: absolute threshold search (0.15) for periodic lag in human speech F0 band.
        int64_t chosen_tau = -1;
        for (int64_t tau = tau_min; tau <= tau_max; ++tau) {
            if (cmndf[static_cast<size_t>(tau)] < 0.15) {
                while (tau + 1 <= tau_max &&
                       cmndf[static_cast<size_t>(tau + 1)] < cmndf[static_cast<size_t>(tau)]) {
                    ++tau;
                }
                chosen_tau = tau;
                break;
            }
        }
        if (chosen_tau < 0) {
            chosen_tau = tau_min;
            for (int64_t tau = tau_min + 1; tau <= tau_max; ++tau) {
                if (cmndf[static_cast<size_t>(tau)] < cmndf[static_cast<size_t>(chosen_tau)]) {
                    chosen_tau = tau;
                }
            }
        }

        const double min_cmndf = cmndf[static_cast<size_t>(chosen_tau)];
        if (min_cmndf < 0.35) {
            const double periodicity = std::clamp(1.0 - min_cmndf, 0.0, 1.0);
            const double voiced_score = periodicity * rms;
            if (voiced_score > best_voiced_score) {
                best_voiced_score = voiced_score;
                best_voiced_center = center;
            }
        }
    };

    for (int64_t pos = lo; pos + frame_len <= hi + 1; pos += hop) {
        evaluate_frame(pos);
    }
    const int64_t last_start = hi + 1 - frame_len;
    if (last_start >= lo && ((last_start - lo) % hop != 0)) {
        evaluate_frame(last_start);
    }

    const int64_t chosen_center = (best_voiced_score > 0.0) ? best_voiced_center : best_rms_center;
    const int64_t search_half = std::max<int64_t>(64, hop / 2);
    return find_abs_peak_in_range(chosen_center - search_half, chosen_center + search_half);
}

std::vector<WordAlignment> VerifierGates::ScanWordBoundaries(
    const float* pcm_44k,
    int64_t total_samples,
    const std::vector<RawWordSpan>& raw_spans,
    int32_t scan_radius,
    int32_t sample_rate_hz) {
    std::vector<WordAlignment> out;
    out.reserve(raw_spans.size());
    if (pcm_44k == nullptr || total_samples <= 0) {
        return out;
    }

    for (const auto& span : raw_spans) {
        const int64_t start = std::clamp<int64_t>(span.start_sample, 0, total_samples - 1);
        const int64_t end = std::clamp<int64_t>(span.end_sample, start, total_samples - 1);
        WordAlignment aligned;
        aligned.word = span.word;
        aligned.start_sample = start;
        aligned.end_sample = end;
        aligned.zero_cross_in = FindNearestZeroCrossing(pcm_44k, total_samples, start, scan_radius);
        aligned.zero_cross_out = FindNearestZeroCrossing(pcm_44k, total_samples, end, scan_radius);
        if (aligned.zero_cross_out < aligned.zero_cross_in) {
            aligned.zero_cross_out = aligned.zero_cross_in;
        }
        aligned.f0_peak_sample = FindF0PeakSample(pcm_44k, total_samples, start, end, sample_rate_hz);
        out.push_back(std::move(aligned));
    }
    return out;
}

}  // namespace voicestudio::verifier
