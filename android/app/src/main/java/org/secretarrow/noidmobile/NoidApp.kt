// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
package org.secretarrow.noidmobile

import android.app.Application
import android.util.Log
import org.secretarrow.noidmobile.pow.NativePow

/**
 * Application entry point. Loads `libnoidmobile_pow.so` (built from the
 * shared Rust crate via cargo-ndk) and the JNI bridge `libnoid_jni.so`
 * (built from `src/main/cpp`).
 *
 * Never logs any wallet secret, mining key, or RPC bearer token.
 */
class NoidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("noidmobile_pow")
            System.loadLibrary("noid_jni")
            NativePow.logBackend()
            Log.i("NoidApp", "native libraries loaded")
        } catch (t: Throwable) {
            // OpenCL missing is non-fatal; native lib failure is fatal.
            Log.e("NoidApp", "failed to load native libraries", t)
        }
    }
}
