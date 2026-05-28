cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni")

set(CMAKE_CXX_STANDARD 17)

# Path to the cloned whisper.cpp source tree (sibling of src/main/cpp).
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp)

if (NOT EXISTS ${WHISPER_LIB_DIR}/src/whisper.cpp)
    message(FATAL_ERROR "whisper.cpp source not found at ${WHISPER_LIB_DIR}. "
            "Clone https://github.com/ggerganov/whisper.cpp into asr/whisper/whisper.cpp.")
endif ()

# Build ggml as a sub-project so it brings in its own backend sources and
# defines (avoids having to enumerate every ggml-*.c file by hand).
add_subdirectory(${WHISPER_LIB_DIR}/ggml ggml-build EXCLUDE_FROM_ALL)

add_library(${PROJECT_NAME} SHARED
        native-lib.cpp
        ${WHISPER_LIB_DIR}/src/whisper.cpp
)

target_compile_definitions(${PROJECT_NAME} PUBLIC GGML_USE_CPU)
target_compile_definitions(${PROJECT_NAME} PRIVATE WHISPER_VERSION="1.x-subly")

target_include_directories(${PROJECT_NAME} PRIVATE
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(${PROJECT_NAME} PRIVATE -march=armv8.2-a+fp16)
endif ()

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(${PROJECT_NAME} PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(${PROJECT_NAME} PRIVATE -Wl,--gc-sections)
endif ()

find_library(log-lib log)
target_link_libraries(${PROJECT_NAME} ${log-lib} android ggml)
