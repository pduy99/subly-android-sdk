cmake_minimum_required(VERSION 3.22.1)
project("whisper_android")

set(CMAKE_CXX_STANDARD 17)

# 1. Include paths for compiling the whisper_jni target
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
        ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src
)

# 2. Build the 'ggml' target via its official subdirectory.
add_subdirectory(ggml)

# 3. Build your Kotlin JNI bridge and the core whisper logic.
add_library(
        whisper_jni
        SHARED
        whisper-jni.cpp
        whisper.cpp
)

# 4. Apply required macro definitions
target_compile_definitions(whisper_jni PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="unknown")

# 5. HARDWARE ACCELERATION INJECTION
# This explicitly forces NEON and FP16 on both your library AND the underlying ggml library.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    # Apply to your JNI code
    target_compile_options(whisper_jni PRIVATE -march=armv8.2-a+fp16)

    # CRITICAL FIX: Force hardware acceleration into the separately compiled ggml targets!
    if (TARGET ggml)
        target_compile_options(ggml PRIVATE -march=armv8.2-a+fp16)
        target_compile_definitions(ggml PRIVATE GGML_USE_NEON)
    endif()
endif ()

# 6. Heavy optimizations for non-debug/Release builds
if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    set(RELEASE_OPTS
            -O3
            -fvisibility=hidden
            -fvisibility-inlines-hidden
            -ffunction-sections
            -fdata-sections
    )
    target_compile_options(whisper_jni PRIVATE ${RELEASE_OPTS})
    if (TARGET ggml)
        target_compile_options(ggml PRIVATE ${RELEASE_OPTS})
    endif()

    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)
endif ()

# 7. Find Android Logging library
find_library(log-lib log)

# 8. Link everything together
target_link_libraries(
        whisper_jni
        ggml
        ${log-lib}
)