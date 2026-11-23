cmake_minimum_required(VERSION 3.22.1)
project(voicestudio_audio_edl LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(voicestudio_edl_core STATIC
    edl_player.cc
)

target_include_directories(voicestudio_edl_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(voicestudio_edl_core PRIVATE
    -Wall
    -Wextra
)

if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(voicestudio_edl_core PRIVATE
        -march=armv9-a+simd+fp16
    )
endif()

if(ANDROID)
    find_package(oboe REQUIRED CONFIG)
    find_library(log-lib log)

    target_link_libraries(voicestudio_edl_core
        PUBLIC
            oboe::oboe
            ${log-lib}
    )

    add_library(voicestudio_edl SHARED
        edl_player.cc
    )

    target_include_directories(voicestudio_edl
        PUBLIC
            ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        target_compile_options(voicestudio_edl PRIVATE
            -march=armv9-a+simd+fp16
        )
    endif()

    target_link_libraries(voicestudio_edl
        PRIVATE
            oboe::oboe
            ${log-lib}
    )

    # Android 17 16 KB ELF LOAD segment alignment mandate
    target_link_options(voicestudio_edl PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
endif()
