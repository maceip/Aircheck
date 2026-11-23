cmake_minimum_required(VERSION 3.22.1)
project(voicestudio_core_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Optional compile-time llama.cpp integration when LLAMA_CPP_DIR is supplied.
set(LLAMA_CPP_DIR "" CACHE PATH "Optional path to llama.cpp checkout for direct linking")

add_library(voicestudio_engine_core STATIC
    voice_engine.cc
    dsp_mastering.cc
)

set_target_properties(voicestudio_engine_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(voicestudio_engine_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(voicestudio_engine_core PRIVATE
    -Wall
    -Wextra
)

if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(voicestudio_engine_core PRIVATE
        -march=armv9-a+simd+fp16
    )
endif()

if(LLAMA_CPP_DIR AND EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama_cpp" EXCLUDE_FROM_ALL)
    target_compile_definitions(voicestudio_engine_core PRIVATE VOICESTUDIO_HAS_LLAMA_CPP=1)
    target_link_libraries(voicestudio_engine_core PUBLIC llama)
endif()

if(UNIX AND NOT APPLE)
    target_link_libraries(voicestudio_engine_core PUBLIC dl)
endif()

if(ANDROID)
    find_library(log-lib log)
    find_library(android-lib android)

    add_library(voicestudio_jni SHARED
        jni_bridge.cc
    )

    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        target_compile_options(voicestudio_jni PRIVATE
            -march=armv9-a+simd+fp16
        )
    endif()

    target_link_libraries(voicestudio_jni
        PRIVATE
            voicestudio_engine_core
            ${log-lib}
            ${android-lib}
    )

    # Android 17 16 KB ELF LOAD segment alignment mandate
    target_link_options(voicestudio_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
else()
    # Host CTest Gate 1 verification suite (macOS / Linux)
    include(CTest)
    if(BUILD_TESTING)
        add_library(llama_lifecycle_probe SHARED
            tests/llama_lifecycle_probe.cc
        )
        target_compile_options(llama_lifecycle_probe PRIVATE -Wall -Wextra)

        add_executable(EngineParityTest
            tests/EngineParityTest.cc
        )
        target_compile_options(EngineParityTest PRIVATE -Wall -Wextra)
        target_link_libraries(EngineParityTest PRIVATE voicestudio_engine_core)
        target_compile_definitions(EngineParityTest PRIVATE
            VOICESTUDIO_LLAMA_PROBE_PATH="$<TARGET_FILE:llama_lifecycle_probe>"
        )
        add_dependencies(EngineParityTest llama_lifecycle_probe)

        add_test(NAME EngineParityTest COMMAND EngineParityTest)
    endif()
endif()
