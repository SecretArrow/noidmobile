cmake_minimum_required(VERSION 3.22.1)

project(noidmobile LANGUAGES CXX)

# ---------------------------------------------------------------------------
# libnoidmobile_pow.so — built by cargo-ndk in CI and placed under
# app/src/main/jniLibs/<abi>/ by the workflow before Gradle runs.
#
# We do NOT build the Rust crate from CMake; we link the prebuilt .so
# instead. This keeps the Gradle build hermetic and lets us cache the
# Rust artifact separately.
# ---------------------------------------------------------------------------

# IMPORTANT: OpenCL GPU path is DISABLED by default to prevent crashes.
# The OpenCL function signatures in opencl_miner.cpp are a minimal subset
# and may not match every vendor's libOpenCL.so — calling them on a device
# with an incompatible OpenCL could segfault the app.
#
# The CPU path through libnoidmobile_pow.so is always correct (it wraps the
# upstream noid_poseidon2b Rust crate) and is what the upstream miner uses.
#
# To enable OpenCL for experimentation, pass -DNOID_ENABLE_OPENCL=ON to
# CMake. Production builds should leave this OFF until a real GPU
# Poseidon2b kernel is implemented and tested across Adreno + Mali.
option(NOID_ENABLE_OPENCL "Enable OpenCL GPU path (experimental, may crash)" OFF)

if(NOID_ENABLE_OPENCL)
    set(OPENCL_CANDIDATES
        OpenCL
        libOpenCL.so
        libOpenCL-pixel.so
        libGLES_mali.so
    )

    set(OPENCL_FOUND FALSE)
    foreach(candidate ${OPENCL_CANDIDATES})
        find_library(OPENCL_LIB ${candidate})
        if(OPENCL_LIB)
            message(STATUS "noidmobile: OpenCL found as ${candidate} -> ${OPENCL_LIB}")
            set(OPENCL_FOUND TRUE)
            break()
        endif()
    endforeach()

    if(NOT OPENCL_FOUND)
        message(WARNING "noidmobile: OpenCL not found — falling back to CPU-only Poseidon2b")
    endif()
else()
    message(STATUS "noidmobile: OpenCL disabled by default (NOID_ENABLE_OPENCL=OFF) — CPU-only Poseidon2b")
endif()

add_library(noidmobile_pow SHARED IMPORTED)
set_target_properties(noidmobile_pow PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnoidmobile_pow.so)

add_library(noid_jni SHARED
    noid_jni.cpp
    opencl_miner.cpp
)

target_include_directories(noid_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)

if(NOID_ENABLE_OPENCL AND OPENCL_FOUND)
    target_compile_definitions(noid_jni PRIVATE NOID_HAS_OPENCL=1)
    target_link_libraries(noid_jni PRIVATE ${OPENCL_LIB})
else()
    target_compile_definitions(noid_jni PRIVATE NOID_HAS_OPENCL=0)
endif()

target_link_libraries(noid_jni PRIVATE
    noidmobile_pow
    log
    android
)

# Hide all symbols except the JNI entry points.
target_compile_options(noid_jni PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden -fvisibility-inlines-hidden
)
