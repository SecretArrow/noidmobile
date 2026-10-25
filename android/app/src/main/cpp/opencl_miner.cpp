// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// OpenCL batch-hash loader. Tries to create a context on the GPU device
// (Adreno / Mali) and run the kernel in opencl_miner.cl over a batch of
// nonces. If OpenCL is unavailable OR the kernel returns a sentinel that
// signals "not implemented", we transparently fall back to the CPU
// batch hash exposed by libnoidmobile_pow.so.
//
// IMPORTANT: A production-grade GPU Poseidon2b kernel is future work.
// The kernel shipped here is a STUB — it always returns the magic
// sentinel 0xN0_1D which the C++ wrapper detects, then routes the call
// to the CPU. The CPU path is the default and always works. NEVER crash
// if OpenCL is unavailable.

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "noid_opencl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if NOID_HAS_OPENCL

// We don't ship a system OpenCL header here; instead we declare the
// minimum subset of the API we actually call. Linking against
// libOpenCL.so is handled by CMakeLists.txt.
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef void* cl_context;
typedef void* cl_command_queue;
typedef void* cl_program;
typedef void* cl_kernel;
typedef void* cl_mem;
typedef int cl_int;
typedef unsigned int cl_uint;
typedef unsigned long cl_mem_flags;
typedef unsigned long cl_context_properties;

// CL_CALLBACK is normally defined by the OpenCL headers; we provide a
// minimal stand-in since we declare the API surface ourselves.
#ifndef CL_CALLBACK
#define CL_CALLBACK
#endif

constexpr cl_int CL_SUCCESS = 0;
constexpr cl_mem_flags CL_MEM_READ_ONLY = 1 << 2;
constexpr cl_mem_flags CL_MEM_WRITE_ONLY = 1 << 1;
constexpr cl_mem_flags CL_MEM_COPY_HOST_PTR = 1 << 0;

extern "C" {
cl_int clGetPlatformIDs(cl_uint, cl_platform_id*, cl_uint*);
cl_int clGetDeviceIDs(cl_platform_id, cl_uint, cl_uint, cl_device_id*, cl_uint*);
cl_context clCreateContext(const cl_context_properties*, cl_uint, const cl_device_id*,
                           void (CL_CALLBACK*)(const char*, const void*, size_t, void*),
                           void*, cl_int*);
cl_command_queue clCreateCommandQueue(cl_context, cl_device_id, cl_int, cl_int*);
cl_program clCreateProgramWithSource(cl_context, cl_uint, const char**, const size_t*, cl_int*);
cl_int clBuildProgram(cl_program, cl_uint, const cl_device_id*, const char*,
                      void (CL_CALLBACK*)(cl_program, void*), void*);
cl_kernel clCreateKernel(cl_program, const char*, cl_int*);
cl_mem clCreateBuffer(cl_context, cl_mem_flags, size_t, void*, cl_int*);
cl_int clSetKernelArg(cl_kernel, cl_uint, size_t, const void*);
cl_int clEnqueueNDRangeKernel(cl_command_queue, cl_kernel, cl_uint, const size_t*,
                              const size_t*, const size_t*, cl_uint, const void*, void*);
cl_int clEnqueueReadBuffer(cl_command_queue, cl_mem, cl_int, size_t, size_t, void*,
                           cl_uint, const void*, void*);
cl_int clFinish(cl_command_queue);
cl_int clReleaseMemObject(cl_mem);
cl_int clReleaseKernel(cl_kernel);
cl_int clReleaseProgram(cl_program);
cl_int clReleaseCommandQueue(cl_command_queue);
cl_int clReleaseContext(cl_context);
}

