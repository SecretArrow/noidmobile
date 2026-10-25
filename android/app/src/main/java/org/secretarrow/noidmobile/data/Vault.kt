// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences wrapper. Stores the 256-bit spend secret,
 * the node URL, the pool config, and the optional mining key.
 *
 * Backed by `EncryptedSharedPreferences` (AES-GCM 256, AES-SIV-CMAC) —
 * the master key is held by the Android Keystore and never leaves the
 * device. We NEVER log secrets.
 */
class Vault(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "noidmobile_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var spendSecretHex: String?
        get() = prefs.getString(K_SPEND_SECRET, null)
        set(v) = prefs.edit().putString(K_SPEND_SECRET, v).apply()

    /** SOLO mode: JSON-RPC URL to a parano1d node (local or remote VPS). */
    var rpcUrl: String
        get() = prefs.getString(K_RPC_URL, "http://127.0.0.1:9601") ?: "http://127.0.0.1:9601"
        set(v) = prefs.edit().putString(K_RPC_URL, v).apply()

    /** SOLO mode: Bearer token (mining key) for the node RPC. Empty = no auth. */
    var miningKey: String
        get() = prefs.getString(K_MINING_KEY, "") ?: ""
        set(v) = prefs.edit().putString(K_MINING_KEY, v).apply()

    /** POOL mode: peakpool Stratum V2 host (e.g. "pool.your-vps.com"). */
    var poolHost: String
        get() = prefs.getString(K_POOL_HOST, "") ?: ""
        set(v) = prefs.edit().putString(K_POOL_HOST, v).apply()

    /** POOL mode: peakpool Stratum V2 port (default 3333). */
    var poolPort: Int
        get() = prefs.getInt(K_POOL_PORT, 3333)
        set(v) = prefs.edit().putInt(K_POOL_PORT, v).apply()

    /** POOL mode: pool's static Noise public key (64 hex chars) for MITM auth. */
    var poolPubkey: String
        get() = prefs.getString(K_POOL_PUBKEY, "") ?: ""
        set(v) = prefs.edit().putString(K_POOL_PUBKEY, v).apply()

    var threads: Int
        get() = prefs.getInt(K_THREADS, 0) // 0 = all cores
        set(v) = prefs.edit().putInt(K_THREADS, v.coerceAtLeast(0)).apply()

    var minerMode: MinerMode
        get() = MinerMode.valueOf(prefs.getString(K_MODE, MinerMode.SOLO.name) ?: MinerMode.SOLO.name)
        set(v) = prefs.edit().putString(K_MODE, v.name).apply()

    fun clearWallet() {
        prefs.edit().remove(K_SPEND_SECRET).apply()
    }

    companion object {
        private const val K_SPEND_SECRET = "spend_secret_hex"
        private const val K_RPC_URL = "rpc_url"
        private const val K_MINING_KEY = "mining_key"
        private const val K_POOL_HOST = "pool_host"
        private const val K_POOL_PORT = "pool_port"
        private const val K_POOL_PUBKEY = "pool_pubkey"
        private const val K_THREADS = "threads"
        private const val K_MODE = "miner_mode"
    }
}

enum class MinerMode { SOLO, POOL }
