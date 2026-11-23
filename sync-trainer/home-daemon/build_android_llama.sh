#!/usr/bin/env bash
set -euo pipefail

# Build real llama.cpp shared libraries for:
#   1. Host macOS (libllama.dylib + libggml*.dylib + llama-cli)
#   2. Android arm64-v8a with 16 KB ELF page alignment (LOAD Align 0x4000)
#
# Uses an out-of-tree wrapper CMakeLists.txt inside each build directory so the
# upstream llama.cpp checkout is NEVER dirtied, while exporting BOTH upstream
# llama_set_adapters_lora AND C ABI compatibility shims expected by VoiceEngine:
#   - llama_set_adapter_lora
#   - llama_rm_adapter_lora
#   - llama_clear_adapter_lora
#   - llama_kv_cache_clear

usage() {
  cat <<'EOF' >&2
Usage:
  build_android_llama.sh [options]

Options:
  --target <all|host|android>     Build target(s) (default: all)
  --llama-cpp-dir <path>          Upstream llama.cpp checkout (default: /Users/mac/llama.cpp)
  --host-build-dir <path>         Host build directory (default: /Users/mac/Downloads/voicestudio-models/llama-build-host)
  --android-build-dir <path>      Android build directory (default: /Users/mac/Downloads/voicestudio-models/llama-build-android-arm64)
  --android-out-dir <path>        Android output .so dir (default: /Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a)
  --ndk-dir <path>                Android NDK root (default: /opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358)
  --cmake-bin <path>              CMake binary for Android build (default: auto-detect)
  --ninja-bin <path>              Ninja binary (default: auto-detect)
  --android-api <level>           Android API level (default: 26)
  --arm-arch <arch>               GGML_CPU_ARM_ARCH for Android arm64-v8a (default: armv8.2-a+dotprod+fp16)
  --jobs <N>                      Parallel build jobs (default: hw.ncpu)
  -h, --help                      Show this help
EOF
}

TARGET="all"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-/Users/mac/llama.cpp}"
HOST_BUILD_DIR="${HOST_BUILD_DIR:-/Users/mac/Downloads/voicestudio-models/llama-build-host}"
ANDROID_BUILD_DIR="${ANDROID_BUILD_DIR:-/Users/mac/Downloads/voicestudio-models/llama-build-android-arm64}"
ANDROID_OUT_DIR="${ANDROID_OUT_DIR:-/Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a}"
NDK_DIR="${NDK_DIR:-/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358}"
ANDROID_CMAKE_BIN="${ANDROID_CMAKE_BIN:-/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/cmake}"
NINJA_BIN="${NINJA_BIN:-}"
ANDROID_API="${ANDROID_API:-26}"
ARM_ARCH="${ARM_ARCH:-armv8.2-a+dotprod+fp16}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 8)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      TARGET="${2:-}"
      shift 2
      ;;
    --llama-cpp-dir)
      LLAMA_CPP_DIR="${2:-}"
      shift 2
      ;;
    --host-build-dir)
      HOST_BUILD_DIR="${2:-}"
      shift 2
      ;;
    --android-build-dir)
      ANDROID_BUILD_DIR="${2:-}"
      shift 2
      ;;
    --android-out-dir)
      ANDROID_OUT_DIR="${2:-}"
      shift 2
      ;;
    --ndk-dir)
      NDK_DIR="${2:-}"
      shift 2
      ;;
    --cmake-bin)
      ANDROID_CMAKE_BIN="${2:-}"
      shift 2
      ;;
    --ninja-bin)
      NINJA_BIN="${2:-}"
      shift 2
      ;;
    --android-api)
      ANDROID_API="${2:-}"
      shift 2
      ;;
    --arm-arch)
      ARM_ARCH="${2:-}"
      shift 2
      ;;
    --jobs)
      JOBS="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

case "$TARGET" in
  all|host|android) ;;
  *)
    echo "ERROR: --target must be one of: all, host, android (got: $TARGET)" >&2
    exit 2
    ;;
esac

if [[ ! -f "${LLAMA_CPP_DIR}/CMakeLists.txt" || ! -f "${LLAMA_CPP_DIR}/include/llama.h" ]]; then
  echo "ERROR: Invalid llama.cpp directory: ${LLAMA_CPP_DIR}" >&2
  exit 1
