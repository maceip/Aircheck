cmake_minimum_required(VERSION 3.22.1)
project(voicestudio_core_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

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
endif()
