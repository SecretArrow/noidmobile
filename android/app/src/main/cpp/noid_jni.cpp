// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// JNI bridge between Kotlin (org.secretarrow.noidmobile.pow.NativePow)
// and the C ABI exported by libnoidmobile_pow.so (see
// rust/noidmobile-pow/src/cffi.rs).
//
// CRITICAL: Every JNI function catches C++ exceptions and returns null/
// false on error. We NEVER let a C++ exception propagate across the JNI
// boundary — that would abort the JVM and crash the app.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "noid_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    int32_t noidmobile_hash_pow(const char* pow_fields_hex, size_t pow_len,
                                const char* nonce_le_hex, size_t nonce_len,
                                uint8_t* out);
    int32_t noidmobile_check_target(const uint8_t* hash, size_t hash_len,
                                    const char* target_le_hex, size_t target_len);
    int32_t noidmobile_validate_address(const char* addr, size_t addr_len);
    int32_t noidmobile_batch_hash(const char* pow_fields_hex, size_t pow_len,
                                  uint64_t nonce_start, uint32_t nonce_count,
                                  uint8_t* out);
    int32_t noidmobile_le256_lt(const uint8_t* a, const uint8_t* b);

    // Provided by opencl_miner.cpp — always safe to call (returns 0 if
    // OpenCL is compiled out, never throws).
    int  noid_opencl_available();
    void noid_opencl_batch_hash(const char* pow_fields_hex, size_t pow_len,
                                uint64_t nonce_start, uint32_t nonce_count,
                                uint8_t* out);
}

namespace {

// Convert a jstring to a std::string. Returns an empty string if jstr is null.
std::string jstr_to_std(JNIEnv* env, jstring jstr) {
    if (!jstr || !env) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return {};
    std::string s(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_hashPowNative(
    JNIEnv* env, jclass, jstring jPowFields, jstring jNonceLe) {

    if (!env) return nullptr;
    try {
        auto pow_fields = jstr_to_std(env, jPowFields);
        auto nonce_le   = jstr_to_std(env, jNonceLe);

        uint8_t out[32] = {0};
        int rc = noidmobile_hash_pow(
            pow_fields.c_str(), pow_fields.size(),
            nonce_le.c_str(),   nonce_le.size(),
            out);
        if (rc != 0) {
            LOGW("hash_pow failed (rc=%d) — returning null", rc);
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(32);
        if (!result) return nullptr;
        env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<jbyte*>(out));
        return result;
    } catch (const std::exception& e) {
        LOGE("hashPowNative exception: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("hashPowNative unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_checkTargetNative(
    JNIEnv* env, jclass, jbyteArray jHash, jstring jTargetLe) {

    if (!env) return JNI_FALSE;
    try {
        if (!jHash) return JNI_FALSE;
        jsize len = env->GetArrayLength(jHash);
        if (len != 32) return JNI_FALSE;
        uint8_t hash[32] = {0};
        env->GetByteArrayRegion(jHash, 0, 32, reinterpret_cast<jbyte*>(hash));

        auto target = jstr_to_std(env, jTargetLe);
        int rc = noidmobile_check_target(hash, 32, target.c_str(), target.size());
        return rc == 1 ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("checkTargetNative exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("checkTargetNative unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_validateAddressNative(
    JNIEnv* env, jclass, jstring jAddr) {

    if (!env) return JNI_FALSE;
    try {
        auto addr = jstr_to_std(env, jAddr);
        int rc = noidmobile_validate_address(addr.c_str(), addr.size());
        return rc == 1 ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("validateAddressNative exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("validateAddressNative unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_batchHashNative(
    JNIEnv* env, jclass, jstring jPowFields, jlong jNonceStart, jint jNonceCount) {

    if (!env) return nullptr;
    try {
        auto pow_fields = jstr_to_std(env, jPowFields);
        auto nonce_start = static_cast<uint64_t>(jNonceStart);
        auto nonce_count = static_cast<uint32_t>(jNonceCount);
        if (nonce_count == 0) {
            return env->NewByteArray(0);
        }
        // Guard against absurd batch sizes that would OOM the device.
        if (nonce_count > 4096) {
            LOGW("batchHashNative: nonce_count=%u exceeds 4096 limit", nonce_count);
            return nullptr;
        }

        std::vector<uint8_t> out(static_cast<size_t>(nonce_count) * 32, 0);

        // Always use the CPU path — OpenCL is disabled by default to
        // prevent crashes from incompatible vendor libOpenCL.so.
        // The CPU path is correct (wraps upstream noid_poseidon2b) and
        // is what the upstream miner uses.
        bool ok = false;
        if (noid_opencl_available()) {
            noid_opencl_batch_hash(
                pow_fields.c_str(), pow_fields.size(),
                nonce_start, nonce_count, out.data());
            // noid_opencl_batch_hash fills with 0xFF on failure — detect
            // and fall through to CPU.
            if (std::memcmp(out.data(), "\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF", 8) != 0) {
                ok = true;
            }
        }
        if (!ok) {
            int rc = noidmobile_batch_hash(
                pow_fields.c_str(), pow_fields.size(),
                nonce_start, nonce_count, out.data());
            ok = (rc == 0);
        }

        if (!ok) {
            LOGW("batch_hash failed — returning null");
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
        if (!result) return nullptr;
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<jbyte*>(out.data()));
        return result;
    } catch (const std::exception& e) {
        LOGE("batchHashNative exception: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("batchHashNative unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_le256LtNative(
    JNIEnv* env, jclass, jbyteArray jA, jbyteArray jB) {

    if (!env) return JNI_FALSE;
    try {
        if (!jA || !jB) return JNI_FALSE;
        if (env->GetArrayLength(jA) != 32 || env->GetArrayLength(jB) != 32) {
            return JNI_FALSE;
        }
        uint8_t a[32] = {0};
        uint8_t b[32] = {0};
        env->GetByteArrayRegion(jA, 0, 32, reinterpret_cast<jbyte*>(a));
        env->GetByteArrayRegion(jB, 0, 32, reinterpret_cast<jbyte*>(b));
        return noidmobile_le256_lt(a, b) == 1 ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("le256LtNative exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("le256LtNative unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_openclAvailableNative(
    JNIEnv*, jclass) {
    // OpenCL is compiled out by default — always return false.
    // This is intentional: the CPU path is always correct and never crashes.
    return noid_opencl_available() ? JNI_TRUE : JNI_FALSE;
}

// Called from NoidApp.onCreate to log the backend selection.
extern "C" JNIEXPORT void JNICALL
Java_org_secretarrow_noidmobile_pow_NativePow_logBackendNative(
    JNIEnv*, jclass) {
    if (noid_opencl_available()) {
        LOGI("Poseidon2b backend: OpenCL (GPU) + CPU fallback");
    } else {
        LOGI("Poseidon2b backend: CPU (libnoidmobile_pow.so) — OpenCL disabled");
    }
}
