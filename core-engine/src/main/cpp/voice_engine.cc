#include "voice_engine.h"

#include "dsp_mastering.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <fstream>
#include <numeric>
#include <random>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#if defined(VOICESTUDIO_HAS_LLAMA_CPP)
#include <llama.h>
#endif

namespace voicestudio::engine {

namespace {

constexpr size_t kMinGgufHeaderBytes = 24;
constexpr size_t kMinTfliteHeaderBytes = 16;

uint32_t ReadLeU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8U) |
           (static_cast<uint32_t>(p[2]) << 16U) |
           (static_cast<uint32_t>(p[3]) << 24U);
}

size_t SystemPageSize() {
    const long sz = sysconf(_SC_PAGESIZE);
    return (sz > 0) ? static_cast<size_t>(sz) : 4096U;
}

size_t AlignUp(size_t value, size_t alignment) {
    if (alignment == 0) {
        return value;
    }
    const size_t rem = value % alignment;
    return (rem == 0) ? value : (value + (alignment - rem));
}

struct MappedWeightFile {
    void* addr = nullptr;
    size_t file_size = 0;
    size_t mapped_size = 0;
    std::string path;

    bool Map(const std::string& file_path, std::string& out_error) {
        Unmap();
        if (file_path.empty()) {
            return true;
        }
        const int fd = ::open(file_path.c_str(), O_RDONLY);
        if (fd < 0) {
            out_error = "Failed to open weight file for mmap: " + file_path;
            return false;
        }
        struct stat st {};
        if (::fstat(fd, &st) != 0 || st.st_size <= 0) {
            ::close(fd);
            out_error = "Weight file is empty or cannot be stat'd: " + file_path;
            return false;
        }
        const size_t sz = static_cast<size_t>(st.st_size);
        void* ptr = ::mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (ptr == MAP_FAILED) {
            out_error = "mmap failed for weight file: " + file_path;
            return false;
        }
        addr = ptr;
        file_size = sz;
        mapped_size = AlignUp(sz, SystemPageSize());
        path = file_path;
        return true;
    }

    void Unmap() {
        if (addr != nullptr && file_size > 0) {
            ::munmap(addr, file_size);
        }
        addr = nullptr;
        file_size = 0;
        mapped_size = 0;
        path.clear();
    }

    ~MappedWeightFile() {
        Unmap();
    }
};

#if !defined(VOICESTUDIO_HAS_LLAMA_CPP)
// Opaque C ABI declarations matching llama.cpp (2024-2026) for runtime dlopen/dlsym binding.
struct llama_model;
struct llama_context;
struct llama_adapter_lora;
struct llama_vocab;
using llama_token = int32_t;
using llama_pos = int32_t;
using llama_seq_id = int32_t;

// Over-allocated ABI-safe parameter storage buffers prevent stack overflow across llama.cpp
// minor revisions when calling by-value default param factories over dlopen/dlsym.
struct alignas(16) LlamaModelParamsStorage {
    uint8_t bytes[512] = {};
};

struct alignas(16) LlamaContextParamsStorage {
    uint8_t bytes[512] = {};
};

struct llama_batch {
    int32_t n_tokens = 0;
    llama_token* token = nullptr;
    float* embd = nullptr;
    llama_pos* pos = nullptr;
    int32_t* n_seq_id = nullptr;
    llama_seq_id** seq_id = nullptr;
    int8_t* logits = nullptr;
};
#endif

struct LlamaApiTable {
    void* dl_handle = nullptr;

    void (*backend_init)() = nullptr;
    void (*backend_free)() = nullptr;

#if defined(VOICESTUDIO_HAS_LLAMA_CPP)
    llama_model_params (*model_default_params)() = nullptr;
    llama_context_params (*context_default_params)() = nullptr;
    llama_model* (*model_load_from_file)(const char*, llama_model_params) = nullptr;
    llama_context* (*init_from_model)(llama_model*, llama_context_params) = nullptr;
#else
    LlamaModelParamsStorage (*model_default_params)() = nullptr;
    LlamaContextParamsStorage (*context_default_params)() = nullptr;
    llama_model* (*model_load_from_file)(const char*, LlamaModelParamsStorage) = nullptr;
    llama_context* (*init_from_model)(llama_model*, LlamaContextParamsStorage) = nullptr;
#endif
    void (*model_free)(llama_model*) = nullptr;
    void (*free_ctx)(llama_context*) = nullptr;

