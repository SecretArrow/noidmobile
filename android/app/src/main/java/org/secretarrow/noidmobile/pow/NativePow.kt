// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.pow

import android.util.Log

/**
 * JNI bindings to `libnoid_jni.so` (built from `src/main/cpp/noid_jni.cpp`),
 * which in turn calls the C ABI exported by `libnoidmobile_pow.so` (built
 * from `rust/noidmobile-pow` via cargo-ndk).
 *
 * CRITICAL: JNI functions CAN return null even when declared as non-null
 * in Kotlin. We declare all return types as nullable (`ByteArray?`, `Boolean?`)
 * so callers are forced to handle null. Accessing a null JNI return as
 * non-null throws NullPointerException and crashes the app.
 *
 * All functions are thread-safe and safe to call from coroutines.
 */
object NativePow {

    private const val TAG = "NativePow"

    init {
        // The actual `System.loadLibrary` calls happen in `NoidApp.onCreate`.
    }

    /**
     * Poseidon2b PoW hash of a single nonce. Returns 32-byte digest, or
     * `null` on error (invalid input, native lib failure, JNI exception).
     */
    external fun hashPowNative(powFieldsHex: String, nonceLeHex: String): ByteArray?

    /**
     * True if `hash < target` as 256-bit LE integers. Returns `false` on
     * any error (never throws).
     */
    external fun checkTargetNative(hash: ByteArray, targetLeHex: String): Boolean

    /**
     * True if the address is a valid bech32m Parano1d address (HRP `o1`).
     */
    external fun validateAddressNative(addr: String): Boolean

    /**
     * Batch-hash `nonceCount` consecutive nonces starting at `nonceStart`.
     * Returns a flat `ByteArray` of length `nonceCount * 32`, or `null`
     * on error. Prefers OpenCL when available; falls back to the CPU
     * path otherwise.
     */
    external fun batchHashNative(powFieldsHex: String, nonceStart: Long, nonceCount: Int): ByteArray?

    /** True if `a < b` as 256-bit LE integers (matches upstream `le256_lt`). */
    external fun le256LtNative(a: ByteArray, b: ByteArray): Boolean

    /** True if the OpenCL backend compiled in and initialized successfully. */
    external fun openclAvailableNative(): Boolean

    /**
     * Derive the bech32m Parano1d address (`o1...`) from a 256-bit spend
     * secret (64 lowercase hex chars). Returns `null` on error.
     *
     * This calls the same `noid_poseidon2b::primitives::derive_address`
     * function the `parano1d` node uses — so the address shown locally
     * matches what the node would report.
     */
    external fun deriveAddressNative(secretHex: String): String?

    /** Logs the active Poseidon2b backend (OpenCL vs CPU). Called from NoidApp. */
    external fun logBackendNative()

    /**
     * Convenience: derive address, return "" on error (never throws).
     */
    fun deriveAddress(secretHex: String): String {
        return try {
            deriveAddressNative(secretHex) ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "deriveAddress failed: ${t.message}")
            ""
        }
    }

    /**
     * Convenience: compute the hex digest for a single nonce.
     * Returns "" on any error (never throws).
     */
    fun hashPowHex(powFieldsHex: String, nonceLeHex: String): String {
        return try {
            val bytes = hashPowNative(powFieldsHex, nonceLeHex) ?: return ""
            bytes.toHex()
        } catch (t: Throwable) {
            Log.w(TAG, "hashPowHex failed: ${t.message}")
            ""
        }
    }

    /**
     * Convenience: batch-hash to a list of 64-char hex digests.
     * Returns empty list on any error (never throws).
     */
    fun batchHashHex(powFieldsHex: String, nonceStart: Long, nonceCount: Int): List<String> {
        if (nonceCount <= 0) return emptyList()
        return try {
            val bytes = batchHashNative(powFieldsHex, nonceStart, nonceCount)
                ?: run {
                    Log.w(TAG, "batchHashNative returned null")
                    return emptyList()
                }
            if (bytes.isEmpty() || bytes.size % 32 != 0) {
                Log.w(TAG, "batchHashNative returned malformed array: size=${bytes.size}")
                return emptyList()
            }
            val expected = nonceCount * 32
            if (bytes.size < expected) {
                Log.w(TAG, "batchHashNative returned short array: ${bytes.size} < $expected")
                return emptyList()
            }
            val out = ArrayList<String>(nonceCount)
            for (i in 0 until nonceCount) {
                out.add(bytes.copyOfRange(i * 32, (i + 1) * 32).toHex())
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "batchHashHex failed: ${t.message}")
            emptyList()
        }
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