namespace {

bool g_opencl_initialized = false;
bool g_opencl_usable = false;
cl_context g_ctx = nullptr;
cl_command_queue g_queue = nullptr;
cl_device_id g_device = nullptr;
cl_program g_program = nullptr;
cl_kernel g_kernel = nullptr;

// Sentinel the stub kernel writes to mark "not implemented". The C++
// wrapper checks for it and falls back to CPU.
constexpr uint8_t NOID_STUB_SENTINEL[32] = {
    0x4E, 0x30, 0x49, 0x44, 0x5F, 0x53, 0x54, 0x55,
    0x42, 0x5F, 0x4E, 0x4F, 0x54, 0x49, 0x4D, 0x50,
    0x4C, 0x4D, 0x45, 0x4E, 0x54, 0x45, 0x44, 0x21,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

const char* kKernelSource = R"CLC(
// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// STUB OpenCL kernel for the Parano1d Poseidon2b PoW batch hash.
//
// A production GPU Poseidon2b permutation over GF(2^128) requires a
// custom field-arithmetic implementation that fits the Adreno / Mali
// vector width and ALU budget. That is a substantial body of work and
// is intentionally out of scope for the v0.1 release.
//
// To keep the integration shape correct, this kernel writes a known
// sentinel into every output digest. The C++ host wrapper detects the
// sentinel and falls back to the CPU path in libnoidmobile_pow.so.
//
// Future work: replace the body of `noid_poseidon2b_batch_stub` with a
// real Poseidon2b implementation. The kernel signature should remain
// stable so the host side does not need to change.

__kernel void noid_poseidon2b_batch_stub(
    __global const uchar* pow_fields,   // 256 bytes (16 x 16-byte LE fields)
    ulong                  nonce_start,
    uint                   nonce_count,
    __global uchar*        out)          // nonce_count * 32 bytes
{
    size_t i = get_global_id(0);
    if (i >= nonce_count) return;
    size_t off = i * 32ul;
    // Sentinel: "N0ID_STUB_NOTIMPLEMENTED!" + zeros
    out[off +  0] = 0x4E; out[off +  1] = 0x30; out[off +  2] = 0x49; out[off +  3] = 0x44;
    out[off +  4] = 0x5F; out[off +  5] = 0x53; out[off +  6] = 0x54; out[off +  7] = 0x55;
    out[off +  8] = 0x42; out[off +  9] = 0x5F; out[off + 10] = 0x4E; out[off + 11] = 0x4F;
    out[off + 12] = 0x54; out[off + 13] = 0x49; out[off + 14] = 0x4D; out[off + 15] = 0x50;
    out[off + 16] = 0x4C; out[off + 17] = 0x4D; out[off + 18] = 0x45; out[off + 19] = 0x4E;
    out[off + 20] = 0x54; out[off + 21] = 0x45; out[off + 22] = 0x44; out[off + 23] = 0x21;
    out[off + 24] = 0x00; out[off + 25] = 0x00; out[off + 26] = 0x00; out[off + 27] = 0x00;
    out[off + 28] = 0x00; out[off + 29] = 0x00; out[off + 30] = 0x00; out[off + 31] = 0x00;
}
)CLC";

bool init_opencl_once() {
    if (g_opencl_initialized) return g_opencl_usable;
    g_opencl_initialized = true;

    cl_platform_id platform = nullptr;
    cl_uint num_platforms = 0;
    if (clGetPlatformIDs(1, &platform, &num_platforms) != CL_SUCCESS || num_platforms == 0) {
        LOGW("clGetPlatformIDs failed — OpenCL not available");
        return false;
    }

    cl_int err = 0;
    err = clGetDeviceIDs(platform, 4 /*CL_DEVICE_TYPE_GPU*/, 1, &g_device, nullptr);
    if (err != CL_SUCCESS || !g_device) {
        LOGW("clGetDeviceIDs(GPU) failed (err=%d) — OpenCL not available", err);
        return false;
    }

    g_ctx = clCreateContext(nullptr, 1, &g_device, nullptr, nullptr, &err);
    if (err != CL_SUCCESS || !g_ctx) {
        LOGW("clCreateContext failed (err=%d)", err);
        return false;
    }

    g_queue = clCreateCommandQueue(g_ctx, g_device, 0, &err);
    if (err != CL_SUCCESS || !g_queue) {
        LOGW("clCreateCommandQueue failed (err=%d)", err);
        return false;
    }

    size_t src_len = std::strlen(kKernelSource);
    g_program = clCreateProgramWithSource(g_ctx, 1, &kKernelSource, &src_len, &err);
    if (err != CL_SUCCESS || !g_program) {
        LOGW("clCreateProgramWithSource failed (err=%d)", err);
        return false;
    }

    err = clBuildProgram(g_program, 1, &g_device, nullptr, nullptr, nullptr);
    if (err != CL_SUCCESS) {
        LOGW("clBuildProgram failed (err=%d) — falling back to CPU", err);
        return false;
    }

    g_kernel = clCreateKernel(g_program, "noid_poseidon2b_batch_stub", &err);
    if (err != CL_SUCCESS || !g_kernel) {
        LOGW("clCreateKernel failed (err=%d)", err);
        return false;
    }

    g_opencl_usable = true;
    LOGI("OpenCL GPU context initialized (stub kernel — CPU fallback in effect)");
    return true;
}

}  // namespace