    llama_adapter_lora* (*adapter_lora_init)(llama_model*, const char*) = nullptr;
    int32_t (*set_adapter_lora)(llama_context*, llama_adapter_lora*, float) = nullptr;
    int32_t (*rm_adapter_lora)(llama_context*, llama_adapter_lora*) = nullptr;
    void (*clear_adapter_lora)(llama_context*) = nullptr;
    void (*adapter_lora_free)(llama_adapter_lora*) = nullptr;

    const llama_vocab* (*model_get_vocab)(const llama_model*) = nullptr;
    int32_t (*vocab_n_tokens)(const llama_vocab*) = nullptr;
    llama_token (*vocab_eos)(const llama_vocab*) = nullptr;
    int32_t (*tokenize_vocab)(
        const llama_vocab*, const char*, int32_t, llama_token*, int32_t, bool, bool) = nullptr;
    int32_t (*tokenize_model)(
        const llama_model*, const char*, int32_t, llama_token*, int32_t, bool, bool) = nullptr;

    llama_batch (*batch_init)(int32_t, int32_t, int32_t) = nullptr;
    void (*batch_free)(llama_batch) = nullptr;
    int32_t (*decode)(llama_context*, llama_batch) = nullptr;
    float* (*get_logits_ith)(llama_context*, int32_t) = nullptr;
    void (*kv_cache_clear)(llama_context*) = nullptr;

    bool IsLoaded() const {
        return model_load_from_file != nullptr &&
               model_free != nullptr &&
               init_from_model != nullptr &&
               free_ctx != nullptr &&
               adapter_lora_init != nullptr &&
               set_adapter_lora != nullptr &&
               adapter_lora_free != nullptr;
    }

    void Unload() {
        if (dl_handle != nullptr) {
            ::dlclose(dl_handle);
            dl_handle = nullptr;
        }
        *this = LlamaApiTable{};
    }
};

template <typename Fn>
Fn ResolveAnySymbol(void* handle, std::initializer_list<const char*> names) {
    for (const char* name : names) {
        if (void* sym = ::dlsym(handle, name)) {
            return reinterpret_cast<Fn>(sym);
        }
    }
    return nullptr;
}