fi

if [[ -z "$NINJA_BIN" ]]; then
  if command -v ninja >/dev/null 2>&1; then
    NINJA_BIN="$(command -v ninja)"
  elif [[ -x "/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja" ]]; then
    NINJA_BIN="/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja"
  else
    echo "ERROR: ninja build tool not found" >&2
    exit 1
  fi
fi

HOST_CMAKE_BIN="$(command -v cmake || echo "$ANDROID_CMAKE_BIN")"
if [[ ! -x "$ANDROID_CMAKE_BIN" ]]; then
  ANDROID_CMAKE_BIN="$HOST_CMAKE_BIN"
fi

write_wrapper_project() {
  local wrapper_src_dir="$1"
  mkdir -p "$wrapper_src_dir"

  cat > "${wrapper_src_dir}/compat_lora_abi.cpp" <<'EOF'
#include "llama.h"

#include <cstddef>
#include <cstdint>

// Out-of-tree C ABI compatibility shims bridging llama.cpp b10686+
// (llama_set_adapters_lora / llama_memory_clear) with VoiceEngine's single-adapter
// runtime symbols without modifying the upstream llama.cpp source tree.
extern "C" {

LLAMA_API int32_t llama_set_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * adapter,
        float scale);
LLAMA_API int32_t llama_rm_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * adapter);
LLAMA_API void llama_clear_adapter_lora(struct llama_context * ctx);
LLAMA_API void llama_kv_cache_clear(struct llama_context * ctx);

LLAMA_API int32_t llama_set_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * adapter,
        float scale) {
    if (ctx == nullptr || adapter == nullptr) {
        return -1;
    }
    struct llama_adapter_lora * adapters[1] = { adapter };
    float scales[1] = { scale };
    return llama_set_adapters_lora(ctx, adapters, 1, scales);
}

LLAMA_API int32_t llama_rm_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * /* adapter */) {
    if (ctx == nullptr) {
        return -1;
    }
    return llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
}

LLAMA_API void llama_clear_adapter_lora(struct llama_context * ctx) {
    if (ctx == nullptr) {
        return;
    }
    llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
}

LLAMA_API void llama_kv_cache_clear(struct llama_context * ctx) {
    if (ctx == nullptr) {
        return;
    }
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }
}

}  // extern "C"
EOF

  cat > "${wrapper_src_dir}/llama_lora_verify.cpp" <<'EOF'
#include "llama.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#if defined(__APPLE__)
#include <mach/mach.h>
#endif

extern "C" {
LLAMA_API int32_t llama_set_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * adapter,
        float scale);
LLAMA_API int32_t llama_rm_adapter_lora(
        struct llama_context * ctx,
        struct llama_adapter_lora * adapter);
LLAMA_API void llama_clear_adapter_lora(struct llama_context * ctx);
LLAMA_API void llama_kv_cache_clear(struct llama_context * ctx);
}

namespace {

constexpr int32_t kSemanticStart = 151678;
constexpr int32_t kSemanticEnd = 155773;

double CurrentRssMb() {
#if defined(__APPLE__)
    mach_task_basic_info info {};
    mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO,
                  reinterpret_cast<task_info_t>(&info), &count) == KERN_SUCCESS) {
        return static_cast<double>(info.resident_size) / (1024.0 * 1024.0);
    }
#endif
    return 0.0;
}

bool EvalPromptLogits(
        llama_context * ctx,
        const std::vector<llama_token> & tokens,
        int32_t n_vocab,
        std::vector<float> & out_logits) {
    llama_kv_cache_clear(ctx);
    const int32_t n = static_cast<int32_t>(tokens.size());
    llama_batch batch = llama_batch_init(n, 0, 1);
    batch.n_tokens = n;
    for (int32_t i = 0; i < n; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n - 1) ? 1 : 0;
    }
    const int32_t rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        return false;
    }
    const float * logits = llama_get_logits_ith(ctx, n - 1);
    if (logits == nullptr) {
        return false;
    }
    out_logits.assign(logits, logits + n_vocab);
    return true;
}

