#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace voicestudio::engine {

struct EngineConfig {
    std::string base_gguf_path;
    std::string lora_gguf_path;
    std::string vocoder_tflite_path;
    std::string room_ir_pcm_path;
};

struct SynthesizedCandidate {
    std::vector<int32_t> discrete_audio_tokens;
    std::vector<float> pcm_44k;
    int64_t seed = 0;
    float temperature = 0.7f;
};

struct EngineStatus {
    bool ok = false;
    std::string error_message;
};

class VoiceEngine {
public:
    VoiceEngine();
    ~VoiceEngine();

    VoiceEngine(const VoiceEngine&) = delete;
    VoiceEngine& operator=(const VoiceEngine&) = delete;

    EngineStatus Init(const EngineConfig& config);
    EngineStatus HotSwapLora(const std::string& new_lora_gguf_path);
    EngineStatus SynthesizeCandidates(
        const std::string& normalized_text,
        const std::string& prefix_pcm_path,
        int32_t num_candidates,
        std::vector<SynthesizedCandidate>& out_candidates);
    void EvictWeightPages();
    bool IsInitialized() const;

private:
    bool initialized_ = false;
    EngineConfig config_;
};

}  // namespace voicestudio::engine