EngineStatus LoadLlamaRuntime(const std::string& explicit_lib_path, LlamaApiTable& api) {
    api.Unload();

#if defined(VOICESTUDIO_HAS_LLAMA_CPP)
    if (explicit_lib_path.empty()) {
        api.backend_init = &llama_backend_init;
        api.backend_free = &llama_backend_free;
        api.model_default_params = &llama_model_default_params;
        api.context_default_params = &llama_context_default_params;
        api.model_load_from_file = &llama_model_load_from_file;
        api.model_free = &llama_model_free;
        api.init_from_model = &llama_init_from_model;
        api.free_ctx = &llama_free;
        api.adapter_lora_init = &llama_adapter_lora_init;
        api.set_adapter_lora = &llama_set_adapter_lora;
        api.rm_adapter_lora = &llama_rm_adapter_lora;
        api.clear_adapter_lora = &llama_clear_adapter_lora;
        api.adapter_lora_free = &llama_adapter_lora_free;
        api.model_get_vocab = &llama_model_get_vocab;
        api.vocab_n_tokens = &llama_vocab_n_tokens;
        api.vocab_eos = &llama_vocab_eos;
        api.tokenize_vocab = &llama_tokenize;
        api.batch_init = &llama_batch_init;
        api.batch_free = &llama_batch_free;
        api.decode = &llama_decode;
        api.get_logits_ith = &llama_get_logits_ith;
        return {true, ""};
    }
#endif

    std::vector<std::string> candidates;
    if (!explicit_lib_path.empty()) {
        candidates.push_back(explicit_lib_path);
    } else {
#if defined(__APPLE__)
        candidates.emplace_back("libllama.dylib");
        candidates.emplace_back("@rpath/libllama.dylib");
#else
        candidates.emplace_back("libllama.so");
        candidates.emplace_back("libllama_jni.so");
#endif
    }

    std::string last_dl_error;
    for (const auto& lib_name : candidates) {
        ::dlerror();
        void* handle = ::dlopen(lib_name.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle != nullptr) {
            api.dl_handle = handle;
            break;
        }
        const char* err = ::dlerror();
        if (err != nullptr) {
            last_dl_error = err;
        }
    }

    if (api.dl_handle == nullptr) {
        return {
            false,
            "llama.cpp runtime library (libllama) not available: " +
                (last_dl_error.empty() ? "dlopen returned null" : last_dl_error)};
    }

    api.backend_init = ResolveAnySymbol<decltype(api.backend_init)>(
        api.dl_handle, {"llama_backend_init"});
    api.backend_free = ResolveAnySymbol<decltype(api.backend_free)>(
        api.dl_handle, {"llama_backend_free"});
    api.model_default_params = ResolveAnySymbol<decltype(api.model_default_params)>(
        api.dl_handle, {"llama_model_default_params"});
    api.context_default_params = ResolveAnySymbol<decltype(api.context_default_params)>(
        api.dl_handle, {"llama_context_default_params"});
    api.model_load_from_file = ResolveAnySymbol<decltype(api.model_load_from_file)>(
        api.dl_handle, {"llama_model_load_from_file", "llama_load_model_from_file"});
    api.model_free = ResolveAnySymbol<decltype(api.model_free)>(
        api.dl_handle, {"llama_model_free", "llama_free_model"});
    api.init_from_model = ResolveAnySymbol<decltype(api.init_from_model)>(
        api.dl_handle, {"llama_init_from_model", "llama_new_context_with_model"});
    api.free_ctx = ResolveAnySymbol<decltype(api.free_ctx)>(
        api.dl_handle, {"llama_free"});

    api.adapter_lora_init = ResolveAnySymbol<decltype(api.adapter_lora_init)>(
        api.dl_handle, {"llama_adapter_lora_init", "llama_lora_adapter_init"});
    api.set_adapter_lora = ResolveAnySymbol<decltype(api.set_adapter_lora)>(
        api.dl_handle, {"llama_set_adapter_lora", "llama_lora_adapter_set"});
    api.rm_adapter_lora = ResolveAnySymbol<decltype(api.rm_adapter_lora)>(
        api.dl_handle, {"llama_rm_adapter_lora", "llama_lora_adapter_remove"});
    api.clear_adapter_lora = ResolveAnySymbol<decltype(api.clear_adapter_lora)>(
        api.dl_handle, {"llama_clear_adapter_lora", "llama_lora_adapter_clear"});
    api.adapter_lora_free = ResolveAnySymbol<decltype(api.adapter_lora_free)>(
        api.dl_handle, {"llama_adapter_lora_free", "llama_lora_adapter_free"});

    api.model_get_vocab = ResolveAnySymbol<decltype(api.model_get_vocab)>(
        api.dl_handle, {"llama_model_get_vocab"});
    api.vocab_n_tokens = ResolveAnySymbol<decltype(api.vocab_n_tokens)>(
        api.dl_handle, {"llama_vocab_n_tokens", "llama_n_vocab"});
    api.vocab_eos = ResolveAnySymbol<decltype(api.vocab_eos)>(
        api.dl_handle, {"llama_vocab_eos", "llama_token_eos"});
    api.tokenize_vocab = ResolveAnySymbol<decltype(api.tokenize_vocab)>(
        api.dl_handle, {"llama_tokenize"});
    api.tokenize_model = ResolveAnySymbol<decltype(api.tokenize_model)>(
        api.dl_handle, {"llama_tokenize"});
    api.batch_init = ResolveAnySymbol<decltype(api.batch_init)>(
        api.dl_handle, {"llama_batch_init"});
    api.batch_free = ResolveAnySymbol<decltype(api.batch_free)>(
        api.dl_handle, {"llama_batch_free"});
    api.decode = ResolveAnySymbol<decltype(api.decode)>(
        api.dl_handle, {"llama_decode"});
    api.get_logits_ith = ResolveAnySymbol<decltype(api.get_logits_ith)>(
        api.dl_handle, {"llama_get_logits_ith", "llama_get_logits"});
    api.kv_cache_clear = ResolveAnySymbol<decltype(api.kv_cache_clear)>(
        api.dl_handle, {"llama_kv_cache_clear"});

    if (!api.IsLoaded() || api.model_default_params == nullptr || api.context_default_params == nullptr) {
        api.Unload();
        return {false, "Loaded libllama is missing required GGUF/LoRA C API symbols"};
    }
    return {true, ""};
}