#endif  // NOID_HAS_OPENCL

extern "C" int noid_opencl_available() {
#if NOID_HAS_OPENCL
    return init_opencl_once() ? 1 : 0;
#else
    return 0;
#endif
}

extern "C" void noid_opencl_batch_hash(
    const char* pow_fields_hex, size_t pow_len,
    uint64_t nonce_start, uint32_t nonce_count,
    uint8_t* out) {

#if NOID_HAS_OPENCL
    if (!noid_opencl_available() || !g_ctx || !g_queue || !g_kernel) {
        // Signal failure: caller falls back to CPU.
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }

    // Decode the 256-hex-char template into 128 raw bytes.
    if (pow_len != 256) {
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }
    std::vector<uint8_t> fields(128, 0);
    auto hex_val = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < 128; ++i) {
        int hi = hex_val(pow_fields_hex[i * 2]);
        int lo = hex_val(pow_fields_hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) {
            std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
            return;
        }
        fields[i] = static_cast<uint8_t>((hi << 4) | lo);
    }

    cl_int err = 0;
    cl_mem fields_buf = clCreateBuffer(g_ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                       128, fields.data(), &err);
    cl_mem out_buf = clCreateBuffer(g_ctx, CL_MEM_WRITE_ONLY,
                                    static_cast<size_t>(nonce_count) * 32, nullptr, &err);
    if (!fields_buf || !out_buf) {
        if (fields_buf) clReleaseMemObject(fields_buf);
        if (out_buf) clReleaseMemObject(out_buf);
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }

    clSetKernelArg(g_kernel, 0, sizeof(cl_mem), &fields_buf);
    clSetKernelArg(g_kernel, 1, sizeof(cl_ulong), &nonce_start);
    clSetKernelArg(g_kernel, 2, sizeof(cl_uint),  &nonce_count);
    clSetKernelArg(g_kernel, 3, sizeof(cl_mem),   &out_buf);

    size_t global = nonce_count;
    err = clEnqueueNDRangeKernel(g_queue, g_kernel, 1, nullptr, &global, nullptr, 0, nullptr, nullptr);
    if (err != CL_SUCCESS) {
        clReleaseMemObject(fields_buf);
        clReleaseMemObject(out_buf);
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }

    err = clEnqueueReadBuffer(g_queue, out_buf, 1 /*blocking*/, 0,
                              static_cast<size_t>(nonce_count) * 32, out,
                              0, nullptr, nullptr);
    clFinish(g_queue);
    clReleaseMemObject(fields_buf);
    clReleaseMemObject(out_buf);

    if (err != CL_SUCCESS) {
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }

    // Detect the stub sentinel on the first digest and signal fallback.
    if (nonce_count > 0 && std::memcmp(out, NOID_STUB_SENTINEL, 32) == 0) {
        // Stub ran successfully — but it doesn't actually compute Poseidon2b.
        // Mark out so the caller routes through the CPU.
        std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
        return;
    }

    // Real kernel result (future). Leave out untouched.
#else
    // OpenCL not compiled in — signal "not implemented".
    (void)pow_fields_hex; (void)pow_len; (void)nonce_start;
    std::memset(out, 0xFF, static_cast<size_t>(nonce_count) * 32);
#endif
}
