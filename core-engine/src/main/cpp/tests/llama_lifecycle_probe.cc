// CTest-only shared library verifying real dlopen/dlsym binding, exact atomic
// llama_adapter_lora_init / llama_set_adapter_lora / llama_adapter_lora_free lifecycle,
// and zero native heap leaks across 10+ LoRA hot-swap cycles.
// NEVER built for Android or bundled in the APK.

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr int32_t kProbeVocabSize = 64;
constexpr int32_t kProbeEosToken = 2;
constexpr size_t kAdapterAllocBytes = 65536;  // 64 KiB heap block per live LoRA adapter

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

ProbeStats g_stats{};

struct alignas(16) ProbeModelParams {
    uint8_t bytes[512];
};

struct alignas(16) ProbeContextParams {
    uint8_t bytes[512];
};

struct llama_vocab {
    int32_t n_tokens = kProbeVocabSize;
    int32_t eos = kProbeEosToken;
};

struct llama_model {
    std::string path;
    llama_vocab vocab;
};

struct llama_adapter_lora {
    std::string path;
    void* heap_block = nullptr;
    size_t heap_bytes = 0;
};

struct llama_context {
    llama_model* model = nullptr;
    llama_adapter_lora* active_adapter = nullptr;
    float active_scale = 0.0f;
    int32_t step_counter = 0;
    std::vector<float> logits;
};

struct llama_batch {
    int32_t n_tokens;
    int32_t* token;
    float* embd;
    int32_t* pos;
    int32_t* n_seq_id;
    int32_t** seq_id;
    int8_t* logits;
};

}  // namespace