int32_t SampleTokenTopP(
    const float* logits,
    int32_t vocab_size,
    float temperature,
    float top_p,
    std::mt19937_64& rng) {
    if (logits == nullptr || vocab_size <= 0) {
        return 0;
    }
    if (temperature <= 1e-4f) {
        return static_cast<int32_t>(
            std::max_element(logits, logits + vocab_size) - logits);
    }

    // Track top-K (up to 64) logits for fast deterministic nucleus (top-p) sampling.
    const size_t k_limit = static_cast<size_t>(std::min<int32_t>(vocab_size, 64));
    std::vector<std::pair<float, int32_t>> top;
    top.reserve(static_cast<size_t>(vocab_size));
    for (int32_t i = 0; i < vocab_size; ++i) {
        const float v = std::isfinite(logits[i]) ? logits[i] : -1e30f;
        top.emplace_back(v, i);
    }
    std::partial_sort(
        top.begin(),
        top.begin() + static_cast<std::ptrdiff_t>(k_limit),
        top.end(),
        [](const auto& a, const auto& b) {
            if (a.first != b.first) return a.first > b.first;
            return a.second < b.second;
        });
    top.resize(k_limit);

    const float max_logit = top[0].first;
    const float inv_temp = 1.0f / std::max(temperature, 1e-4f);
    std::vector<double> probs(k_limit, 0.0);
    double sum = 0.0;
    for (size_t i = 0; i < k_limit; ++i) {
        const double p = std::exp(static_cast<double>((top[i].first - max_logit) * inv_temp));
        probs[i] = p;
        sum += p;
    }
    if (sum <= 0.0 || !std::isfinite(sum)) {
        return top[0].second;
    }

    const double clamped_top_p = std::clamp<double>(top_p, 0.05, 1.0);
    double cum = 0.0;
    size_t keep = 1;
    for (size_t i = 0; i < k_limit; ++i) {
        probs[i] /= sum;
        cum += probs[i];
        keep = i + 1;
        if (cum >= clamped_top_p) {
            break;
        }
    }

    std::uniform_real_distribution<double> dist(0.0, cum);
    const double r = dist(rng);
    double acc = 0.0;
    for (size_t i = 0; i < keep; ++i) {
        acc += probs[i];
        if (r <= acc) {
            return top[i].second;
        }
    }
    return top[keep - 1].second;
}

#if defined(__linux__)
size_t EvictProcMapsMatchingPath(
    const std::string& target_path,
    void* skip_addr,
    bool& any_failed) {
    if (target_path.empty()) {
        return 0;
    }
    std::ifstream maps("/proc/self/maps");
    if (!maps.good()) {
        return 0;
    }
    size_t evicted = 0;
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find(target_path) == std::string::npos) {
            continue;
        }
        std::istringstream iss(line);
        std::string addr_range;
        std::string perms;
        if (!(iss >> addr_range >> perms)) {
            continue;
        }
        // Only advise private readable mappings; never touch shared writable or executable code.
        if (perms.size() < 4 || perms[0] != 'r' || perms[2] == 'x' || perms[3] != 'p') {
            continue;
        }
        const size_t dash = addr_range.find('-');
        if (dash == std::string::npos) {
            continue;
        }
        const uintptr_t start = std::stoull(addr_range.substr(0, dash), nullptr, 16);
        const uintptr_t end = std::stoull(addr_range.substr(dash + 1), nullptr, 16);
        if (end <= start) {
            continue;
        }
        void* ptr = reinterpret_cast<void*>(start);
        if (ptr == skip_addr) {
            continue;
        }
        const size_t len = static_cast<size_t>(end - start);
        if (::madvise(ptr, len, MADV_DONTNEED) == 0) {
            evicted += len;
        } else {
            any_failed = true;
        }
    }
    return evicted;
}
#endif

}  // namespace

EngineStatus ValidateGgufFile(const std::string& path) {
    if (path.empty()) {
        return {false, "GGUF file path is empty"};
    }
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in.good()) {
        return {false, "Missing or unreadable GGUF file: " + path};
    }
    const std::streamoff size = in.tellg();
    if (size < static_cast<std::streamoff>(kMinGgufHeaderBytes)) {
        return {false, "GGUF file is truncated (< 24 bytes): " + path};
    }
    in.seekg(0, std::ios::beg);
    std::array<uint8_t, 8> hdr{};
    in.read(reinterpret_cast<char*>(hdr.data()), static_cast<std::streamsize>(hdr.size()));
    if (!in.good()) {
        return {false, "Failed reading GGUF header: " + path};
    }
    if (hdr[0] != 'G' || hdr[1] != 'G' || hdr[2] != 'U' || hdr[3] != 'F') {
        return {false, "Invalid GGUF magic header (expected 'GGUF'): " + path};
    }
    const uint32_t version = ReadLeU32(hdr.data() + 4);
    if (version < 2U || version > 3U) {
        return {
            false,
            "Unsupported GGUF format version " + std::to_string(version) + " in: " + path};
    }
    return {true, ""};
}