double SemanticProb(const std::vector<float> & logits) {
    float max_l = -1e30f;
    for (float v : logits) {
        if (v > max_l) max_l = v;
    }
    double sum_all = 0.0;
    double sum_sem = 0.0;
    for (size_t i = 0; i < logits.size(); ++i) {
        const double e = std::exp(static_cast<double>(logits[i] - max_l));
        sum_all += e;
        if (static_cast<int32_t>(i) >= kSemanticStart &&
            static_cast<int32_t>(i) <= kSemanticEnd) {
            sum_sem += e;
        }
    }
    return (sum_all > 0.0) ? (sum_sem / sum_all) : 0.0;
}

std::vector<std::pair<int32_t, float>> TopK(const std::vector<float> & logits, size_t k) {
    std::vector<std::pair<float, int32_t>> pairs;
    pairs.reserve(logits.size());
    for (size_t i = 0; i < logits.size(); ++i) {
        pairs.emplace_back(logits[i], static_cast<int32_t>(i));
    }
    const size_t n = std::min(k, pairs.size());
    std::partial_sort(
        pairs.begin(),
        pairs.begin() + static_cast<std::ptrdiff_t>(n),
        pairs.end(),
        [](const auto & a, const auto & b) { return a.first > b.first; });
    std::vector<std::pair<int32_t, float>> res;
    res.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        res.emplace_back(pairs[i].second, pairs[i].first);
    }
    return res;
}

}  // namespace

