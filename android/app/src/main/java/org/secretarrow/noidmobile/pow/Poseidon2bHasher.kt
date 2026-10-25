// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.pow

import kotlin.math.min

/**
 * High-level hasher that delegates to [NativePow]. The OpenCL path is
 * selected automatically when [NativePow.openclAvailableNative] returns
 * true — otherwise the CPU path through `libnoidmobile_pow.so` is used.
 *
 * The batch interface mirrors the upstream `FixedFieldNonceBatch`:
 * given a fixed template, hash many consecutive nonces in one call to
 * amortize the Poseidon2b setup cost.
 */
class Poseidon2bHasher(
    private val powFieldsHex: String,
    /** Canonical nonce lane index — always 10 in the Parano1d protocol. */
    val nonceFieldIndex: Int = 10,
) {

    /** Compute the 32-byte hash for a single nonce (LE u128). */
    fun hash(nonce: Long): ByteArray {
        val nonceLeHex = longToLeHex(nonce)
        return NativePow.hashPowNative(powFieldsHex, nonceLeHex)
            ?: ByteArray(32) { 0xFF.toByte() }
    }

    /** Compute the 64-char hex hash for a single nonce (LE u128). */
    fun hashHex(nonce: Long): String = NativePow.hashPowHex(powFieldsHex, longToLeHex(nonce))

    /**
     * Batch-hash `count` consecutive nonces starting at [nonceStart].
     * Returns a list of 64-char hex digests.
     *
     * Implementation note: the upstream miner uses `DIGEST_BATCH = 256`.
     * We split the request into sub-batches of 256 so the peak memory is
     * bounded and OpenCL/CPU paths both stay responsive.
     */
    fun batchHashHex(nonceStart: Long, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val out = ArrayList<String>(count)
        var start = nonceStart
        var remaining = count
        while (remaining > 0) {
            val n = min(remaining, BATCH_SIZE)
            out.addAll(NativePow.batchHashHex(powFieldsHex, start, n))
            start += n.toLong()
            remaining -= n
        }
        return out
    }

    /** True if `hash < target` as 256-bit LE integers. */
    fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean {
        return NativePow.checkTargetNative(hash, targetLeHex)
    }

    companion object {
        /** Batch size — matches upstream `DIGEST_BATCH = 256`. */
        const val BATCH_SIZE = 256

        /** Encode a `Long` (assumed to fit in u128) as 16-byte LE hex (32 chars). */
        fun longToLeHex(nonce: Long): String {
            val bytes = ByteArray(16)
            var v = nonce
            for (i in 0 until 8) {
                bytes[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            // Upper 8 bytes are zero (we use a Long nonce, not a full u128).
            return bytes.toHex()
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
    }
}
