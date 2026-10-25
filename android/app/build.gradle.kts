plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.secretarrow.noidmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.secretarrow.noidmobile"
        // Lowered from 26 → 24 to support Android 7.0+ (Nougat).
        // Compose's minSdk is 21, so 24 is well within bounds.
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Pass CMake flags down to the native build.
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-O2", "-fvisibility=hidden", "-fexceptions")
                arguments("-DANDROID_STL=c++_static")
            }
        }

        // Build for the ABIs we ship a Rust .so for.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    // Define signing config at the TOP LEVEL — AGP requires this so that
    // zipAlign + apksigner run in the correct order during assembleRelease.
    // (Defining signingConfigs.create() inside the buildType block caused
    //  AGP to skip the final apksigner pass, producing an APK that Android
    //  sometimes rejected as "package corrupted".)
    signingConfigs {
        create("release") {
            val ks = (System.getenv("NOID_KEYSTORE_FILE")
                ?: providers.gradleProperty("NOID_KEYSTORE_FILE").orNull)
                ?.takeIf { it.isNotBlank() }
            val ksPass = (System.getenv("NOID_KEYSTORE_PASSWORD")
                ?: providers.gradleProperty("NOID_KEYSTORE_PASSWORD").orNull)
                ?.takeIf { it.isNotBlank() }
            val alias = (System.getenv("NOID_KEY_ALIAS")
                ?: providers.gradleProperty("NOID_KEY_ALIAS").orNull)
                ?.takeIf { it.isNotBlank() }
            val keyPass = (System.getenv("NOID_KEY_PASSWORD")
                ?: providers.gradleProperty("NOID_KEY_PASSWORD").orNull)
                ?.takeIf { it.isNotBlank() }
            if (ks != null && ksPass != null && alias != null && keyPass != null) {
                storeFile = file(ks)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Reference the top-level signing config — this lets AGP run
            // zipAlign + apksigner in the correct order.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Note: kotlinOptions {} is treated as a hard error by AGP 8.7's
    // Kotlin DSL script compiler (deprecation promoted to error). The
    // JVM target is inherited from compileOptions above; Compose's
    // freeCompilerArgs are configured by the kotlin-compose plugin
    // automatically. No explicit kotlinOptions block needed.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Side-by-side NDK version. r26d ships with CMake 3.22.1+.
    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
        // useLegacyPackaging = true extracts .so files at install time.
        // Set to false to keep them inside the APK — smaller install
        // footprint, and required for APK signing block to be valid.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
