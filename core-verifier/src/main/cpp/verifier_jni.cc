#include <jni.h>

#include <cmath>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

#include "verifier_gates.h"

namespace {

using voicestudio::verifier::GateResult;
using voicestudio::verifier::RawWordSpan;
using voicestudio::verifier::TailAnalysis;
using voicestudio::verifier::VerifierConfig;
using voicestudio::verifier::VerifierGates;
using voicestudio::verifier::VerifierThresholds;
using voicestudio::verifier::WordAlignment;

std::string JStringToStdString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string JsonEscape(const std::string& input) {
    std::ostringstream oss;
    for (unsigned char c : input) {
        switch (c) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\b': oss << "\\b"; break;
            case '\f': oss << "\\f"; break;
            case '\n': oss << "\\n"; break;
            case '\r': oss << "\\r"; break;
            case '\t': oss << "\\t"; break;
            default:
                if (c < 0x20) {
                    oss << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(c) << std::dec;
                } else {
                    oss << static_cast<char>(c);
                }
                break;
        }
    }
    return oss.str();
}

float SanitizeJsonFloat(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

std::string SerializeGateResponse(
    bool ok,
    const GateResult& result,
    const std::string& error_message) {
    std::ostringstream oss;
    oss << std::setprecision(7);
    oss << "{";
    oss << "\"ok\":" << (ok ? "true" : "false") << ",";
    oss << "\"error\":\"" << JsonEscape(error_message) << "\",";
    oss << "\"cer\":" << SanitizeJsonFloat(result.cer) << ",";
    oss << "\"maxPhonemeDurationMs\":" << SanitizeJsonFloat(result.max_phoneme_duration_ms) << ",";
    oss << "\"speakerCosineSim\":" << SanitizeJsonFloat(result.speaker_cosine_sim) << ",";
    oss << "\"dnsmosP808\":" << SanitizeJsonFloat(result.dnsmos_p808) << ",";
    oss << "\"tailRmsDb\":" << SanitizeJsonFloat(result.tail_rms_db) << ",";
    oss << "\"tailHfSpectralFlux\":" << SanitizeJsonFloat(result.tail_hf_spectral_flux) << ",";
    oss << "\"tailHfGlitchDetected\":" << (result.tail_hf_glitch_detected ? "true" : "false") << ",";
    oss << "\"syllablesPerSec\":" << SanitizeJsonFloat(result.syllables_per_sec) << ",";
    oss << "\"passedAllGates\":" << (result.passed_all_gates ? "true" : "false") << ",";
    oss << "\"failureReason\":\"" << JsonEscape(result.failure_reason) << "\",";
    oss << "\"tokens\":[";
    for (size_t i = 0; i < result.tokens.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        const WordAlignment& tok = result.tokens[i];
        oss << "{";
        oss << "\"word\":\"" << JsonEscape(tok.word) << "\",";
        oss << "\"startSample\":" << tok.start_sample << ",";
        oss << "\"endSample\":" << tok.end_sample << ",";
        oss << "\"zeroCrossIn\":" << tok.zero_cross_in << ",";
        oss << "\"zeroCrossOut\":" << tok.zero_cross_out << ",";
        oss << "\"f0PeakSample\":" << tok.f0_peak_sample;
        oss << "}";
    }
    oss << "]}";
    return oss.str();
}

std::string SerializeScanResponse(
    const TailAnalysis& tail,
    const std::vector<WordAlignment>& boundaries) {
    std::ostringstream oss;
    oss << std::setprecision(7);
    oss << "{";
    oss << "\"tailRmsDb\":" << SanitizeJsonFloat(tail.tail_rms_db) << ",";
    oss << "\"tailPeakFrameRmsDb\":" << SanitizeJsonFloat(tail.tail_peak_frame_rms_db) << ",";
    oss << "\"tailHfEnergyDb\":" << SanitizeJsonFloat(tail.tail_hf_energy_db) << ",";
    oss << "\"tailHfSpectralFlux\":" << SanitizeJsonFloat(tail.tail_hf_spectral_flux) << ",";
    oss << "\"tailHfGlitchDetected\":" << (tail.hf_glitch_detected ? "true" : "false") << ",";
    oss << "\"boundaries\":[";
    for (size_t i = 0; i < boundaries.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        const WordAlignment& b = boundaries[i];
        oss << "{";
        oss << "\"startSample\":" << b.start_sample << ",";
        oss << "\"endSample\":" << b.end_sample << ",";
        oss << "\"zeroCrossIn\":" << b.zero_cross_in << ",";
        oss << "\"zeroCrossOut\":" << b.zero_cross_out << ",";
        oss << "\"f0PeakSample\":" << b.f0_peak_sample;
        oss << "}";
    }
    oss << "]}";
    return oss.str();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicestudio_verifier_VerifierJni_nativeInitVerifier(
    JNIEnv* env,
    jobject /* thiz */,
    jstring sherpa_ctc_model_dir,
    jstring wespeaker_onnx_path,
    jstring dnsmos_p808_onnx_path,
    jstring master_centroid_bin_path) {
    auto* verifier = new VerifierGates();
    VerifierConfig config{
        JStringToStdString(env, sherpa_ctc_model_dir),
        JStringToStdString(env, wespeaker_onnx_path),
        JStringToStdString(env, dnsmos_p808_onnx_path),
        JStringToStdString(env, master_centroid_bin_path),
    };
    std::string error;
    if (!verifier->Init(config, &error)) {
        delete verifier;
        return 0L;
    }
    return reinterpret_cast<jlong>(verifier);
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_verifier_VerifierJni_nativeVerifyCandidate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong verifier_handle,
    jfloatArray pcm_44k,
    jstring expected_text,
    jfloat max_cer,
    jfloat max_phoneme_duration_ms,
    jfloat min_speaker_cosine_sim,
    jfloat min_dnsmos_p808,
    jfloat max_tail_rms_db,
    jfloat min_syllables_per_sec,
    jfloat max_syllables_per_sec) {
    GateResult result;
    if (verifier_handle == 0L) {
        return env->NewStringUTF(
            SerializeGateResponse(false, result, "Invalid verifier handle (0)").c_str());
    }
    if (pcm_44k == nullptr) {
        return env->NewStringUTF(
            SerializeGateResponse(false, result, "pcm44k array is null").c_str());
    }

    const jsize length = env->GetArrayLength(pcm_44k);
    if (length <= 0) {
        return env->NewStringUTF(
            SerializeGateResponse(false, result, "pcm44k array is empty").c_str());
    }

    jfloat* samples = env->GetFloatArrayElements(pcm_44k, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF(
            SerializeGateResponse(false, result, "Failed to access pcm44k float elements").c_str());
    }

    auto* verifier = reinterpret_cast<VerifierGates*>(verifier_handle);
    VerifierThresholds thresholds;
    thresholds.max_cer = max_cer;
    thresholds.max_phoneme_duration_ms = max_phoneme_duration_ms;
    thresholds.min_speaker_cosine_sim = min_speaker_cosine_sim;
    thresholds.min_dnsmos_p808 = min_dnsmos_p808;
    thresholds.max_tail_rms_db = max_tail_rms_db;
    thresholds.min_syllables_per_sec = min_syllables_per_sec;
    thresholds.max_syllables_per_sec = max_syllables_per_sec;

    std::string error;
    const bool ok = verifier->VerifyCandidate(
        samples,
        static_cast<size_t>(length),
        JStringToStdString(env, expected_text),
        thresholds,
        &result,
        &error);

    env->ReleaseFloatArrayElements(pcm_44k, samples, JNI_ABORT);
    return env->NewStringUTF(SerializeGateResponse(ok, result, error).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_verifier_VerifierJni_nativeScanZeroCrossingsAndTail(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcm_44k,
    jlongArray word_start_samples,
    jlongArray word_end_samples) {
    if (pcm_44k == nullptr) {
        return env->NewStringUTF(SerializeScanResponse(TailAnalysis{}, {}).c_str());
    }
    const jsize pcm_len = env->GetArrayLength(pcm_44k);
    if (pcm_len <= 0) {
        return env->NewStringUTF(SerializeScanResponse(TailAnalysis{}, {}).c_str());
    }

    jfloat* samples = env->GetFloatArrayElements(pcm_44k, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF(SerializeScanResponse(TailAnalysis{}, {}).c_str());
    }

    const TailAnalysis tail = VerifierGates::AnalyzeTail(
        samples,
        static_cast<size_t>(pcm_len),
        VerifierThresholds{});

    std::vector<RawWordSpan> raw_spans;
    if (word_start_samples != nullptr && word_end_samples != nullptr) {
        const jsize n_starts = env->GetArrayLength(word_start_samples);
        const jsize n_ends = env->GetArrayLength(word_end_samples);
        const jsize count = (n_starts < n_ends) ? n_starts : n_ends;
        if (count > 0) {
            jlong* starts = env->GetLongArrayElements(word_start_samples, nullptr);
            jlong* ends = env->GetLongArrayElements(word_end_samples, nullptr);
            if (starts != nullptr && ends != nullptr) {
                raw_spans.reserve(static_cast<size_t>(count));
                for (jsize i = 0; i < count; ++i) {
                    raw_spans.push_back(RawWordSpan{
                        "",
                        static_cast<int64_t>(starts[i]),
                        static_cast<int64_t>(ends[i]),
                    });
                }
            }
            if (starts != nullptr) {
                env->ReleaseLongArrayElements(word_start_samples, starts, JNI_ABORT);
            }
            if (ends != nullptr) {
                env->ReleaseLongArrayElements(word_end_samples, ends, JNI_ABORT);
            }
        }
    }

    const std::vector<WordAlignment> boundaries = VerifierGates::ScanWordBoundaries(
        samples,
        static_cast<int64_t>(pcm_len),
        raw_spans);

    env->ReleaseFloatArrayElements(pcm_44k, samples, JNI_ABORT);
    return env->NewStringUTF(SerializeScanResponse(tail, boundaries).c_str());
}

JNIEXPORT void JNICALL
Java_com_voicestudio_verifier_VerifierJni_nativeDestroyVerifier(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong verifier_handle) {
    if (verifier_handle == 0L) {
        return;
    }
    auto* verifier = reinterpret_cast<VerifierGates*>(verifier_handle);
    delete verifier;
}

}  // extern "C"
