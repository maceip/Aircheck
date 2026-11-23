#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace voicestudio::engine {

struct EngineConfig {
    std::string base_gguf_path;
    std::string lora_gguf_path;
    std::string vocoder_tflite_path;
    std::string room_ir_pcm_path;
    std::string room_tone_pcm_path;
    std::string llama_library_path;  // Optional explicit path to libllama.so / libllama.dylib
    float lora_scale = 1.0f;
    int32_t n_threads = 4;
    int32_t n_ctx = 4096;
    int32_t max_audio_tokens = 2048;
    int64_t base_seed = 1001;
    float default_temperature = 0.7f;
    float top_p = 0.9f;
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

using VocoderDecodeCallback = std::function<EngineStatus(
    const std::vector<int32_t>& discrete_tokens,
    std::vector<float>& out_pcm_44k)>;

/**
 * Validates that a file exists and begins with a valid GGUF magic header ('GGUF', v2/v3).
 */
EngineStatus ValidateGgufFile(const std::string& path);

/**
 * Validates that a file exists and carries the LiteRT / FlatBuffers 'TFL3' identifier.
 */
EngineStatus ValidateTfliteFile(const std::string& path);

class VoiceEngine {
public:
    VoiceEngine();
    ~VoiceEngine();

    VoiceEngine(const VoiceEngine&) = delete;
    VoiceEngine& operator=(const VoiceEngine&) = delete;

    EngineStatus Init(const EngineConfig& config);

    /**
     * Atomically hot-swaps the active Rank-32 GGUF LoRA adapter via llama_adapter_lora_init(),
     * llama_set_adapter_lora(), and llama_adapter_lora_free() without leaking native handles.
     */
    EngineStatus HotSwapLora(const std::string& new_lora_gguf_path);

    /**
     * Runs Stage A Dual-AR discrete codebook rollout via llama.cpp (+ active LoRA).
     * If a real vocoder callback is attached, also decodes to 44.1 kHz Float32 PCM and
     * runs Stage C NEON DSP mastering. Never fabricates fake speech PCM.
     */
    EngineStatus SynthesizeCandidates(
        const std::string& normalized_text,
        const std::string& prefix_pcm_path,
        int32_t num_candidates,
        std::vector<SynthesizedCandidate>& out_candidates);

    /**
     * Registers an optional native/JNI LiteRT DAC vocoder decode callback.
     */
    void SetVocoderCallback(VocoderDecodeCallback callback);

    /**
     * Invokes madvise(..., MADV_DONTNEED) over mapped GGUF base & LoRA weight pages.
     * Returns the total byte count advised for eviction.
     */
    size_t EvictWeightPages();

    bool IsInitialized() const;
    uint64_t LoraSwapCount() const;
    size_t LastEvictedBytes() const;
    bool LastEvictionSucceeded() const;
    const std::string& ActiveLoraPath() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    mutable std::mutex mutex_;
    EngineConfig config_;
    std::atomic<bool> initialized_{false};
    std::atomic<uint64_t> lora_swap_count_{0};
    std::atomic<size_t> last_evicted_bytes_{0};
    std::atomic<bool> last_eviction_succeeded_{false};
    VocoderDecodeCallback vocoder_callback_;
};

}  // namespace voicestudio::engine