int main(int argc, char ** argv) {
    std::string model_path;
    std::string lora_path;
    std::string prompt =
        "<|im_start|>system\nconvert the provided text to speech<|im_end|>\n"
        "<|im_start|>user\n<|speaker:0|>Hello, this message is for Matthew Swish and Ranya.<|im_end|>\n"
        "<|im_start|>assistant\n<|voice|>";
    float scale = 1.0f;
    int hotswap_iters = 10;
    int n_gpu_layers = 0;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--model") == 0 && i + 1 < argc) {
            model_path = argv[++i];
        } else if (std::strcmp(argv[i], "--lora") == 0 && i + 1 < argc) {
            lora_path = argv[++i];
        } else if (std::strcmp(argv[i], "--prompt") == 0 && i + 1 < argc) {
            prompt = argv[++i];
        } else if (std::strcmp(argv[i], "--scale") == 0 && i + 1 < argc) {
            scale = std::stof(argv[++i]);
        } else if (std::strcmp(argv[i], "--hotswap-iters") == 0 && i + 1 < argc) {
            hotswap_iters = std::stoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--n-gpu-layers") == 0 && i + 1 < argc) {
            n_gpu_layers = std::stoi(argv[++i]);
        }
    }

    if (model_path.empty()) {
        std::fprintf(stderr, "Usage: llama-lora-verify --model <base.gguf> [--lora <adapter.gguf>] [--scale 1.0] [--hotswap-iters 10] [--n-gpu-layers 0]\n");
        return 2;
    }

    llama_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        std::fprintf(stderr, "ERROR: llama_model_load_from_file failed\n");
        llama_backend_free();
        return 1;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_batch = 512;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        std::fprintf(stderr, "ERROR: llama_init_from_model failed\n");
        llama_model_free(model);
        llama_backend_free();
        return 1;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token> tokens(prompt.size() + 32);
    const int32_t n_tok = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (n_tok <= 0) {
        std::fprintf(stderr, "ERROR: llama_tokenize failed\n");
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        return 1;
    }
    tokens.resize(static_cast<size_t>(n_tok));

    std::vector<float> base_logits;
    if (!EvalPromptLogits(ctx, tokens, n_vocab, base_logits)) {
        std::fprintf(stderr, "ERROR: base llama_decode failed\n");
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        return 1;
    }
    const double base_sem_prob = SemanticProb(base_logits);
    const auto base_top5 = TopK(base_logits, 5);

    bool lora_loaded = false;
    int32_t lora_set_rc = -1;
    int32_t lora_rm_rc = -1;
    double lora_sem_prob = 0.0;
    double post_detach_sem_prob = 0.0;
    double logit_max_abs_diff = 0.0;
    double logit_l2_diff = 0.0;
    double sem_max_abs_diff = 0.0;
    double sem_l2_diff = 0.0;
    double post_detach_max_abs_diff = 0.0;
    int hotswap_errors = 0;
    double rss_before_mb = 0.0;
    double rss_after_mb = 0.0;
    std::vector<std::pair<int32_t, float>> lora_top5;

    if (!lora_path.empty()) {
        llama_adapter_lora * lora = llama_adapter_lora_init(model, lora_path.c_str());
        if (lora == nullptr) {
            std::fprintf(stderr, "ERROR: llama_adapter_lora_init failed for %s\n", lora_path.c_str());
            llama_free(ctx);
            llama_model_free(model);
            llama_backend_free();
            return 1;
        }
        lora_loaded = true;

        lora_set_rc = llama_set_adapter_lora(ctx, lora, scale);
        if (lora_set_rc != 0) {
            std::fprintf(stderr, "ERROR: llama_set_adapter_lora failed rc=%d\n", lora_set_rc);
            llama_adapter_lora_free(lora);
            llama_free(ctx);
            llama_model_free(model);
            llama_backend_free();
            return 1;
        }

        std::vector<float> lora_logits;
        if (!EvalPromptLogits(ctx, tokens, n_vocab, lora_logits)) {
            std::fprintf(stderr, "ERROR: LoRA llama_decode failed\n");
            llama_adapter_lora_free(lora);
            llama_free(ctx);
            llama_model_free(model);
            llama_backend_free();
            return 1;
        }
        lora_sem_prob = SemanticProb(lora_logits);
        lora_top5 = TopK(lora_logits, 5);

        double sum_sq = 0.0;
        double sem_sum_sq = 0.0;
        for (int32_t i = 0; i < n_vocab; ++i) {
            const double d = std::fabs(static_cast<double>(lora_logits[i]) - static_cast<double>(base_logits[i]));
            if (d > logit_max_abs_diff) logit_max_abs_diff = d;
            sum_sq += d * d;
            if (i >= kSemanticStart && i <= kSemanticEnd) {
                if (d > sem_max_abs_diff) sem_max_abs_diff = d;
                sem_sum_sq += d * d;
            }
        }
        logit_l2_diff = std::sqrt(sum_sq);
        sem_l2_diff = std::sqrt(sem_sum_sq);

        lora_rm_rc = llama_rm_adapter_lora(ctx, lora);
        llama_adapter_lora_free(lora);

        std::vector<float> detach_logits;
        if (!EvalPromptLogits(ctx, tokens, n_vocab, detach_logits)) {
            std::fprintf(stderr, "ERROR: post-detach llama_decode failed\n");
            llama_free(ctx);
            llama_model_free(model);
            llama_backend_free();
            return 1;
        }
        post_detach_sem_prob = SemanticProb(detach_logits);
        for (int32_t i = 0; i < n_vocab; ++i) {
            const double d = std::fabs(static_cast<double>(detach_logits[i]) - static_cast<double>(base_logits[i]));
            if (d > post_detach_max_abs_diff) post_detach_max_abs_diff = d;
        }

        // Warm one cycle before measuring RSS delta across repeated hot-swaps.
        {
            llama_adapter_lora * warm = llama_adapter_lora_init(model, lora_path.c_str());
            if (warm == nullptr || llama_set_adapter_lora(ctx, warm, scale) != 0) {
                hotswap_errors++;
            } else {
                std::vector<float> tmp;
                if (!EvalPromptLogits(ctx, tokens, n_vocab, tmp)) hotswap_errors++;
                llama_rm_adapter_lora(ctx, warm);
                llama_adapter_lora_free(warm);
            }
        }
        rss_before_mb = CurrentRssMb();
        for (int iter = 0; iter < hotswap_iters; ++iter) {
            llama_adapter_lora * next_lora = llama_adapter_lora_init(model, lora_path.c_str());
            if (next_lora == nullptr) {
                hotswap_errors++;
                continue;
            }
            if (llama_set_adapter_lora(ctx, next_lora, scale) != 0) {
                hotswap_errors++;
                llama_adapter_lora_free(next_lora);
                continue;
            }
            std::vector<float> tmp;
            if (!EvalPromptLogits(ctx, tokens, n_vocab, tmp)) {
                hotswap_errors++;
            }
            if (llama_rm_adapter_lora(ctx, next_lora) != 0) {
                hotswap_errors++;
            }
            llama_adapter_lora_free(next_lora);
        }
        rss_after_mb = CurrentRssMb();
    }

    std::printf("{\n");
    std::printf("  \"base_model_loaded\": true,\n");
    std::printf("  \"vocab_size\": %d,\n", n_vocab);
    std::printf("  \"prompt_tokens\": %d,\n", n_tok);
    std::printf("  \"base_sem_prob\": %.6f,\n", base_sem_prob);
    std::printf("  \"lora_loaded\": %s,\n", lora_loaded ? "true" : "false");
    std::printf("  \"lora_set_rc\": %d,\n", lora_set_rc);
    std::printf("  \"lora_rm_rc\": %d,\n", lora_rm_rc);
    std::printf("  \"lora_sem_prob\": %.6f,\n", lora_sem_prob);
    std::printf("  \"post_detach_sem_prob\": %.6f,\n", post_detach_sem_prob);
    std::printf("  \"logit_max_abs_diff\": %.6f,\n", logit_max_abs_diff);
    std::printf("  \"logit_l2_diff\": %.6f,\n", logit_l2_diff);
    std::printf("  \"semantic_logit_max_abs_diff\": %.6f,\n", sem_max_abs_diff);
    std::printf("  \"semantic_logit_l2_diff\": %.6f,\n", sem_l2_diff);
    std::printf("  \"post_detach_max_abs_diff\": %.6f,\n", post_detach_max_abs_diff);
    std::printf("  \"hotswap_iterations\": %d,\n", lora_loaded ? hotswap_iters : 0);
    std::printf("  \"hotswap_errors\": %d,\n", hotswap_errors);
    std::printf("  \"rss_before_mb\": %.2f,\n", rss_before_mb);
    std::printf("  \"rss_after_mb\": %.2f,\n", rss_after_mb);
    std::printf("  \"rss_delta_mb\": %.2f,\n", rss_after_mb - rss_before_mb);
    std::printf("  \"base_top5_tokens\": [%d, %d, %d, %d, %d],\n",
                base_top5[0].first, base_top5[1].first, base_top5[2].first, base_top5[3].first, base_top5[4].first);
    if (lora_top5.size() == 5) {
        std::printf("  \"lora_top5_tokens\": [%d, %d, %d, %d, %d]\n",
                    lora_top5[0].first, lora_top5[1].first, lora_top5[2].first, lora_top5[3].first, lora_top5[4].first);
    } else {
        std::printf("  \"lora_top5_tokens\": []\n");
    }
    std::printf("}\n");

    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return (hotswap_errors == 0) ? 0 : 1;
}
EOF

  cat > "${wrapper_src_dir}/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.22)
