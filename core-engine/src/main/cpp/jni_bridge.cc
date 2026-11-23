#include <jni.h>

#include <string>

#include "dsp_mastering.h"
#include "voice_engine.h"

namespace {

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

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring base_gguf_path,
    jstring lora_gguf_path,
    jstring vocoder_tflite_path,
    jstring room_ir_pcm_path) {
    auto* engine = new voicestudio::engine::VoiceEngine();
    voicestudio::engine::EngineConfig cfg{
        JStringToStdString(env, base_gguf_path),
        JStringToStdString(env, lora_gguf_path),
        JStringToStdString(env, vocoder_tflite_path),
        JStringToStdString(env, room_ir_pcm_path),
    };
    const auto status = engine->Init(cfg);
    if (!status.ok) {
        delete engine;
        return 0L;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeHotSwapLora(
    JNIEnv* env,
    jobject /* thiz */,
    jlong engine_handle,
    jstring new_lora_gguf_path) {
    if (engine_handle == 0L) {
        return JNI_FALSE;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);
    const auto status = engine->HotSwapLora(JStringToStdString(env, new_lora_gguf_path));
    return status.ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeEvictWeightPages(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong engine_handle) {
    if (engine_handle == 0L) {
        return;
    }
    auto* engine = reinterpret_cast<voicestudio::engine::VoiceEngine*>(engine_handle);
    engine->EvictWeightPages();
}

JNIEXPORT void JNICALL
Java_com_voicestudio_engine_VoiceEngineJni_nativeClose(
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