EngineStatus ValidateTfliteFile(const std::string& path) {
    if (path.empty()) {
        return {false, "LiteRT DAC .tflite path is empty"};
    }
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in.good()) {
        return {false, "Missing or unreadable LiteRT DAC vocoder: " + path};
    }
    const std::streamoff size = in.tellg();
    if (size < static_cast<std::streamoff>(kMinTfliteHeaderBytes)) {
        return {false, "LiteRT DAC .tflite file is truncated (< 16 bytes): " + path};
    }
    in.seekg(0, std::ios::beg);
    std::array<uint8_t, 8> hdr{};
    in.read(reinterpret_cast<char*>(hdr.data()), static_cast<std::streamsize>(hdr.size()));
    if (!in.good()) {
        return {false, "Failed reading LiteRT DAC .tflite header: " + path};
    }
    if (hdr[4] != 'T' || hdr[5] != 'F' || hdr[6] != 'L' || hdr[7] != '3') {
        return {false, "Invalid LiteRT flatbuffer identifier (expected 'TFL3'): " + path};
    }
    return {true, ""};
}

struct VoiceEngine::Impl {
    LlamaApiTable api;
    bool backend_initialized = false;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_adapter_lora* active_lora = nullptr;
    MappedWeightFile base_gguf_map;
    MappedWeightFile lora_gguf_map;
    std::vector<float> room_ir;
    std::vector<float> room_tone;

    void ReleaseNativeHandles() {
        if (ctx != nullptr && active_lora != nullptr) {
            if (api.rm_adapter_lora != nullptr) {
                api.rm_adapter_lora(ctx, active_lora);
            } else if (api.clear_adapter_lora != nullptr) {
                api.clear_adapter_lora(ctx);
            }
        }
        if (active_lora != nullptr && api.adapter_lora_free != nullptr) {
            api.adapter_lora_free(active_lora);
            active_lora = nullptr;
        }
        if (ctx != nullptr && api.free_ctx != nullptr) {
            api.free_ctx(ctx);
            ctx = nullptr;
        }
        if (model != nullptr && api.model_free != nullptr) {
            api.model_free(model);
            model = nullptr;
        }
        if (backend_initialized && api.backend_free != nullptr) {
            api.backend_free();
            backend_initialized = false;
        }
        lora_gguf_map.Unmap();
        base_gguf_map.Unmap();
        room_ir.clear();
        room_tone.clear();
        api.Unload();
    }

    ~Impl() {
        ReleaseNativeHandles();
    }
};

VoiceEngine::VoiceEngine() : impl_(std::make_unique<Impl>()) {}

VoiceEngine::~VoiceEngine() {
    std::lock_guard<std::mutex> lock(mutex_);
    initialized_.store(false, std::memory_order_release);
    if (impl_) {
        impl_->ReleaseNativeHandles();
    }
}

EngineStatus VoiceEngine::Init(const EngineConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    initialized_.store(false, std::memory_order_release);
    impl_->ReleaseNativeHandles();

    // 1. Validate real model artifacts on disk (Constitutional Law 2: fail fast if missing/corrupt).
    if (const auto st = ValidateGgufFile(config.base_gguf_path); !st.ok) {
        return st;
    }
    if (const auto st = ValidateTfliteFile(config.vocoder_tflite_path); !st.ok) {
        return st;
    }
    if (!config.lora_gguf_path.empty()) {
        if (const auto st = ValidateGgufFile(config.lora_gguf_path); !st.ok) {
            return st;
        }
    }
    if (!config.room_ir_pcm_path.empty()) {
        if (!ReadPcmFile44k(config.room_ir_pcm_path, impl_->room_ir) || impl_->room_ir.empty()) {
            impl_->ReleaseNativeHandles();
            return {false, "Missing or unreadable room IR PCM: " + config.room_ir_pcm_path};
        }
    }
    if (!config.room_tone_pcm_path.empty()) {
        if (!ReadPcmFile44k(config.room_tone_pcm_path, impl_->room_tone) ||
            impl_->room_tone.empty()) {
            impl_->ReleaseNativeHandles();
            return {false, "Missing or unreadable room tone PCM: " + config.room_tone_pcm_path};
        }
    }

    // 2. Map base GGUF weights read-only for deterministic page-eviction tracking via madvise.
    std::string map_err;
    if (!impl_->base_gguf_map.Map(config.base_gguf_path, map_err)) {
        impl_->ReleaseNativeHandles();
        return {false, map_err};
    }

    // 3. Load real llama.cpp runtime (compile-time linked or dynamic dlopen).
    if (const auto st = LoadLlamaRuntime(config.llama_library_path, impl_->api); !st.ok) {
        impl_->ReleaseNativeHandles();
        return st;
    }

    if (impl_->api.backend_init != nullptr) {
        impl_->api.backend_init();
        impl_->backend_initialized = true;
    }

    auto model_params = impl_->api.model_default_params();
    impl_->model = impl_->api.model_load_from_file(config.base_gguf_path.c_str(), model_params);
    if (impl_->model == nullptr) {
        impl_->ReleaseNativeHandles();
        return {false, "llama_model_load_from_file failed for: " + config.base_gguf_path};
    }

    auto ctx_params = impl_->api.context_default_params();
    impl_->ctx = impl_->api.init_from_model(impl_->model, ctx_params);
    if (impl_->ctx == nullptr) {
        impl_->ReleaseNativeHandles();
        return {false, "llama_init_from_model failed for: " + config.base_gguf_path};
    }

    // 4. Attach initial Rank-32 GGUF LoRA adapter if specified.
    if (!config.lora_gguf_path.empty()) {
        if (!impl_->lora_gguf_map.Map(config.lora_gguf_path, map_err)) {
            impl_->ReleaseNativeHandles();
            return {false, map_err};
        }
        impl_->active_lora =
            impl_->api.adapter_lora_init(impl_->model, config.lora_gguf_path.c_str());
        if (impl_->active_lora == nullptr) {
            impl_->ReleaseNativeHandles();
            return {false, "llama_adapter_lora_init failed for: " + config.lora_gguf_path};
        }
        const int32_t rc =
            impl_->api.set_adapter_lora(impl_->ctx, impl_->active_lora, config.lora_scale);
        if (rc != 0) {
            impl_->ReleaseNativeHandles();
            return {
                false,
                "llama_set_adapter_lora failed with status " + std::to_string(rc) +
                    " for: " + config.lora_gguf_path};
        }
    }

    config_ = config;
    initialized_.store(true, std::memory_order_release);
    return {true, ""};
}