project(voicestudio_llama_wrapper C CXX)

set(VOICESTUDIO_LLAMA_CPP_DIR "" CACHE PATH "Path to upstream llama.cpp checkout")
if (NOT EXISTS "${VOICESTUDIO_LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "VOICESTUDIO_LLAMA_CPP_DIR is invalid: ${VOICESTUDIO_LLAMA_CPP_DIR}")
endif()

add_subdirectory("${VOICESTUDIO_LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama_cpp_sub")

if (NOT TARGET llama)
    message(FATAL_ERROR "Expected llama target from upstream llama.cpp")
endif()

target_sources(llama PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/compat_lora_abi.cpp")

if (NOT ANDROID)
    add_executable(llama-lora-verify "${CMAKE_CURRENT_SOURCE_DIR}/llama_lora_verify.cpp")
    target_link_libraries(llama-lora-verify PRIVATE llama)
    target_compile_features(llama-lora-verify PRIVATE cxx_std_17)
    set_target_properties(llama-lora-verify PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    )
endif()

if (ANDROID)
    # Android Bionic linker requires unversioned *.so filenames and DT_SONAME/DT_NEEDED.
    foreach(_target IN ITEMS llama ggml ggml-base ggml-cpu)
        if (TARGET ${_target})
            set_target_properties(${_target} PROPERTIES
                VERSION ""
                SOVERSION ""
            )
            target_link_options(${_target} PRIVATE
                "-Wl,-z,max-page-size=16384"
                "-Wl,-z,common-page-size=16384"
            )
        endif()
    endforeach()
endif()
EOF
}

build_host() {
  echo "=== Building host macOS llama.cpp into ${HOST_BUILD_DIR} ==="
  local wrapper_src="${HOST_BUILD_DIR}/_wrapper_src"
  write_wrapper_project "$wrapper_src"

  local c_compiler=""
  local cxx_compiler=""
  if [[ "$(uname -s)" == "Darwin" && -x "/usr/bin/clang" && -x "/usr/bin/clang++" ]]; then
    c_compiler="/usr/bin/clang"
    cxx_compiler="/usr/bin/clang++"
  fi

  local cmake_args=(
    -S "$wrapper_src"
    -B "$HOST_BUILD_DIR"
    -G Ninja
    "-DCMAKE_MAKE_PROGRAM=${NINJA_BIN}"
    "-DVOICESTUDIO_LLAMA_CPP_DIR=${LLAMA_CPP_DIR}"
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=ON
    -DGGML_METAL=ON
    -DGGML_OPENMP=OFF
    -DGGML_BACKEND_DL=OFF
    -DLLAMA_OPENSSL=OFF
    -DLLAMA_BUILD_COMMON=ON
    -DLLAMA_BUILD_TOOLS=ON
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=ON
    -DLLAMA_BUILD_APP=OFF
  )
  if [[ -n "$c_compiler" ]]; then
    cmake_args+=(
      "-DCMAKE_C_COMPILER=${c_compiler}"
      "-DCMAKE_CXX_COMPILER=${cxx_compiler}"
    )
  fi

  "$HOST_CMAKE_BIN" "${cmake_args[@]}"
  "$HOST_CMAKE_BIN" --build "$HOST_BUILD_DIR" --target llama llama-cli llama-lora-verify -j "$JOBS"

  local libllama_path=""
  for candidate in \
    "${HOST_BUILD_DIR}/bin/libllama.dylib" \
    "${HOST_BUILD_DIR}/bin/libllama.so" \
    "${HOST_BUILD_DIR}/llama_cpp_sub/src/libllama.dylib" \
    "${HOST_BUILD_DIR}/llama_cpp_sub/src/libllama.so"; do
    if [[ -e "$candidate" ]]; then
      libllama_path="$candidate"
      break
    fi
  done
  if [[ -z "$libllama_path" || ! -e "$libllama_path" ]]; then
    echo "ERROR: Host libllama not found under ${HOST_BUILD_DIR}" >&2
    exit 1
  fi

  echo "Host libllama: ${libllama_path}"
  local required_syms=(
    llama_adapter_lora_init
    llama_set_adapter_lora
    llama_set_adapters_lora
    llama_rm_adapter_lora
    llama_clear_adapter_lora
    llama_adapter_lora_free
    llama_kv_cache_clear
  )
  local exported
  exported="$(nm -gU "$libllama_path")"
  for sym in "${required_syms[@]}"; do
    if ! grep -Eq "(^|[[:space:]])_?${sym}$" <<< "$exported"; then
      echo "ERROR: Host libllama missing exported symbol: ${sym}" >&2
      exit 1
    fi
  done
  echo "Host libllama symbol verification PASSED (${#required_syms[@]} symbols)."
}

build_android() {
  echo "=== Cross-compiling Android arm64-v8a (16 KB aligned) llama.cpp ==="
  local toolchain_file="${NDK_DIR}/build/cmake/android.toolchain.cmake"
  if [[ ! -f "$toolchain_file" ]]; then
    echo "ERROR: Android NDK toolchain not found: ${toolchain_file}" >&2
    exit 1
  fi

  local llvm_bin_dir=""
  for host_tag in darwin-arm64 darwin-x86_64 linux-x86_64; do
    if [[ -d "${NDK_DIR}/toolchains/llvm/prebuilt/${host_tag}/bin" ]]; then
      llvm_bin_dir="${NDK_DIR}/toolchains/llvm/prebuilt/${host_tag}/bin"
      break
    fi
  done
  if [[ -z "$llvm_bin_dir" ]]; then
    echo "ERROR: Could not find NDK LLVM prebuilt bin directory under ${NDK_DIR}" >&2
    exit 1
  fi
  local readelf_bin="${llvm_bin_dir}/llvm-readelf"
  local nm_bin="${llvm_bin_dir}/llvm-nm"

  local wrapper_src="${ANDROID_BUILD_DIR}/_wrapper_src"
  write_wrapper_project "$wrapper_src"

  "$ANDROID_CMAKE_BIN" \
    -S "$wrapper_src" \
    -B "$ANDROID_BUILD_DIR" \
    -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=${NINJA_BIN}" \
    "-DCMAKE_TOOLCHAIN_FILE=${toolchain_file}" \
    "-DVOICESTUDIO_LLAMA_CPP_DIR=${LLAMA_CPP_DIR}" \
    -DANDROID_ABI=arm64-v8a \
    "-DANDROID_PLATFORM=android-${ANDROID_API}" \
    -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_NATIVE=OFF \
    "-DGGML_CPU_ARM_ARCH=${ARM_ARCH}" \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

  "$ANDROID_CMAKE_BIN" --build "$ANDROID_BUILD_DIR" --target llama -j "$JOBS"

  mkdir -p "$ANDROID_OUT_DIR"
  rm -f "${ANDROID_OUT_DIR}"/*.so

  local copied=0
  while IFS= read -r -d '' so_file; do
    cp -fL "$so_file" "${ANDROID_OUT_DIR}/$(basename "$so_file")"
    copied=$((copied + 1))
  done < <(find "${ANDROID_BUILD_DIR}" -type f \( -name 'libllama.so' -o -name 'libggml*.so' \) -print0)

  if [[ "$copied" -eq 0 ]]; then
    echo "ERROR: No Android .so outputs found under ${ANDROID_BUILD_DIR}" >&2
    exit 1
  fi

  echo "Verifying 16 KB ELF LOAD alignment (0x4000) and unversioned SONAME/NEEDED on ${copied} Android .so files..."
  for so_path in "${ANDROID_OUT_DIR}"/*.so; do
    local phdrs
    phdrs="$("$readelf_bin" -lW "$so_path")"
    local load_lines
    load_lines="$(awk '$1 == "LOAD" { print $0 }' <<< "$phdrs")"
    if [[ -z "$load_lines" ]]; then
      echo "ERROR: No LOAD segments found in ${so_path}" >&2
      exit 1
    fi
    while IFS= read -r line; do
      local align
      align="$(awk '{ print $NF }' <<< "$line")"
      if [[ "$align" != "0x4000" && "$align" != "0x10000" ]]; then
        echo "ERROR: ${so_path} has non-16KB LOAD alignment '${align}': ${line}" >&2
        exit 1
      fi
    done <<< "$load_lines"

    local dyn
    dyn="$("$readelf_bin" -dW "$so_path")"
    if grep -Eq '\.so\.[0-9]+' <<< "$dyn"; then
      echo "ERROR: ${so_path} contains versioned SONAME/NEEDED entries:" >&2
      grep -E '\.so\.[0-9]+' <<< "$dyn" >&2
      exit 1
    fi
    local sz
    sz="$(wc -c < "$so_path" | tr -d ' ')"
    echo "  OK [16KB Align 0x4000]: $(basename "$so_path") (${sz} bytes)"
  done

  local android_libllama="${ANDROID_OUT_DIR}/libllama.so"
  if [[ ! -f "$android_libllama" ]]; then
    echo "ERROR: Missing ${android_libllama}" >&2
    exit 1
  fi

  local required_syms=(
    llama_adapter_lora_init
    llama_set_adapter_lora
    llama_set_adapters_lora
    llama_rm_adapter_lora
    llama_clear_adapter_lora
    llama_adapter_lora_free
    llama_kv_cache_clear
  )
  local dyn_syms
  dyn_syms="$("$nm_bin" -D --defined-only "$android_libllama")"
  for sym in "${required_syms[@]}"; do
    if ! grep -Eq "(^|[[:space:]])${sym}$" <<< "$dyn_syms"; then
      echo "ERROR: Android libllama.so missing exported symbol: ${sym}" >&2
      exit 1
    fi
  done
  echo "Android libllama.so symbol verification PASSED (${#required_syms[@]} symbols)."
}

if [[ "$TARGET" == "all" || "$TARGET" == "host" ]]; then
  build_host
fi
if [[ "$TARGET" == "all" || "$TARGET" == "android" ]]; then
  build_android
fi

echo "=== build_android_llama.sh COMPLETE (target=${TARGET}) ==="
