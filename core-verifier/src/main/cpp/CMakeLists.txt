cmake_minimum_required(VERSION 3.22.1)
project(voicestudio_core_verifier LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(voicestudio_verifier_core STATIC
    verifier_gates.cc
)

set_target_properties(voicestudio_verifier_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(voicestudio_verifier_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(voicestudio_verifier_core PRIVATE
    -Wall
    -Wextra
)

if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(voicestudio_verifier_core PRIVATE
        -march=armv9-a+simd+fp16
    )
endif()

if(ANDROID)
    find_library(log-lib log)

    add_library(voicestudio_verifier SHARED
        verifier_jni.cc
    )

    target_compile_options(voicestudio_verifier PRIVATE
        -Wall
        -Wextra
    )

    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        target_compile_options(voicestudio_verifier PRIVATE
            -march=armv9-a+simd+fp16
        )
    endif()

    target_link_libraries(voicestudio_verifier
        PRIVATE
            voicestudio_verifier_core
            ${log-lib}
    )

    # Android 17 16 KB ELF LOAD segment alignment mandate
    target_link_options(voicestudio_verifier PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
else()
    include(CTest)
    if(BUILD_TESTING)
        add_executable(VerifierCoreTest
            tests/VerifierCoreTest.cc
        )
        target_link_libraries(VerifierCoreTest
            PRIVATE
                voicestudio_verifier_core
        )
        target_compile_options(VerifierCoreTest PRIVATE
            -Wall
            -Wextra
        )
        add_test(NAME VerifierCoreTest COMMAND VerifierCoreTest)
    endif()
endif()