EngineStatus VoiceEngine::HotSwapLora(const std::string& new_lora_gguf_path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load(std::memory_order_acquire) ||
        impl_->model == nullptr ||
        impl_->ctx == nullptr) {
        return {false, "VoiceEngine is not initialized"};
    }
    if (const auto st = ValidateGgufFile(new_lora_gguf_path); !st.ok) {
        return st;
    }

    // Stage 1: Load new Rank-32 GGUF LoRA before disturbing the active adapter.
    llama_adapter_lora* next_lora =
        impl_->api.adapter_lora_init(impl_->model, new_lora_gguf_path.c_str());
    if (next_lora == nullptr) {
        return {false, "llama_adapter_lora_init failed for: " + new_lora_gguf_path};
    }

    MappedWeightFile next_map;
    std::string map_err;
    if (!next_map.Map(new_lora_gguf_path, map_err)) {
        impl_->api.adapter_lora_free(next_lora);
        return {false, map_err};
    }

    // Stage 2: Detach previous adapter (if any) and atomically bind the replacement adapter.
    llama_adapter_lora* prev_lora = impl_->active_lora;
    if (prev_lora != nullptr) {
        if (impl_->api.rm_adapter_lora != nullptr) {
            impl_->api.rm_adapter_lora(impl_->ctx, prev_lora);
        } else if (impl_->api.clear_adapter_lora != nullptr) {
            impl_->api.clear_adapter_lora(impl_->ctx);
        }
    }

    const int32_t rc = impl_->api.set_adapter_lora(impl_->ctx, next_lora, config_.lora_scale);
    if (rc != 0) {
        impl_->api.adapter_lora_free(next_lora);
        // Roll back to prior adapter so engine remains consistent.
        if (prev_lora != nullptr) {
            impl_->api.set_adapter_lora(impl_->ctx, prev_lora, config_.lora_scale);
        }
        return {
            false,
            "llama_set_adapter_lora failed with status " + std::to_string(rc) +
                " for: " + new_lora_gguf_path};
    }

    // Stage 3: Free previous adapter handle & unmap old file so native heap stays strictly flat.
    impl_->active_lora = next_lora;
    if (prev_lora != nullptr) {
        impl_->api.adapter_lora_free(prev_lora);
    }
    impl_->lora_gguf_map.Unmap();
    impl_->lora_gguf_map.addr = next_map.addr;
    impl_->lora_gguf_map.file_size = next_map.file_size;
    impl_->lora_gguf_map.mapped_size = next_map.mapped_size;
    impl_->lora_gguf_map.path = std::move(next_map.path);
    next_map.addr = nullptr;
    next_map.file_size = 0;
    next_map.mapped_size = 0;

    config_.lora_gguf_path = new_lora_gguf_path;
    lora_swap_count_.fetch_add(1, std::memory_order_acq_rel);
    return {true, ""};
}

