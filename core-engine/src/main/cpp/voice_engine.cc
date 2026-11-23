#include "voice_engine.h"

#include "dsp_mastering.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
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
constexpr int32_t kDefaultLlamaContextSize = 512;
constexpr int32_t kPrefillChunkSize = 512;

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

bool PathIsDirectory(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool PathIsRegularFile(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string ParentDir(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) {
        return {};
    }
    if (slash == 0) {
        return "/";
    }
    return path.substr(0, slash);
}

std::string JoinPath(const std::string& dir, const std::string& name) {
    if (dir.empty()) {
        return name;
    }
    if (dir.back() == '/') {
        return dir + name;
    }
    return dir + "/" + name;
}

bool EndsWith(const std::string& s, const char* suffix) {
    const size_t suffix_len = std::strlen(suffix);
    return s.size() >= suffix_len &&
           s.compare(s.size() - suffix_len, suffix_len, suffix) == 0;
}

void AppendUnique(std::vector<std::string>& out, const std::string& value) {
    if (value.empty()) {
        return;
    }
    if (std::find(out.begin(), out.end(), value) == out.end()) {
        out.push_back(value);
    }
}

std::string DetectCurrentSharedObjectDir() {
#if defined(__ANDROID__) || defined(__APPLE__)
    Dl_info info{};
    if (::dladdr(reinterpret_cast<const void*>(&ValidateGgufFile), &info) != 0 &&
        info.dli_fname != nullptr) {
        const std::string self_path(info.dli_fname);
        if (EndsWith(self_path, ".so") || EndsWith(self_path, ".dylib")) {
            return ParentDir(self_path);
        }
    }
#endif
    return {};
}

void QuietLlamaErrorOnlyLog(int level, const char* text, void* /*user_data*/) {
    // ggml_log_level: NONE=0, DEBUG=1, INFO=2, WARN=3, ERROR=4, CONT=5
    if (level == 4 && text != nullptr) {
        std::fputs(text, stderr);
    }
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
struct llama_sampler;
struct llama_memory_i;
using llama_memory_t = llama_memory_i*;
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

struct llama_token_data {
    llama_token id;
    float logit;
    float p;
};

struct llama_token_data_array {
    llama_token_data* data;
    size_t size;
    int64_t selected;
    bool sorted;
};

struct llama_sampler_chain_params {
    bool no_perf;
};
#endif

struct LlamaApiTable {
    void* dl_handle = nullptr;
    std::vector<void*> companion_handles;
    std::string loaded_library_path;

    void (*log_set)(void (*)(int, const char*, void*), void*) = nullptr;
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
    uint32_t (*n_ctx)(const llama_context*) = nullptr;
    void (*set_n_threads)(llama_context*, int32_t, int32_t) = nullptr;

    llama_adapter_lora* (*adapter_lora_init)(llama_model*, const char*) = nullptr;
    int32_t (*set_adapter_lora)(llama_context*, llama_adapter_lora*, float) = nullptr;
    int32_t (*set_adapters_lora)(llama_context*, llama_adapter_lora**, size_t, float*) = nullptr;
    int32_t (*rm_adapter_lora)(llama_context*, llama_adapter_lora*) = nullptr;
    void (*clear_adapter_lora)(llama_context*) = nullptr;
    void (*adapter_lora_free)(llama_adapter_lora*) = nullptr;

    const llama_vocab* (*model_get_vocab)(const llama_model*) = nullptr;
    int32_t (*vocab_n_tokens)(const llama_vocab*) = nullptr;
    llama_token (*vocab_eos)(const llama_vocab*) = nullptr;
    llama_token (*vocab_eot)(const llama_vocab*) = nullptr;
    int32_t (*tokenize_vocab)(
        const llama_vocab*, const char*, int32_t, llama_token*, int32_t, bool, bool) = nullptr;
    int32_t (*tokenize_model)(
        const llama_model*, const char*, int32_t, llama_token*, int32_t, bool, bool) = nullptr;

    llama_batch (*batch_init)(int32_t, int32_t, int32_t) = nullptr;
    void (*batch_free)(llama_batch) = nullptr;
    int32_t (*decode)(llama_context*, llama_batch) = nullptr;
    float* (*get_logits_ith)(llama_context*, int32_t) = nullptr;

    llama_memory_t (*get_memory)(const llama_context*) = nullptr;
    void (*memory_clear)(llama_memory_t, bool) = nullptr;
    void (*kv_cache_clear)(llama_context*) = nullptr;

    llama_sampler_chain_params (*sampler_chain_default_params)() = nullptr;
    llama_sampler* (*sampler_chain_init)(llama_sampler_chain_params) = nullptr;
    void (*sampler_chain_add)(llama_sampler*, llama_sampler*) = nullptr;
    llama_sampler* (*sampler_init_top_k)(int32_t) = nullptr;
    llama_sampler* (*sampler_init_top_p)(float, size_t) = nullptr;
    llama_sampler* (*sampler_init_temp)(float) = nullptr;
    llama_sampler* (*sampler_init_dist)(uint32_t) = nullptr;
    llama_sampler* (*sampler_init_greedy)() = nullptr;
    void (*sampler_apply)(llama_sampler*, llama_token_data_array*) = nullptr;
    void (*sampler_accept)(llama_sampler*, llama_token) = nullptr;
    llama_token (*sampler_sample)(llama_sampler*, llama_context*, int32_t) = nullptr;
    void (*sampler_free)(llama_sampler*) = nullptr;

    bool IsLoaded() const {
        const bool has_lora_bind = (set_adapter_lora != nullptr) || (set_adapters_lora != nullptr);
        return model_load_from_file != nullptr &&
               model_free != nullptr &&
               init_from_model != nullptr &&
               free_ctx != nullptr &&
               adapter_lora_init != nullptr &&
               has_lora_bind &&
               adapter_lora_free != nullptr;
    }

    bool HasSamplerApi() const {
        return sampler_chain_default_params != nullptr &&
               sampler_chain_init != nullptr &&
               sampler_chain_add != nullptr &&
               sampler_init_top_k != nullptr &&
               sampler_init_top_p != nullptr &&
               sampler_init_temp != nullptr &&
               sampler_init_dist != nullptr &&
               sampler_init_greedy != nullptr &&
               sampler_apply != nullptr &&
               sampler_accept != nullptr &&
               sampler_free != nullptr;
    }

    int32_t AttachLora(llama_context* ctx, llama_adapter_lora* lora, float scale) const {
        if (set_adapter_lora != nullptr) {
            return set_adapter_lora(ctx, lora, scale);
        }
        if (set_adapters_lora != nullptr) {
            llama_adapter_lora* adapters[1] = {lora};
            float scales[1] = {scale};
            return set_adapters_lora(ctx, adapters, 1, scales);
        }
        return -1;
    }

    void DetachLora(llama_context* ctx, llama_adapter_lora* lora) const {
        if (ctx == nullptr) {
            return;
        }
        if (rm_adapter_lora != nullptr && lora != nullptr) {
            rm_adapter_lora(ctx, lora);
        } else if (clear_adapter_lora != nullptr) {
            clear_adapter_lora(ctx);
        } else if (set_adapters_lora != nullptr) {
            set_adapters_lora(ctx, nullptr, 0, nullptr);
        }
    }

    void ClearKvCache(llama_context* ctx) const {
        if (ctx == nullptr) {
            return;
        }
        if (get_memory != nullptr && memory_clear != nullptr) {
            llama_memory_t mem = get_memory(ctx);
            if (mem != nullptr) {
                memory_clear(mem, true);
                return;
            }
        }
        if (kv_cache_clear != nullptr) {
            kv_cache_clear(ctx);
        }
    }

    void Unload() {
        if (dl_handle != nullptr) {
            if (log_set != nullptr) {
                log_set(nullptr, nullptr);
            }
            ::dlclose(dl_handle);
            dl_handle = nullptr;
        }
        for (auto it = companion_handles.rbegin(); it != companion_handles.rend(); ++it) {
            if (*it != nullptr) {
                ::dlclose(*it);
            }
        }
        companion_handles.clear();
        loaded_library_path.clear();
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

void PreloadCompanionLibraries(
    const std::string& lib_path,
    std::vector<void*>& out_handles,
    std::string& out_last_error) {
    const std::string lib_dir = ParentDir(lib_path);
#if defined(__APPLE__)
    static constexpr std::array<const char*, 5> kCompanions = {
        "libggml-base.dylib",
        "libggml-metal.dylib",
        "libggml-blas.dylib",
        "libggml-cpu.dylib",
        "libggml.dylib",
    };
#else
    static constexpr std::array<const char*, 3> kCompanions = {
        "libggml-base.so",
        "libggml-cpu.so",
        "libggml.so",
    };
#endif

    for (const char* comp_name : kCompanions) {
        std::string comp_target;
        if (!lib_dir.empty()) {
            comp_target = JoinPath(lib_dir, comp_name);
            if (!PathIsRegularFile(comp_target)) {
                continue;
            }
        } else {
            comp_target = comp_name;
        }

        ::dlerror();
        void* handle = ::dlopen(comp_target.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle != nullptr) {
            out_handles.push_back(handle);
        } else {
            const char* err = ::dlerror();
            if (err != nullptr && !lib_dir.empty()) {
                out_last_error = std::string("companion ") + comp_target + ": " + err;
            }
        }
    }
}

void CloseHandlesReverse(std::vector<void*>& handles) {
    for (auto it = handles.rbegin(); it != handles.rend(); ++it) {
        if (*it != nullptr) {
            ::dlclose(*it);
        }
    }
    handles.clear();
}

EngineStatus LoadLlamaRuntime(const EngineConfig& config, LlamaApiTable& api) {
    api.Unload();

#if defined(VOICESTUDIO_HAS_LLAMA_CPP)
    if (config.llama_library_path.empty()) {
        api.backend_init = &llama_backend_init;
        api.backend_free = &llama_backend_free;
        api.model_default_params = &llama_model_default_params;
        api.context_default_params = &llama_context_default_params;
        api.model_load_from_file = &llama_model_load_from_file;
        api.model_free = &llama_model_free;
        api.init_from_model = &llama_init_from_model;
        api.free_ctx = &llama_free;
        api.n_ctx = &llama_n_ctx;
        api.set_n_threads = &llama_set_n_threads;
        api.adapter_lora_init = &llama_adapter_lora_init;
        api.set_adapters_lora = &llama_set_adapters_lora;
        api.adapter_lora_free = &llama_adapter_lora_free;
        api.model_get_vocab = &llama_model_get_vocab;
        api.vocab_n_tokens = &llama_vocab_n_tokens;
        api.vocab_eos = &llama_vocab_eos;
        api.vocab_eot = &llama_vocab_eot;
        api.tokenize_vocab = &llama_tokenize;
        api.batch_init = &llama_batch_init;
        api.batch_free = &llama_batch_free;
        api.decode = &llama_decode;
        api.get_logits_ith = &llama_get_logits_ith;
        api.get_memory = &llama_get_memory;
        api.memory_clear = &llama_memory_clear;
        api.sampler_chain_default_params = &llama_sampler_chain_default_params;
        api.sampler_chain_init = &llama_sampler_chain_init;
        api.sampler_chain_add = &llama_sampler_chain_add;
        api.sampler_init_top_k = &llama_sampler_init_top_k;
        api.sampler_init_top_p = &llama_sampler_init_top_p;
        api.sampler_init_temp = &llama_sampler_init_temp;
        api.sampler_init_dist = &llama_sampler_init_dist;
        api.sampler_init_greedy = &llama_sampler_init_greedy;
        api.sampler_apply = &llama_sampler_apply;
        api.sampler_accept = &llama_sampler_accept;
        api.sampler_sample = &llama_sampler_sample;
        api.sampler_free = &llama_sampler_free;
        api.loaded_library_path = "<linked:llama>";
        return {true, ""};
    }
#endif

    const std::vector<std::string> candidates = BuildLlamaLibraryCandidates(config);
    std::string last_dl_error;

    for (const auto& lib_name : candidates) {
        // Skip absolute paths that do not exist so dlerror preserves real loader failures.
        if (!lib_name.empty() && lib_name[0] == '/' && !PathIsRegularFile(lib_name)) {
            if (last_dl_error.empty()) {
                last_dl_error = "Library not found: " + lib_name;
            }
            continue;
        }

        std::vector<void*> companions;
        std::string companion_err;
        PreloadCompanionLibraries(lib_name, companions, companion_err);

        ::dlerror();
        void* handle = ::dlopen(lib_name.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle != nullptr) {
            api.dl_handle = handle;
            api.companion_handles = std::move(companions);
            api.loaded_library_path = lib_name;
            break;
        }

        const char* err = ::dlerror();
        if (err != nullptr) {
            last_dl_error = lib_name + ": " + err;
            if (!companion_err.empty()) {
                last_dl_error += " (" + companion_err + ")";
            }
        }
        CloseHandlesReverse(companions);
    }

    if (api.dl_handle == nullptr) {
        return {
            false,
            "llama.cpp runtime library (libllama) not available: " +
                (last_dl_error.empty() ? "dlopen returned null" : last_dl_error)};
    }

    api.log_set = ResolveAnySymbol<decltype(api.log_set)>(api.dl_handle, {"llama_log_set"});
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
    api.n_ctx = ResolveAnySymbol<decltype(api.n_ctx)>(
        api.dl_handle, {"llama_n_ctx"});
    api.set_n_threads = ResolveAnySymbol<decltype(api.set_n_threads)>(
        api.dl_handle, {"llama_set_n_threads"});

    api.adapter_lora_init = ResolveAnySymbol<decltype(api.adapter_lora_init)>(
        api.dl_handle, {"llama_adapter_lora_init", "llama_lora_adapter_init"});
    api.set_adapter_lora = ResolveAnySymbol<decltype(api.set_adapter_lora)>(
        api.dl_handle, {"llama_set_adapter_lora", "llama_lora_adapter_set"});
    api.set_adapters_lora = ResolveAnySymbol<decltype(api.set_adapters_lora)>(
        api.dl_handle, {"llama_set_adapters_lora"});
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
    api.vocab_eot = ResolveAnySymbol<decltype(api.vocab_eot)>(
        api.dl_handle, {"llama_vocab_eot", "llama_token_eot"});
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

    api.get_memory = ResolveAnySymbol<decltype(api.get_memory)>(
        api.dl_handle, {"llama_get_memory"});
    api.memory_clear = ResolveAnySymbol<decltype(api.memory_clear)>(
        api.dl_handle, {"llama_memory_clear"});
    api.kv_cache_clear = ResolveAnySymbol<decltype(api.kv_cache_clear)>(
        api.dl_handle, {"llama_kv_cache_clear"});

    api.sampler_chain_default_params =
        ResolveAnySymbol<decltype(api.sampler_chain_default_params)>(
            api.dl_handle, {"llama_sampler_chain_default_params"});
    api.sampler_chain_init = ResolveAnySymbol<decltype(api.sampler_chain_init)>(
        api.dl_handle, {"llama_sampler_chain_init"});
    api.sampler_chain_add = ResolveAnySymbol<decltype(api.sampler_chain_add)>(
        api.dl_handle, {"llama_sampler_chain_add"});
    api.sampler_init_top_k = ResolveAnySymbol<decltype(api.sampler_init_top_k)>(
        api.dl_handle, {"llama_sampler_init_top_k"});
    api.sampler_init_top_p = ResolveAnySymbol<decltype(api.sampler_init_top_p)>(
        api.dl_handle, {"llama_sampler_init_top_p"});
    api.sampler_init_temp = ResolveAnySymbol<decltype(api.sampler_init_temp)>(
        api.dl_handle, {"llama_sampler_init_temp"});
    api.sampler_init_dist = ResolveAnySymbol<decltype(api.sampler_init_dist)>(
        api.dl_handle, {"llama_sampler_init_dist"});
    api.sampler_init_greedy = ResolveAnySymbol<decltype(api.sampler_init_greedy)>(
        api.dl_handle, {"llama_sampler_init_greedy"});
    api.sampler_apply = ResolveAnySymbol<decltype(api.sampler_apply)>(
        api.dl_handle, {"llama_sampler_apply"});
    api.sampler_accept = ResolveAnySymbol<decltype(api.sampler_accept)>(
        api.dl_handle, {"llama_sampler_accept"});
    api.sampler_sample = ResolveAnySymbol<decltype(api.sampler_sample)>(
        api.dl_handle, {"llama_sampler_sample"});
    api.sampler_free = ResolveAnySymbol<decltype(api.sampler_free)>(
        api.dl_handle, {"llama_sampler_free"});

    if (!api.IsLoaded() || api.model_default_params == nullptr || api.context_default_params == nullptr) {
        api.Unload();
        return {false, "Loaded libllama is missing required GGUF/LoRA C API symbols"};
    }
    return {true, ""};
}

int32_t SampleCandidatesFallback(
    std::vector<llama_token_data>& candidates,
    float temperature,
    float top_p,
    int32_t top_k,
    std::mt19937_64& rng) {
    if (candidates.empty()) {
        return 0;
    }
    if (temperature <= 1e-4f) {
        const auto best = std::max_element(
            candidates.begin(),
            candidates.end(),
            [](const llama_token_data& a, const llama_token_data& b) {
                return a.logit < b.logit;
            });
        return best->id;
    }

    const size_t k_limit = (top_k > 0)
        ? std::min<size_t>(candidates.size(), static_cast<size_t>(top_k))
        : candidates.size();

    std::partial_sort(
        candidates.begin(),
        candidates.begin() + static_cast<std::ptrdiff_t>(k_limit),
        candidates.end(),
        [](const llama_token_data& a, const llama_token_data& b) {
            if (a.logit != b.logit) return a.logit > b.logit;
            return a.id < b.id;
        });
    candidates.resize(k_limit);

    const float max_logit = candidates[0].logit;
    const float inv_temp = 1.0f / std::max(temperature, 1e-4f);
    std::vector<double> probs(k_limit, 0.0);
    double sum = 0.0;
    for (size_t i = 0; i < k_limit; ++i) {
        const double p = std::exp(static_cast<double>((candidates[i].logit - max_logit) * inv_temp));
        probs[i] = p;
        sum += p;
    }
    if (sum <= 0.0 || !std::isfinite(sum)) {
        return candidates[0].id;
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
            return candidates[i].id;
        }
    }
    return candidates[keep - 1].id;
}

bool TokenizeAppendSegment(
    const LlamaApiTable& api,
    const llama_model* model,
    const llama_vocab* vocab,
    const std::string& segment,
    bool add_special,
    bool parse_special,
    std::vector<llama_token>& out_tokens) {
    if (segment.empty()) {
        return true;
    }
    int32_t capacity = static_cast<int32_t>(segment.size()) + 16;
    std::vector<llama_token> buf(static_cast<size_t>(capacity));
    auto invoke = [&](int32_t cap) -> int32_t {
        if (vocab != nullptr && api.tokenize_vocab != nullptr) {
            return api.tokenize_vocab(
                vocab,
                segment.c_str(),
                static_cast<int32_t>(segment.size()),
                buf.data(),
                cap,
                add_special,
                parse_special);
        }
        if (api.tokenize_model != nullptr) {
            return api.tokenize_model(
                model,
                segment.c_str(),
                static_cast<int32_t>(segment.size()),
                buf.data(),
                cap,
                add_special,
                parse_special);
        }
        return -1;
    };

    int32_t n = invoke(capacity);
    if (n < 0 && n != INT32_MIN) {
        capacity = -n + 4;
        buf.resize(static_cast<size_t>(capacity));
        n = invoke(capacity);
    }
    if (n < 0) {
        return false;
    }
    out_tokens.insert(out_tokens.end(), buf.begin(), buf.begin() + n);
    return true;
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

std::vector<std::string> BuildLlamaLibraryCandidates(const EngineConfig& config) {
    std::vector<std::string> candidates;
#if defined(__APPLE__)
    static constexpr const char* kPrimaryLibName = "libllama.dylib";
    static constexpr const char* kAltLibName = "libllama.so";
#else
    static constexpr const char* kPrimaryLibName = "libllama.so";
    static constexpr const char* kAltLibName = "libllama_jni.so";
#endif

    // 1. Explicit library file or directory path takes strict priority (never silently fall back
    //    to an unrelated system library if an explicit path is broken — Constitutional Law 2).
    if (!config.llama_library_path.empty()) {
        if (PathIsDirectory(config.llama_library_path)) {
            AppendUnique(candidates, JoinPath(config.llama_library_path, kPrimaryLibName));
            AppendUnique(
                candidates,
                JoinPath(JoinPath(config.llama_library_path, "arm64-v8a"), kPrimaryLibName));
            AppendUnique(candidates, JoinPath(config.llama_library_path, kAltLibName));
        } else {
            AppendUnique(candidates, config.llama_library_path);
        }
        return candidates;
    }

    // 2. Explicit app nativeLibraryDir (e.g. /data/app/.../lib/arm64).
    if (!config.native_library_dir.empty()) {
        AppendUnique(candidates, JoinPath(config.native_library_dir, kPrimaryLibName));
        AppendUnique(candidates, JoinPath(config.native_library_dir, kAltLibName));
    }

    // 3. Auto-detected nativeLibraryDir hosting libvoicestudio_jni.so via dladdr().
    const std::string self_so_dir = DetectCurrentSharedObjectDir();
    if (!self_so_dir.empty()) {
        AppendUnique(candidates, JoinPath(self_so_dir, kPrimaryLibName));
        AppendUnique(candidates, JoinPath(self_so_dir, kAltLibName));
    }

    // 4. OTA bundle-relative android-libs/ directories derived from base_gguf_path
    //    (e.g. noBackupFilesDir/bundles/{ver}/android-libs[/arm64-v8a]/libllama.so).
    const std::string bundle_dir = ParentDir(config.base_gguf_path);
    if (!bundle_dir.empty()) {
        const std::string bundle_android_libs = JoinPath(bundle_dir, "android-libs");
        AppendUnique(candidates, JoinPath(bundle_android_libs, kPrimaryLibName));
        AppendUnique(
            candidates,
            JoinPath(JoinPath(bundle_android_libs, "arm64-v8a"), kPrimaryLibName));
        AppendUnique(candidates, JoinPath(bundle_dir, kPrimaryLibName));

        const std::string bundles_parent = ParentDir(bundle_dir);
        if (!bundles_parent.empty()) {
            const std::string parent_android_libs = JoinPath(bundles_parent, "android-libs");
            AppendUnique(candidates, JoinPath(parent_android_libs, kPrimaryLibName));
            AppendUnique(
                candidates,
                JoinPath(JoinPath(parent_android_libs, "arm64-v8a"), kPrimaryLibName));
        }
    }

    // 5. Standard dynamic linker search names (bionic app nativeLibraryDir namespace / macOS rpath).
#if defined(__APPLE__)
    AppendUnique(candidates, "libllama.dylib");
    AppendUnique(candidates, "@rpath/libllama.dylib");
#else
    AppendUnique(candidates, "libllama.so");
    AppendUnique(candidates, "libllama_jni.so");
#endif

    return candidates;
}

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
            api.DetachLora(ctx, active_lora);
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
    resolved_llama_lib_path_.clear();
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

    // 3. Load real llama.cpp runtime (nativeLibraryDir or OTA dir, preloading ggml companions with RTLD_GLOBAL).
    if (const auto st = LoadLlamaRuntime(config, impl_->api); !st.ok) {
        impl_->ReleaseNativeHandles();
        return st;
    }

    if (impl_->api.log_set != nullptr) {
        impl_->api.log_set(&QuietLlamaErrorOnlyLog, nullptr);
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
#if defined(VOICESTUDIO_HAS_LLAMA_CPP)
    if (config.n_ctx >= kDefaultLlamaContextSize) {
        ctx_params.n_ctx = static_cast<uint32_t>(config.n_ctx);
    }
    if (config.n_threads > 0) {
        ctx_params.n_threads = config.n_threads;
        ctx_params.n_threads_batch = config.n_threads;
    }
#else
    // Offset 0 of llama_context_params is uint32_t n_ctx (default 512 in llama.cpp).
    // Patch safely only when the returned default matches llama.cpp's canonical 512.
    uint32_t default_n_ctx = 0;
    std::memcpy(&default_n_ctx, ctx_params.bytes, sizeof(uint32_t));
    if (default_n_ctx == static_cast<uint32_t>(kDefaultLlamaContextSize) &&
        config.n_ctx >= kDefaultLlamaContextSize) {
        const uint32_t desired_ctx = static_cast<uint32_t>(config.n_ctx);
        std::memcpy(ctx_params.bytes, &desired_ctx, sizeof(uint32_t));
    }
#endif

    impl_->ctx = impl_->api.init_from_model(impl_->model, ctx_params);
    if (impl_->ctx == nullptr) {
        impl_->ReleaseNativeHandles();
        return {false, "llama_init_from_model failed for: " + config.base_gguf_path};
    }
    if (impl_->api.set_n_threads != nullptr && config.n_threads > 0) {
        impl_->api.set_n_threads(impl_->ctx, config.n_threads, config.n_threads);
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
            impl_->api.AttachLora(impl_->ctx, impl_->active_lora, config.lora_scale);
        if (rc != 0) {
            impl_->ReleaseNativeHandles();
            return {
                false,
                "llama_set_adapter_lora failed with status " + std::to_string(rc) +
                    " for: " + config.lora_gguf_path};
        }
    }

    config_ = config;
    resolved_llama_lib_path_ = impl_->api.loaded_library_path;
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
        impl_->api.DetachLora(impl_->ctx, prev_lora);
    }

    const int32_t rc = impl_->api.AttachLora(impl_->ctx, next_lora, config_.lora_scale);
    if (rc != 0) {
        impl_->api.adapter_lora_free(next_lora);
        // Roll back to prior adapter so engine remains consistent.
        if (prev_lora != nullptr) {
            impl_->api.AttachLora(impl_->ctx, prev_lora, config_.lora_scale);
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

EngineStatus VoiceEngine::SynthesizeClause(
    const std::string& normalized_text,
    const std::string& prefix_pcm_path,
    const ClauseSamplingParams& sampling,
    SynthesizedCandidate& out_candidate) {
    out_candidate = SynthesizedCandidate{};

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_.load(std::memory_order_acquire) ||
        impl_->model == nullptr ||
        impl_->ctx == nullptr) {
        return {false, "VoiceEngine is not initialized"};
    }
    if (normalized_text.empty()) {
        return {false, "normalized_text must not be empty"};
    }

    std::vector<float> prefix_tail;
    if (!prefix_pcm_path.empty()) {
        if (!ReadPcmFile44k(prefix_pcm_path, prefix_tail) || prefix_tail.empty()) {
            return {false, "Acoustic prefix PCM is missing or unreadable: " + prefix_pcm_path};
        }
        if (prefix_tail.size() > kPrefixTailSamples) {
            prefix_tail = ExtractAcousticPrefixTail(prefix_tail, kPrefixTailSamples);
        }
    }

    return SynthesizeClauseLocked(normalized_text, prefix_tail, sampling, out_candidate);
}

EngineStatus VoiceEngine::SynthesizeClauseLocked(
    const std::string& normalized_text,
    const std::vector<float>& prefix_tail,
    const ClauseSamplingParams& sampling,
    SynthesizedCandidate& out_candidate) {
    out_candidate = SynthesizedCandidate{};

    if (impl_->api.batch_init == nullptr ||
        impl_->api.batch_free == nullptr ||
        impl_->api.decode == nullptr ||
        impl_->api.get_logits_ith == nullptr) {
        return {
            false,
            "Loaded llama.cpp runtime is missing token rollout symbols (batch/decode/logits)"};
    }

    const llama_vocab* vocab =
        (impl_->api.model_get_vocab != nullptr) ? impl_->api.model_get_vocab(impl_->model) : nullptr;
    const int32_t vocab_size =
        (vocab != nullptr && impl_->api.vocab_n_tokens != nullptr)
            ? impl_->api.vocab_n_tokens(vocab)
            : 0;
    if (vocab_size <= 0) {
        return {false, "Unable to query llama.cpp vocabulary size"};
    }

    const bool is_s2_pro_vocab = (vocab_size > kS2ProSemanticEndTokenId);
    const llama_token eos_token =
        is_s2_pro_vocab
            ? kS2ProImEndTokenId
            : ((vocab != nullptr && impl_->api.vocab_eos != nullptr)
                   ? impl_->api.vocab_eos(vocab)
                   : -1);

    // Tokenize exact s2-pro Dual-AR chat template (segment-by-segment so BPE never merges
    // across <|im_start|>, <|im_end|>, <|speaker:0|>, or <|voice|> delimiters).
    std::vector<llama_token> prompt_tokens;
    prompt_tokens.reserve(normalized_text.size() + 64);

    if (is_s2_pro_vocab) {
        std::string speaker_text = normalized_text;
        if (speaker_text.find("<|speaker:") == std::string::npos) {
            speaker_text = "<|speaker:0|>" + speaker_text;
        }
        const std::array<std::string, 7> segments = {
            "<|im_start|>system\n",
            "convert the provided text to speech",
            "<|im_end|>\n",
            "<|im_start|>user\n",
            speaker_text,
            "<|im_end|>\n",
            "<|im_start|>assistant\n<|voice|>",
        };
        for (const auto& seg : segments) {
            if (!TokenizeAppendSegment(
                    impl_->api,
                    impl_->model,
                    vocab,
                    seg,
                    /*add_special=*/false,
                    /*parse_special=*/true,
                    prompt_tokens)) {
                return {false, "llama_tokenize failed for s2-pro clause prompt"};
            }
        }
    } else {
        std::string prompt = "<|s2_pro_text|>" + normalized_text;
        if (!prefix_tail.empty()) {
            prompt += "<|acoustic_prefix_samples:" + std::to_string(prefix_tail.size()) + "|>";
        }
        prompt += "<|audio_start|>";
        if (!TokenizeAppendSegment(
                impl_->api,
                impl_->model,
                vocab,
                prompt,
                /*add_special=*/true,
                /*parse_special=*/true,
                prompt_tokens)) {
            return {false, "llama_tokenize failed for clause text"};
        }
    }

    if (prompt_tokens.empty()) {
        return {false, "llama_tokenize produced zero prompt tokens"};
    }

    const int32_t n_prompt = static_cast<int32_t>(prompt_tokens.size());
    const int32_t ctx_limit =
        (impl_->api.n_ctx != nullptr)
            ? static_cast<int32_t>(impl_->api.n_ctx(impl_->ctx))
            : std::max(config_.n_ctx, kDefaultLlamaContextSize);
    if (n_prompt >= ctx_limit) {
        return {
            false,
            "Clause prompt length (" + std::to_string(n_prompt) +
                ") exceeds context window (" + std::to_string(ctx_limit) + ")"};
    }

    const int32_t requested_max = std::clamp(sampling.max_audio_tokens, 1, 4096);
    const int32_t max_tokens = std::min(requested_max, ctx_limit - n_prompt);

    impl_->api.ClearKvCache(impl_->ctx);

    const auto t0 = std::chrono::steady_clock::now();

    // RAII guard for llama_batch so no path can ever leak native allocations.
    const int32_t batch_cap = std::min<int32_t>(std::max<int32_t>(n_prompt, 1), kPrefillChunkSize);
    llama_batch batch = impl_->api.batch_init(batch_cap, /*embd=*/0, /*n_seq_max=*/1);
    struct BatchGuard {
        const LlamaApiTable& api;
        llama_batch b;
        ~BatchGuard() {
            if (api.batch_free != nullptr) {
                api.batch_free(b);
            }
        }
    } batch_guard{impl_->api, batch};

    // Prefill prompt in chunks <= kPrefillChunkSize.
    int32_t last_logit_idx = 0;
    for (int32_t offset = 0; offset < n_prompt; offset += batch_cap) {
        const int32_t chunk = std::min(batch_cap, n_prompt - offset);
        batch_guard.b.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            batch_guard.b.token[i] = prompt_tokens[static_cast<size_t>(offset + i)];
            batch_guard.b.pos[i] = offset + i;
            batch_guard.b.n_seq_id[i] = 1;
            batch_guard.b.seq_id[i][0] = 0;
            const bool is_final_prompt_token = (offset + i == n_prompt - 1);
            batch_guard.b.logits[i] = is_final_prompt_token ? 1 : 0;
        }
        last_logit_idx = chunk - 1;
        const int32_t prompt_rc = impl_->api.decode(impl_->ctx, batch_guard.b);
        if (prompt_rc != 0) {
            return {
                false,
                "llama_decode failed during prompt prefill with code " +
                    std::to_string(prompt_rc)};
        }
    }

    // Build real llama_sampler chain (top_k -> top_p -> temp -> dist/greedy) when exported.
    struct SamplerGuard {
        const LlamaApiTable& api;
        llama_sampler* chain = nullptr;
        ~SamplerGuard() {
            if (chain != nullptr && api.sampler_free != nullptr) {
                api.sampler_free(chain);
            }
        }
    } sampler_guard{impl_->api, nullptr};

    const bool use_llama_sampler = impl_->api.HasSamplerApi();
    if (use_llama_sampler) {
        const auto sparams = impl_->api.sampler_chain_default_params();
        sampler_guard.chain = impl_->api.sampler_chain_init(sparams);
        if (sampler_guard.chain == nullptr) {
            return {false, "llama_sampler_chain_init returned null"};
        }
        if (sampling.temperature <= 1e-4f) {
            impl_->api.sampler_chain_add(sampler_guard.chain, impl_->api.sampler_init_greedy());
        } else {
            if (sampling.top_k > 0) {
                impl_->api.sampler_chain_add(
                    sampler_guard.chain, impl_->api.sampler_init_top_k(sampling.top_k));
            }
            const float clamped_top_p = std::clamp(sampling.top_p, 0.05f, 1.0f);
            impl_->api.sampler_chain_add(
                sampler_guard.chain, impl_->api.sampler_init_top_p(clamped_top_p, /*min_keep=*/1));
            impl_->api.sampler_chain_add(
                sampler_guard.chain, impl_->api.sampler_init_temp(sampling.temperature));
            impl_->api.sampler_chain_add(
                sampler_guard.chain,
                impl_->api.sampler_init_dist(static_cast<uint32_t>(sampling.seed)));
        }
    }

    std::mt19937_64 fallback_rng(static_cast<uint64_t>(sampling.seed));

    SynthesizedCandidate candidate;
    candidate.seed = sampling.seed;
    candidate.temperature = sampling.temperature;
    candidate.discrete_audio_tokens.reserve(static_cast<size_t>(max_tokens));

    std::vector<llama_token_data> cur_data;
    if (is_s2_pro_vocab) {
        cur_data.reserve(static_cast<size_t>(
            (kS2ProSemanticEndTokenId - kS2ProSemanticStartTokenId + 1) + 1));
    } else {
        cur_data.reserve(static_cast<size_t>(vocab_size));
    }

    int32_t cur_pos = n_prompt;
    for (int32_t step = 0; step < max_tokens; ++step) {
        const float* logits = impl_->api.get_logits_ith(impl_->ctx, last_logit_idx);
        if (logits == nullptr) {
            return {false, "llama_get_logits_ith returned null during token rollout"};
        }

        cur_data.clear();
        if (is_s2_pro_vocab) {
            // Restrict candidate set strictly to s2-pro semantic codebook tokens [151678..155773]
            // plus <|im_end|> (151645) once at least one semantic token has been emitted.
            for (int32_t tid = kS2ProSemanticStartTokenId; tid <= kS2ProSemanticEndTokenId; ++tid) {
                const float v = std::isfinite(logits[tid]) ? logits[tid] : -1e30f;
                cur_data.push_back(llama_token_data{tid, v, 0.0f});
            }
            if (!candidate.discrete_audio_tokens.empty()) {
                const float eos_v =
                    std::isfinite(logits[kS2ProImEndTokenId]) ? logits[kS2ProImEndTokenId] : -1e30f;
                cur_data.push_back(llama_token_data{kS2ProImEndTokenId, eos_v, 0.0f});
            }
        } else {
            for (int32_t tid = 0; tid < vocab_size; ++tid) {
                const float v = std::isfinite(logits[tid]) ? logits[tid] : -1e30f;
                cur_data.push_back(llama_token_data{tid, v, 0.0f});
            }
        }

        int32_t next_token = -1;
        if (use_llama_sampler) {
            llama_token_data_array cur_p{
                cur_data.data(),
                cur_data.size(),
                /*selected=*/-1,
                /*sorted=*/false};
            impl_->api.sampler_apply(sampler_guard.chain, &cur_p);
            if (cur_p.size == 0) {
                return {false, "llama_sampler_apply left zero candidate tokens"};
            }
            const size_t sel_idx =
                (cur_p.selected >= 0 && static_cast<size_t>(cur_p.selected) < cur_p.size)
                    ? static_cast<size_t>(cur_p.selected)
                    : 0U;
            next_token = cur_p.data[sel_idx].id;
            impl_->api.sampler_accept(sampler_guard.chain, next_token);
        } else {
            next_token = SampleCandidatesFallback(
                cur_data,
                sampling.temperature,
                sampling.top_p,
                sampling.top_k,
                fallback_rng);
        }

        if (next_token == eos_token && !candidate.discrete_audio_tokens.empty()) {
            candidate.hit_eos = true;
            break;
        }
        if (is_s2_pro_vocab &&
            (next_token < kS2ProSemanticStartTokenId || next_token > kS2ProSemanticEndTokenId)) {
            return {
                false,
                "Sampled non-semantic token outside [151678..155773]: " +
                    std::to_string(next_token)};
        }

        candidate.discrete_audio_tokens.push_back(next_token);

        if (step + 1 < max_tokens) {
            batch_guard.b.n_tokens = 1;
            batch_guard.b.token[0] = next_token;
            batch_guard.b.pos[0] = cur_pos++;
            batch_guard.b.n_seq_id[0] = 1;
            batch_guard.b.seq_id[0][0] = 0;
            batch_guard.b.logits[0] = 1;
            last_logit_idx = 0;

            const int32_t step_rc = impl_->api.decode(impl_->ctx, batch_guard.b);
            if (step_rc != 0) {
                return {
                    false,
                    "llama_decode failed during autoregressive rollout with code " +
                        std::to_string(step_rc)};
            }
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    candidate.elapsed_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    if (candidate.elapsed_ms > 1e-6 && !candidate.discrete_audio_tokens.empty()) {
        candidate.tokens_per_sec =
            static_cast<double>(candidate.discrete_audio_tokens.size()) /
            (candidate.elapsed_ms / 1000.0);
    }

    // Optional native/JNI vocoder callback decodes discrete codebook tokens to 44.1 kHz PCM
    // and runs Stage C NEON DSP mastering. When no callback is attached, pcm_44k stays empty
    // so LiteRtCodecDecoder in Kotlin performs decoding — NEVER fabricate fake PCM.
    if (vocoder_callback_) {
        const auto dec_status =
            vocoder_callback_(candidate.discrete_audio_tokens, candidate.pcm_44k);
        if (!dec_status.ok) {
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

    out_candidate = std::move(candidate);
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

    out_candidates.reserve(static_cast<size_t>(num_candidates));
    for (int32_t c = 0; c < num_candidates; ++c) {
        ClauseSamplingParams sampling;
        sampling.seed = config_.base_seed + static_cast<int64_t>(c);
        sampling.temperature = config_.default_temperature;
        sampling.top_p = config_.top_p;
        sampling.top_k = config_.top_k;
        sampling.max_audio_tokens = config_.max_audio_tokens;

        SynthesizedCandidate candidate;
        const auto st = SynthesizeClauseLocked(normalized_text, prefix_tail, sampling, candidate);
        if (!st.ok) {
            out_candidates.clear();
            return st;
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

const std::string& VoiceEngine::ResolvedLlamaLibraryPath() const {
    return resolved_llama_lib_path_;
}

}  // namespace voicestudio::engine
