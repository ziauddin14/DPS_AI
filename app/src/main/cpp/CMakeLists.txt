# Project Falcon — DPS Android Client
# Native build: llama.cpp + the JNI bridge.
#
# Produces exactly one shared library, `libdps_llama.so`, which is what
# `LlamaCppBridge.System.loadLibrary("dps_llama")` resolves.
#
# llama.cpp is pinned at release b10246 (39eab74a05d3e68ac822b6dc6cd78c90cb985c19)
# and consumed as source from android/third_party/llama.cpp.

cmake_minimum_required(VERSION 3.22.1)

project(dps_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# llama.cpp source
# ---------------------------------------------------------------------------
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_CPP_DIR}\n"
        "Fetch them with:\n"
        "  git clone --depth 1 --branch b10246 https://github.com/ggml-org/llama.cpp.git "
        "android/third_party/llama.cpp")
endif()

# ---------------------------------------------------------------------------
# llama.cpp build configuration
#
# Everything not needed by an on-device inference library is switched off. This
# is not only about build time: llama.cpp's tools and examples pull in curl,
# threads of test harness code, and standalone binaries that have no meaning
# inside an APK and would fail to cross-compile for Android anyway.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)  # static-link into our .so

# These default to ${LLAMA_STANDALONE}, which is already false under
# add_subdirectory. Set explicitly anyway so the build does not silently change
# meaning if upstream alters that default.
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)

# The following default to ON regardless of LLAMA_STANDALONE, and each is
# actively harmful here. Verified against b10246's option() declarations —
# note that LLAMA_CURL, which older guides reference, no longer exists.
#
#   LLAMA_USE_PREBUILT_UI downloads a prebuilt web UI from a HuggingFace bucket
#   during configure. A build-time fetch from a third-party host is both a
#   network dependency an offline-first product should not have and an
#   unaudited input to our binary.
#
#   LLAMA_OPENSSL would require an OpenSSL toolchain cross-compiled for Android
#   to serve HTTPS we never use.
set(LLAMA_BUILD_UI         OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS       OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       OFF CACHE BOOL "" FORCE)

# GGML_NATIVE probes the *host* CPU for instruction sets. We are cross-compiling
# for arm64 from x86_64, so leaving it on emits host-specific flags that either
# fail to compile or produce a binary the device cannot run.
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

# The NDK ships no OpenMP runtime by default; linking would fail at load time
# on device rather than at build time here.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)

# CPU backend only for the MVP. Vulkan offload is device-fragmented on Android
# and a wrong answer there is a crash rather than a slowdown (ADR-002).
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL            OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# The JNI bridge
# ---------------------------------------------------------------------------
add_library(dps_llama SHARED
    llama_jni.cpp
)

target_link_libraries(dps_llama
    PRIVATE
        llama
        ggml
        android      # __android_log_print
        log
)

target_compile_options(dps_llama PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden          # export only the JNI entry points
)

# JNI entry points must remain visible despite -fvisibility=hidden. JNIEXPORT
# already carries the visibility attribute, so this is belt-and-braces for the
# symbols the JVM resolves by name at runtime.
set_target_properties(dps_llama PROPERTIES
    C_VISIBILITY_PRESET   hidden
    CXX_VISIBILITY_PRESET hidden
)

# Restrict the dynamic symbol table to the JNI entry points.
#
# -fvisibility=hidden only governs our own translation units. llama.cpp and ggml
# are statically linked and were compiled with default visibility, so without
# this script all ~7,700 of their symbols are re-exported from our library.
# See dps_llama.map for why that matters.
set(DPS_VERSION_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/dps_llama.map")
target_link_options(dps_llama PRIVATE
    "-Wl,--version-script=${DPS_VERSION_SCRIPT}"
)
# Relink when the export list changes.
set_target_properties(dps_llama PROPERTIES LINK_DEPENDS "${DPS_VERSION_SCRIPT}")

if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    target_compile_options(dps_llama PRIVATE -O3)
    # Drop unused sections; a statically linked llama.cpp is large and most of
    # it is unreachable from five JNI functions.
    target_link_options(dps_llama PRIVATE -Wl,--gc-sections -Wl,--strip-all)
endif()

message(STATUS "DPS: building dps_llama for ${ANDROID_ABI} (llama.cpp b10246)")