EngineStatus VoiceEngine::SynthesizeCandidates(
    const std::string& normalized_text,
    const std::string& prefix_pcm_path,
    int32_t num_candidates,
    std::vector<SynthesizedCandidate>& out_candidates) {
    out_candidates.clear();

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load(std::memory_order_acquire) ||
        impl_->model == nullptr ||
        impl_->ctx == nullptr) {
        return {false, "VoiceEngine is not initialized"};
    }
    if (normalized_text.empty()) {
        return {false, "normalized_text must not be empty"};
    }
    if (num_candidates <= 0 || num_candidates > 16) {
        return {false, "num_candidates must be in range [1, 16]"};
    }

    if (impl_->api.batch_init == nullptr ||
        impl_->api.batch_free == nullptr ||
        impl_->api.decode == nullptr ||
        impl_->api.get_logits_ith == nullptr) {
        return {
            false,
            "Loaded llama.cpp runtime is missing token rollout symbols (batch/decode/logits)"};
    }

    // Validate optional acoustic prefix PCM (trailing 1.5s / 66,150 samples of prior clause).
    std::vector<float> prefix_tail;
    if (!prefix_pcm_path.empty()) {
        if (!ReadPcmFile44k(prefix_pcm_path, prefix_tail) || prefix_tail.empty()) {
            return {false, "Acoustic prefix PCM is missing or unreadable: " + prefix_pcm_path};
        }
        if (prefix_tail.size() > kPrefixTailSamples) {
            prefix_tail = ExtractAcousticPrefixTail(prefix_tail, kPrefixTailSamples);
        }
    }

    // Build Dual-AR prompt conditioning string.
    std::string prompt = "<|s2_pro_text|>" + normalized_text;
    if (!prefix_tail.empty()) {
        prompt += "<|acoustic_prefix_samples:" + std::to_string(prefix_tail.size()) + "|>";
    }
    prompt += "<|audio_start|>";

    const llama_vocab* vocab =
        (impl_->api.model_get_vocab != nullptr) ? impl_->api.model_get_vocab(impl_->model) : nullptr;
    const int32_t vocab_size =
        (vocab != nullptr && impl_->api.vocab_n_tokens != nullptr)
            ? impl_->api.vocab_n_tokens(vocab)
            : 0;
    if (vocab_size <= 0) {
        return {false, "Unable to query llama.cpp vocabulary size"};
    }
    const llama_token eos_token =
        (vocab != nullptr && impl_->api.vocab_eos != nullptr)
            ? impl_->api.vocab_eos(vocab)
            : -1;

    std::vector<llama_token> prompt_tokens(prompt.size() + 16);
    int32_t n_prompt = -1;
    if (vocab != nullptr && impl_->api.tokenize_vocab != nullptr) {
        n_prompt = impl_->api.tokenize_vocab(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            /*add_special=*/true,
            /*parse_special=*/true);
    } else if (impl_->api.tokenize_model != nullptr) {
        n_prompt = impl_->api.tokenize_model(
            impl_->model,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            /*add_special=*/true,
            /*parse_special=*/true);
    }
    if (n_prompt <= 0) {
        return {false, "llama_tokenize failed for clause text"};
    }
    prompt_tokens.resize(static_cast<size_t>(n_prompt));

    const int32_t max_tokens = std::clamp(config_.max_audio_tokens, 16, 4096);
    out_candidates.reserve(static_cast<size_t>(num_candidates));

    for (int32_t c = 0; c < num_candidates; ++c) {
        if (impl_->api.kv_cache_clear != nullptr) {
            impl_->api.kv_cache_clear(impl_->ctx);
        }

        const int64_t seed = config_.base_seed + static_cast<int64_t>(c);
        const float temperature = config_.default_temperature;
        std::mt19937_64 rng(static_cast<uint64_t>(seed));

        llama_batch batch =
            impl_->api.batch_init(std::max<int32_t>(n_prompt, 1), /*embd=*/0, /*n_seq_max=*/1);
        batch.n_tokens = n_prompt;
        for (int32_t i = 0; i < n_prompt; ++i) {
            batch.token[i] = prompt_tokens[static_cast<size_t>(i)];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_prompt - 1) ? 1 : 0;
        }

        const int32_t prompt_rc = impl_->api.decode(impl_->ctx, batch);
        if (prompt_rc != 0) {
            impl_->api.batch_free(batch);
            out_candidates.clear();
            return {
                false,
                "llama_decode failed during prompt prefill with code " +
                    std::to_string(prompt_rc)};
        }

        SynthesizedCandidate candidate;
        candidate.seed = seed;
        candidate.temperature = temperature;
        candidate.discrete_audio_tokens.reserve(static_cast<size_t>(max_tokens));

        int32_t cur_pos = n_prompt;
        int32_t last_logit_idx = n_prompt - 1;

        for (int32_t step = 0; step < max_tokens; ++step) {
            const float* logits = impl_->api.get_logits_ith(impl_->ctx, last_logit_idx);
            if (logits == nullptr) {
                impl_->api.batch_free(batch);
                out_candidates.clear();
                return {false, "llama_get_logits_ith returned null during token rollout"};
            }
            const int32_t next_token =
                SampleTokenTopP(logits, vocab_size, temperature, config_.top_p, rng);
            if (next_token == eos_token && !candidate.discrete_audio_tokens.empty()) {
                break;
            }
            candidate.discrete_audio_tokens.push_back(next_token);

            batch.n_tokens = 1;
            batch.token[0] = next_token;
            batch.pos[0] = cur_pos++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;
            last_logit_idx = 0;

            const int32_t step_rc = impl_->api.decode(impl_->ctx, batch);
            if (step_rc != 0) {
                impl_->api.batch_free(batch);
                out_candidates.clear();
                return {
                    false,
                    "llama_decode failed during autoregressive rollout with code " +
                        std::to_string(step_rc)};
            }
        }
        impl_->api.batch_free(batch);

        // Optional native/JNI vocoder callback decodes discrete codebook tokens to 44.1 kHz PCM
        // and runs Stage C NEON DSP mastering. When no callback is attached, pcm_44k stays empty
        // so LiteRtCodecDecoder in Kotlin performs decoding — NEVER fabricate fake PCM.
        if (vocoder_callback_) {
            const auto dec_status =
                vocoder_callback_(candidate.discrete_audio_tokens, candidate.pcm_44k);
            if (!dec_status.ok) {
                out_candidates.clear();
                return dec_status;
            }
            if (!candidate.pcm_44k.empty()) {
                MasterClausePcm(
                    candidate.pcm_44k,
                    impl_->room_ir.empty() ? nullptr : impl_->room_ir.data(),
                    impl_->room_ir.size(),
                    impl_->room_tone.empty() ? nullptr : impl_->room_tone.data(),
                    impl_->room_tone.size());
            }
        }

        out_candidates.push_back(std::move(candidate));
    }

    return {true, ""};
}

