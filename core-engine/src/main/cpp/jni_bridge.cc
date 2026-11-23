#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "dsp_mastering.h"
#include "voice_engine.h"

namespace {

thread_local std::string g_last_error;

void SetLastError(const std::string& message) {
    g_last_error = message;
}

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

jlong InitEngineInternal(
    JNIEnv* env,
    jstring base_gguf_path,
    jstring lora_gguf_path,
    jstring vocoder_tflite_path,
    jstring room_ir_pcm_path,
    jstring llama_lib_path) {
    auto* engine = new voicestudio::engine::VoiceEngine();
    voicestudio::engine::EngineConfig cfg;
    cfg.base_gguf_path = JStringToStdString(env, base_gguf_path);
    cfg.lora_gguf_path = JStringToStdString(env, lora_gguf_path);
    cfg.vocoder_tflite_path = JStringToStdString(env, vocoder_tflite_path);
    cfg.room_ir_pcm_path = JStringToStdString(env, room_ir_pcm_path);
    cfg.llama_library_path = JStringToStdString(env, llama_lib_path);

    const auto status = engine->Init(cfg);
    if (!status.ok) {
        SetLastError(status.error_message);
        delete engine;
        return 0L;
    }
    SetLastError("");
    return reinterpret_cast<jlong>(engine);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeGetLastError(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring base_gguf_path,
    jstring lora_gguf_path,
    jstring vocoder_tflite_path,
    jstring room_ir_pcm_path) {
    return InitEngineInternal(
        env,
        base_gguf_path,
        lora_gguf_path,
        vocoder_tflite_path,
        room_ir_pcm_path,
        nullptr);
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeInitWithLlamaLib(
    JNIEnv* env,
    jobject /* thiz */,
    jstring base_gguf_path,
    jstring lora_gguf_path,
    jstring vocoder_tflite_path,
    jstring room_ir_pcm_path,
    jstring llama_lib_path) {
    return InitEngineInternal(
        env,
        base_gguf_path,
        lora_gguf_path,
        vocoder_tflite_path,
        room_ir_pcm_path,
        llama_lib_path);
}

JNIEXPORT jboolean JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeHotSwapLora(
    JNIEnv* env,
    jobject /* thiz */,
    jlong engine_handle,
    jstring new_lora_gguf_path) {
    if (engine_handle == 0L) {
        SetLastError("Invalid VoiceEngine handle (0)");
        return JNI_FALSE;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);
    const auto status = engine->HotSwapLora(JStringToStdString(env, new_lora_gguf_path));
    if (!status.ok) {
        SetLastError(status.error_message);
        return JNI_FALSE;
    }
    SetLastError("");
    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeSynthesizeCandidates(
    JNIEnv* env,
    jobject /* thiz */,
    jlong engine_handle,
    jstring normalized_text,
    jstring prefix_pcm_path,
    jint num_candidates) {
    if (engine_handle == 0L) {
        SetLastError("Invalid VoiceEngine handle (0)");
        return nullptr;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);

    std::vector<voicestudio::engine::SynthesizedCandidate> candidates;
    const auto status = engine->SynthesizeCandidates(
        JStringToStdString(env, normalized_text),
        JStringToStdString(env, prefix_pcm_path),
        static_cast<int32_t>(num_candidates),
        candidates);
    if (!status.ok) {
        SetLastError(status.error_message);
        return nullptr;
    }

    jclass candidate_cls = env->FindClass("com/voicestudio/engine/NativeCandidateTokens");
    if (candidate_cls == nullptr) {
        SetLastError("Failed to find JNI class com/voicestudio/engine/NativeCandidateTokens");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(candidate_cls, "<init>", "([IJF)V");
    if (ctor == nullptr) {
        SetLastError("Failed to find NativeCandidateTokens([IJF)V constructor");
        env->DeleteLocalRef(candidate_cls);
        return nullptr;
    }

    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(candidates.size()), candidate_cls, nullptr);
    if (result == nullptr) {
        SetLastError("Failed to allocate NativeCandidateTokens[] array");
        env->DeleteLocalRef(candidate_cls);
        return nullptr;
    }

    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto& cand = candidates[i];
        jintArray tokens_arr =
            env->NewIntArray(static_cast<jsize>(cand.discrete_audio_tokens.size()));
        if (tokens_arr == nullptr) {
            SetLastError("Failed to allocate discreteAudioTokens jintArray");
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(candidate_cls);
            return nullptr;
        }
        if (!cand.discrete_audio_tokens.empty()) {
            env->SetIntArrayRegion(
                tokens_arr,
                0,
                static_cast<jsize>(cand.discrete_audio_tokens.size()),
                reinterpret_cast<const jint*>(cand.discrete_audio_tokens.data()));
        }

        jobject item = env->NewObject(
            candidate_cls,
            ctor,
            tokens_arr,
            static_cast<jlong>(cand.seed),
            static_cast<jfloat>(cand.temperature));
        env->DeleteLocalRef(tokens_arr);
        if (item == nullptr || env->ExceptionCheck()) {
            SetLastError("Failed to construct NativeCandidateTokens object");
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(candidate_cls);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }

    env->DeleteLocalRef(candidate_cls);
    SetLastError("");
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeMasterAndWritePcm(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray input_pcm,
    jstring room_ir_pcm_path,
    jstring room_tone_pcm_path,
    jstring output_pcm_path,
    jfloat target_lufs,
    jfloat noise_floor_dbfs) {
    if (input_pcm == nullptr) {
        SetLastError("input_pcm must not be null");
        return nullptr;
    }
    const jsize len = env->GetArrayLength(input_pcm);
    if (len <= 0) {
        SetLastError("input_pcm must not be empty");
        return nullptr;
    }

    std::vector<float> pcm(static_cast<size_t>(len));
    env->GetFloatArrayRegion(input_pcm, 0, len, pcm.data());
    if (env->ExceptionCheck()) {
        SetLastError("Failed copying input_pcm float array from JVM");
        return nullptr;
    }

    std::vector<float> room_ir;
    const std::string ir_path = JStringToStdString(env, room_ir_pcm_path);
    if (!ir_path.empty()) {
        if (!voicestudio::engine::ReadPcmFile44k(ir_path, room_ir) || room_ir.empty()) {
            SetLastError("Missing or unreadable room IR PCM: " + ir_path);
            return nullptr;
        }
    }

    std::vector<float> room_tone;
    const std::string tone_path = JStringToStdString(env, room_tone_pcm_path);
    if (!tone_path.empty()) {
        if (!voicestudio::engine::ReadPcmFile44k(tone_path, room_tone) || room_tone.empty()) {
            SetLastError("Missing or unreadable room tone PCM: " + tone_path);
            return nullptr;
        }
    }

    voicestudio::engine::MasterClausePcm(
        pcm,
        room_ir.empty() ? nullptr : room_ir.data(),
        room_ir.size(),
        room_tone.empty() ? nullptr : room_tone.data(),
        room_tone.size(),
        static_cast<float>(target_lufs),
        static_cast<float>(noise_floor_dbfs));

    const std::string out_path = JStringToStdString(env, output_pcm_path);
    if (!out_path.empty()) {
        if (!voicestudio::engine::WriteAlignedPcm44k(out_path, pcm)) {
            SetLastError("Failed writing 64-byte-aligned PCM file: " + out_path);
            return nullptr;
        }
    }

    jfloatArray out_arr = env->NewFloatArray(static_cast<jsize>(pcm.size()));
    if (out_arr == nullptr) {
        SetLastError("Failed to allocate mastered PCM jfloatArray");
        return nullptr;
    }
    env->SetFloatArrayRegion(out_arr, 0, static_cast<jsize>(pcm.size()), pcm.data());
    SetLastError("");
    return out_arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeExtractAcousticPrefixTail(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray input_pcm,
    jint tail_samples) {
    if (input_pcm == nullptr || tail_samples <= 0) {
        return env->NewFloatArray(0);
    }
    const jsize len = env->GetArrayLength(input_pcm);
    if (len <= 0) {
        return env->NewFloatArray(0);
    }
    std::vector<float> pcm(static_cast<size_t>(len));
    env->GetFloatArrayRegion(input_pcm, 0, len, pcm.data());
    const auto tail = voicestudio::engine::ExtractAcousticPrefixTail(
        pcm.data(), pcm.size(), static_cast<size_t>(tail_samples));
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(tail.size()));
    if (out != nullptr && !tail.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(tail.size()), tail.data());
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeFindNearestZeroCrossing(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcm_array,
    jlong target_sample,
    jint scan_radius) {
    if (pcm_array == nullptr) {
        return 0L;
    }
    const jsize len = env->GetArrayLength(pcm_array);
    if (len <= 0) {
        return 0L;
    }
    jfloat* elems = env->GetFloatArrayElements(pcm_array, nullptr);
    if (elems == nullptr) {
        return 0L;
    }
    const int64_t idx = voicestudio::engine::FindNearestZeroCrossing(
        elems,
        static_cast<int64_t>(len),
        static_cast<int64_t>(target_sample),
        static_cast<int32_t>(scan_radius));
    env->ReleaseFloatArrayElements(pcm_array, elems, JNI_ABORT);
    return static_cast<jlong>(idx);
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeFindNearestZeroCrossingInFile(
    JNIEnv* env,
    jobject /* thiz */,
    jstring pcm_file_path,
    jlong target_sample,
    jint scan_radius) {
    const std::string path = JStringToStdString(env, pcm_file_path);
    const int64_t idx = voicestudio::engine::FindNearestZeroCrossingInFile(
        path,
        static_cast<int64_t>(target_sample),
        static_cast<int32_t>(scan_radius));
    if (idx < 0) {
        SetLastError("Failed scanning zero crossing in PCM file: " + path);
    }
    return static_cast<jlong>(idx);
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeEvictWeightPages(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong engine_handle) {
    if (engine_handle == 0L) {
        return 0L;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);
    return static_cast<jlong>(engine->EvictWeightPages());
}

JNIEXPORT void JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong engine_handle) {
    if (engine_handle == 0L) {
        return;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);
    delete engine;
}

}  // extern "C"
