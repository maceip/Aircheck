#include "edl_player.h"

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace {

using voicestudio::edl::EdlPlayer;
using voicestudio::edl::SliceDescriptor;

EdlPlayer* FromHandle(jlong handle) {
    return reinterpret_cast<EdlPlayer*>(static_cast<intptr_t>(handle));
}

std::string JStringToStdString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

jstring MakeJString(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voicestudio_edl_EdlController_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* player = new EdlPlayer();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(player));
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_edl_EdlController_nativeInit(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring room_tone_loop_path) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return MakeJString(env, "EdlPlayer native handle is closed");
    }
    const std::string path = JStringToStdString(env, room_tone_loop_path);
    std::string error;
    if (!player->Init(path, &error)) {
        return MakeJString(env, error.empty() ? "EdlPlayer::Init failed" : error);
    }
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_edl_EdlController_nativeStart(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return MakeJString(env, "EdlPlayer native handle is closed");
    }
    std::string error;
    if (!player->Start(&error)) {
        return MakeJString(env, error.empty() ? "EdlPlayer::Start failed" : error);
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_voicestudio_edl_EdlController_nativePause(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player != nullptr) {
        player->Pause();
    }
}

JNIEXPORT void JNICALL
Java_com_voicestudio_edl_EdlController_nativeClose(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player != nullptr) {
        player->Close();
        delete player;
    }
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_edl_EdlController_nativePreloadFiles(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobjectArray pcm_paths) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return MakeJString(env, "EdlPlayer native handle is closed");
    }
    if (pcm_paths == nullptr) {
        return nullptr;
    }

    const jsize count = env->GetArrayLength(pcm_paths);
    std::vector<std::string> paths;
    paths.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto* elem = static_cast<jstring>(env->GetObjectArrayElement(pcm_paths, i));
        paths.push_back(JStringToStdString(env, elem));
        if (elem != nullptr) {
            env->DeleteLocalRef(elem);
        }
    }

    std::string error;
    if (!player->PreloadFiles(paths, &error)) {
        return MakeJString(env, error.empty() ? "EdlPlayer::PreloadFiles failed" : error);
    }
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_voicestudio_edl_EdlController_nativeUpdateSnapshot(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobjectArray pcm_paths,
    jlongArray zero_cross_in_samples,
    jlongArray zero_cross_out_samples,
    jintArray gap_after_samples,
    jfloat global_gap_scale) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return MakeJString(env, "EdlPlayer native handle is closed");
    }
    if (pcm_paths == nullptr || zero_cross_in_samples == nullptr ||
        zero_cross_out_samples == nullptr || gap_after_samples == nullptr) {
        return MakeJString(env, "Snapshot slice arrays must not be null");
    }

    const jsize count = env->GetArrayLength(pcm_paths);
    if (env->GetArrayLength(zero_cross_in_samples) != count ||
        env->GetArrayLength(zero_cross_out_samples) != count ||
        env->GetArrayLength(gap_after_samples) != count) {
        return MakeJString(env, "Snapshot slice array lengths must match");
    }

    jlong* ins = (count > 0) ? env->GetLongArrayElements(zero_cross_in_samples, nullptr) : nullptr;
    jlong* outs = (count > 0) ? env->GetLongArrayElements(zero_cross_out_samples, nullptr) : nullptr;
    jint* gaps = (count > 0) ? env->GetIntArrayElements(gap_after_samples, nullptr) : nullptr;

    std::vector<SliceDescriptor> descriptors;
    descriptors.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto* path_jstr = static_cast<jstring>(env->GetObjectArrayElement(pcm_paths, i));
        SliceDescriptor desc;
        desc.pcm_file_path = JStringToStdString(env, path_jstr);
        if (path_jstr != nullptr) {
            env->DeleteLocalRef(path_jstr);
        }
        desc.zero_cross_in_sample = static_cast<int64_t>(ins[i]);
        desc.zero_cross_out_sample = static_cast<int64_t>(outs[i]);
        desc.gap_after_samples = static_cast<int32_t>(gaps[i]);
        descriptors.push_back(std::move(desc));
    }

    if (ins != nullptr) {
        env->ReleaseLongArrayElements(zero_cross_in_samples, ins, JNI_ABORT);
    }
    if (outs != nullptr) {
        env->ReleaseLongArrayElements(zero_cross_out_samples, outs, JNI_ABORT);
    }
    if (gaps != nullptr) {
        env->ReleaseIntArrayElements(gap_after_samples, gaps, JNI_ABORT);
    }

    std::string error;
    if (!player->UpdateEdlFromDescriptors(descriptors, static_cast<float>(global_gap_scale), &error)) {
        return MakeJString(env, error.empty() ? "EdlPlayer::UpdateEdlFromDescriptors failed" : error);
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_voicestudio_edl_EdlController_nativeSeekToSample(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jlong timeline_sample) {
    auto* player = FromHandle(handle);
    if (player != nullptr) {
        player->SeekToSample(static_cast<int64_t>(timeline_sample));
    }
}

JNIEXPORT jlong JNICALL
Java_com_voicestudio_edl_EdlController_nativeGetCurrentSample(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return 0;
    }
    return static_cast<jlong>(player->GetCurrentSample());
}

JNIEXPORT jint JNICALL
Java_com_voicestudio_edl_EdlController_nativeGetXRunCount(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return 0;
    }
    return static_cast<jint>(player->GetXRunCount());
}

JNIEXPORT jboolean JNICALL
Java_com_voicestudio_edl_EdlController_nativeIsPlaying(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle) {
    auto* player = FromHandle(handle);
    if (player == nullptr) {
        return JNI_FALSE;
    }
    return player->IsPlaying() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
