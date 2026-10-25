// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// STUB OpenCL kernel for the Parano1d Poseidon2b PoW batch hash.
//
// A production GPU Poseidon2b permutation over GF(2^128) requires a custom
// field-arithmetic implementation tuned to the Adreno / Mali vector width
// and ALU budget. That is a substantial body of work and is intentionally
// out of scope for the v0.1 release.
//
// To keep the integration shape correct, this kernel writes a known
// sentinel into every output digest. The C++ host wrapper in
// `opencl_miner.cpp` detects the sentinel and falls back to the CPU path
// in libnoidmobile_pow.so. The CPU path is the default and always works.
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
