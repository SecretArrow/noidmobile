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

# OpenCL: most Android devices ship libOpenCL.so under /vendor/lib(64).
# Some Qualcomm devices expose libOpenCL-pixel.so or libGLES_mali.so.
# Try a list of known names; if none link, define NO_OPENCL and the C++
# falls back to the CPU batch hash from libnoidmobile_pow.so.
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

if(OPENCL_FOUND)
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