extern "C" {

void voicestudio_probe_reset() {
    g_stats = ProbeStats{};
}

void voicestudio_probe_get_stats(ProbeStats* out) {
    if (out != nullptr) {
        *out = g_stats;
    }
}

void llama_backend_init() {
    ++g_stats.backend_init_count;
}

void llama_backend_free() {
    ++g_stats.backend_free_count;
}

ProbeModelParams llama_model_default_params() {
    return ProbeModelParams{};
}

ProbeContextParams llama_context_default_params() {
    return ProbeContextParams{};
}

llama_model* llama_model_load_from_file(const char* path_model, ProbeModelParams /*params*/) {
    if (path_model == nullptr || std::strlen(path_model) == 0) {
        return nullptr;
    }
    auto* m = new llama_model();
    m->path = path_model;
    ++g_stats.live_models;
    return m;
}

void llama_model_free(llama_model* model) {
    if (model == nullptr) {
        return;
    }
    --g_stats.live_models;
    delete model;
}

llama_context* llama_init_from_model(llama_model* model, ProbeContextParams /*params*/) {
    if (model == nullptr) {
        return nullptr;
    }
    auto* ctx = new llama_context();
    ctx->model = model;
    ctx->logits.assign(kProbeVocabSize, -5.0f);
    ++g_stats.live_contexts;
    return ctx;
}

void llama_free(llama_context* ctx) {
    if (ctx == nullptr) {
        return;
    }
    --g_stats.live_contexts;
    delete ctx;
}

llama_adapter_lora* llama_adapter_lora_init(llama_model* model, const char* path_lora) {
    if (model == nullptr || path_lora == nullptr || std::strlen(path_lora) == 0) {
        return nullptr;
    }
    if (std::strstr(path_lora, "fail_init") != nullptr) {
        return nullptr;
    }
    auto* adapter = new llama_adapter_lora();
    adapter->path = path_lora;
    adapter->heap_block = std::calloc(1, kAdapterAllocBytes);
    adapter->heap_bytes = kAdapterAllocBytes;
    ++g_stats.adapter_init_count;
    ++g_stats.live_adapters;
    g_stats.live_adapter_bytes += static_cast<int64_t>(adapter->heap_bytes);
    return adapter;
}

int32_t llama_set_adapter_lora(llama_context* ctx, llama_adapter_lora* adapter, float scale) {
    if (ctx == nullptr || adapter == nullptr) {
        return -1;
    }
    if (adapter->path.find("fail_set") != std::string::npos) {
        return -2;
    }
    ctx->active_adapter = adapter;
    ctx->active_scale = scale;
    ++g_stats.adapter_set_count;
    return 0;
}

int32_t llama_rm_adapter_lora(llama_context* ctx, llama_adapter_lora* adapter) {
    if (ctx == nullptr) {
        return -1;
    }
    if (ctx->active_adapter == adapter) {
        ctx->active_adapter = nullptr;
        ctx->active_scale = 0.0f;
    }
    ++g_stats.adapter_rm_count;
    return 0;
}

void llama_clear_adapter_lora(llama_context* ctx) {
    if (ctx != nullptr) {
        ctx->active_adapter = nullptr;
        ctx->active_scale = 0.0f;
    }
}

void llama_adapter_lora_free(llama_adapter_lora* adapter) {
    if (adapter == nullptr) {
        return;
    }
    if (adapter->heap_block != nullptr) {
        std::free(adapter->heap_block);
        g_stats.live_adapter_bytes -= static_cast<int64_t>(adapter->heap_bytes);
        adapter->heap_block = nullptr;
    }
    ++g_stats.adapter_free_count;
    --g_stats.live_adapters;
    delete adapter;
}

const llama_vocab* llama_model_get_vocab(const llama_model* model) {
    return (model != nullptr) ? &model->vocab : nullptr;
}

int32_t llama_vocab_n_tokens(const llama_vocab* vocab) {
    return (vocab != nullptr) ? vocab->n_tokens : 0;
}

int32_t llama_vocab_eos(const llama_vocab* vocab) {
    return (vocab != nullptr) ? vocab->eos : -1;
}

int32_t llama_tokenize(
    const llama_vocab* vocab,
    const char* text,
    int32_t text_len,
    int32_t* tokens,
    int32_t n_tokens_max,
    bool /*add_special*/,
    bool /*parse_special*/) {
    if (vocab == nullptr || text == nullptr || text_len <= 0 || tokens == nullptr || n_tokens_max <= 0) {
        return -1;
    }
    const int32_t count = (text_len < n_tokens_max) ? text_len : n_tokens_max;
    const int32_t out_len = (count > 8) ? 8 : count;
    for (int32_t i = 0; i < out_len; ++i) {
        tokens[i] = 4 + (static_cast<unsigned char>(text[i]) % 32);
    }
    return out_len;
}

llama_batch llama_batch_init(int32_t n_tokens, int32_t /*embd*/, int32_t n_seq_max) {
    llama_batch b{};
    const int32_t cap = (n_tokens > 0) ? n_tokens : 1;
    const int32_t seq_cap = (n_seq_max > 0) ? n_seq_max : 1;
    b.n_tokens = 0;
    b.token = new int32_t[static_cast<size_t>(cap)]{};
    b.pos = new int32_t[static_cast<size_t>(cap)]{};
    b.n_seq_id = new int32_t[static_cast<size_t>(cap)]{};
    b.seq_id = new int32_t*[static_cast<size_t>(cap)]{};
    auto* seq_pool = new int32_t[static_cast<size_t>(cap) * static_cast<size_t>(seq_cap)]{};
    for (int32_t i = 0; i < cap; ++i) {
        b.seq_id[i] = seq_pool + static_cast<size_t>(i) * static_cast<size_t>(seq_cap);
    }
    b.logits = new int8_t[static_cast<size_t>(cap)]{};
    ++g_stats.batch_init_count;
    return b;
}

void llama_batch_free(llama_batch batch) {
    if (batch.seq_id != nullptr) {
        delete[] batch.seq_id[0];
        delete[] batch.seq_id;
    }
    delete[] batch.token;
    delete[] batch.pos;
    delete[] batch.n_seq_id;
    delete[] batch.logits;
    ++g_stats.batch_free_count;
}

int32_t llama_decode(llama_context* ctx, llama_batch batch) {
    if (ctx == nullptr || batch.n_tokens <= 0) {
        return -1;
    }
    ++g_stats.decode_call_count;
    ++ctx->step_counter;
    std::fill(ctx->logits.begin(), ctx->logits.end(), -4.0f);
    if (ctx->step_counter >= 9) {
        ctx->logits[kProbeEosToken] = 12.0f;
    } else {
        for (int32_t k = 10; k < 18; ++k) {
            ctx->logits[static_cast<size_t>(k)] = 3.0f + 0.1f * static_cast<float>((k + ctx->step_counter) % 5);
        }
    }
    return 0;
}

float* llama_get_logits_ith(llama_context* ctx, int32_t /*i*/) {
    return (ctx != nullptr) ? ctx->logits.data() : nullptr;
}

void llama_kv_cache_clear(llama_context* ctx) {
    if (ctx != nullptr) {
        ctx->step_counter = 0;
    }
}

}  // extern "C"
