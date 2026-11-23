#include "voice_engine.h"

#include <fstream>

namespace voicestudio::engine {

namespace {

bool FileReadable(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    std::ifstream in(path, std::ios::binary);
    return in.good();
}

}  // namespace

VoiceEngine::VoiceEngine() = default;
VoiceEngine::~VoiceEngine() = default;

EngineStatus VoiceEngine::Init(const EngineConfig& config) {
    if (!FileReadable(config.base_gguf_path)) {
        return {false, "Missing or unreadable base GGUF model: " + config.base_gguf_path};
    }
    if (!FileReadable(config.vocoder_tflite_path)) {
        return {false, "Missing or unreadable LiteRT DAC vocoder: " + config.vocoder_tflite_path};
    }
    if (!config.lora_gguf_path.empty() && !FileReadable(config.lora_gguf_path)) {
        return {false, "Missing or unreadable LoRA GGUF adapter: " + config.lora_gguf_path};
    }
    if (!config.room_ir_pcm_path.empty() && !FileReadable(config.room_ir_pcm_path)) {
        return {false, "Missing or unreadable room IR PCM: " + config.room_ir_pcm_path};
    }
    config_ = config;
    initialized_ = false;
    return {false, "Wave-1 build scaffold: llama.cpp GGUF + LiteRT DAC backend pending Milestone 1 implementation"};
}

EngineStatus VoiceEngine::HotSwapLora(const std::string& new_lora_gguf_path) {
    if (!initialized_) {
        return {false, "VoiceEngine is not initialized"};
    }
    if (!FileReadable(new_lora_gguf_path)) {
        return {false, "Missing or unreadable LoRA GGUF adapter: " + new_lora_gguf_path};
    }
    return {false, "Wave-1 build scaffold: llama_adapter_lora_init pending Milestone 1 implementation"};
}

EngineStatus VoiceEngine::SynthesizeCandidates(
    const std::string& normalized_text,
    const std::string& prefix_pcm_path,
    int32_t num_candidates,
    std::vector<SynthesizedCandidate>& out_candidates) {
    (void)normalized_text;
    (void)prefix_pcm_path;
    (void)num_candidates;
    out_candidates.clear();
    if (!initialized_) {
        return {false, "VoiceEngine is not initialized"};
    }
    return {false, "Wave-1 build scaffold: Stage A synthesis pending Milestone 1 implementation"};
}

void VoiceEngine::EvictWeightPages() {
    // Milestone 1 binds madvise(MADV_DONTNEED) to mmap'd GGUF model pages.
}

bool VoiceEngine::IsInitialized() const {
    return initialized_;
}

}  // namespace voicestudio::engine
