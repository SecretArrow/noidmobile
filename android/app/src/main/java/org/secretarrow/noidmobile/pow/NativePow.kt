// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.pow

/**
 * JNI bindings to `libnoid_jni.so` (built from `src/main/cpp/noid_jni.cpp`),
 * which in turn calls the C ABI exported by `libnoidmobile_pow.so` (built
 * from `rust/noidmobile-pow` via cargo-ndk).
 *
 * All functions are thread-safe and safe to call from coroutines.
 */
object NativePow {

    init {
        // The actual `System.loadLibrary` calls happen in `NoidApp.onCreate`.
    }

    /** Poseidon2b PoW hash of a single nonce. 32-byte digest, or null on error. */
    external fun hashPowNative(powFieldsHex: String, nonceLeHex: String): ByteArray

    /** True if `hash < target` as 256-bit LE integers. */
    external fun checkTargetNative(hash: ByteArray, targetLeHex: String): Boolean

    /** True if the address is a valid bech32m Parano1d address (HRP `o1`). */
    external fun validateAddressNative(addr: String): Boolean

    /**
     * Batch-hash `nonceCount` consecutive nonces starting at `nonceStart`.
     * Returns a flat `ByteArray` of length `nonceCount * 32`. Prefers
     * OpenCL when available; falls back to the CPU path otherwise.
     */
    external fun batchHashNative(powFieldsHex: String, nonceStart: Long, nonceCount: Int): ByteArray

    /** True if `a < b` as 256-bit LE integers (matches upstream `le256_lt`). */
    external fun le256LtNative(a: ByteArray, b: ByteArray): Boolean

    /** True if the OpenCL backend compiled in and initialized successfully. */
    external fun openclAvailableNative(): Boolean

    /** Logs the active Poseidon2b backend (OpenCL vs CPU). Called from NoidApp. */
    external fun logBackendNative()

    /** Convenience: compute the hex digest for a single nonce. */
    fun hashPowHex(powFieldsHex: String, nonceLeHex: String): String {
        val bytes = hashPowNative(powFieldsHex, nonceLeHex) ?: return ""
        return bytes.toHex()
    }

    /** Convenience: batch-hash to a list of 64-char hex digests. */
    fun batchHashHex(powFieldsHex: String, nonceStart: Long, nonceCount: Int): List<String> {
        val bytes = batchHashNative(powFieldsHex, nonceStart, nonceCount) ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        val out = ArrayList<String>(nonceCount)
        for (i in 0 until nonceCount) {
            val slice = bytes.copyOfRange(i * 32, (i + 1) * 32)
            out.add(slice.toHex())
        }
        return out
    }
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
