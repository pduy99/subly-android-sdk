# Subly SDK native AI core.
#
# Builds libwhisper_jni.so. By default this is a stub that reports
# "unavailable" so the Kotlin layer falls back cleanly (CI builds, devices
# without the model dropped in). Drop whisper.cpp under cpp/whisper.cpp/ and
# reconfigure with -DSUBLY_HAS_WHISPER=ON to build the real engine.

cmake_minimum_required(VERSION 3.22.1)
project(subly_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SUBLY_HAS_WHISPER "Build against a vendored whisper.cpp checkout" OFF)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_compile_options(whisper_jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -O3)

target_link_options(whisper_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL)

find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE ${log-lib})

if(SUBLY_HAS_WHISPER)
    if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt")
        message(FATAL_ERROR
            "SUBLY_HAS_WHISPER=ON but cpp/whisper.cpp/ is missing. "
            "Vendor https://github.com/ggerganov/whisper.cpp at a pinned tag.")
    endif()
    set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory(whisper.cpp)
    target_compile_definitions(whisper_jni PRIVATE SUBLY_HAS_WHISPER=1)
    target_link_libraries(whisper_jni PRIVATE whisper)
endif()