void VoiceEngine::SetVocoderCallback(VocoderDecodeCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    vocoder_callback_ = std::move(callback);
}

size_t VoiceEngine::EvictWeightPages() {
    std::lock_guard<std::mutex> lock(mutex_);
    size_t total_evicted = 0;
    bool any_failed = false;

    auto advise_region = [&](const MappedWeightFile& region) {
        if (region.addr != nullptr && region.mapped_size > 0) {
            if (::madvise(region.addr, region.mapped_size, MADV_DONTNEED) == 0) {
                total_evicted += region.mapped_size;
            } else {
                any_failed = true;
            }
        }
    };

    advise_region(impl_->base_gguf_map);
    advise_region(impl_->lora_gguf_map);

#if defined(__linux__)
    // Also advise llama.cpp's own mmap'd weight regions discovered in /proc/self/maps.
    total_evicted += EvictProcMapsMatchingPath(
        config_.base_gguf_path, impl_->base_gguf_map.addr, any_failed);
    total_evicted += EvictProcMapsMatchingPath(
        config_.lora_gguf_path, impl_->lora_gguf_map.addr, any_failed);
#endif

    last_evicted_bytes_.store(total_evicted, std::memory_order_release);
    last_eviction_succeeded_.store(!any_failed && total_evicted > 0, std::memory_order_release);
    return total_evicted;
}

bool VoiceEngine::IsInitialized() const {
    return initialized_.load(std::memory_order_acquire);
}

uint64_t VoiceEngine::LoraSwapCount() const {
    return lora_swap_count_.load(std::memory_order_acquire);
}

size_t VoiceEngine::LastEvictedBytes() const {
    return last_evicted_bytes_.load(std::memory_order_acquire);
}

bool VoiceEngine::LastEvictionSucceeded() const {
    return last_eviction_succeeded_.load(std::memory_order_acquire);
}

const std::string& VoiceEngine::ActiveLoraPath() const {
    return config_.lora_gguf_path;
}

}  // namespace voicestudio::engine
