/*
 * Project Falcon — DPS Android Client
 * Application module.
 *
 * Single Gradle module by decision (ADR-005). Internal package boundaries are
 * shaped exactly like a future multi-module split, so extraction is mechanical:
 *
 *     ui  →  ai  →  domain  ←  data
 *                     ↑          │
 *                     └──────────┘
 *                  core (depends on nothing)
 *
 * `domain` is pure Kotlin with zero android.* imports, which keeps the entire
 * AI layer unit-testable on the JVM without an emulator.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.softwaremine.dps"
    compileSdk = 35

    // Pinned explicitly. Left unset, AGP silently downloads whichever version
    // its default happens to be — during the first build it fetched 34.0.0
    // unprompted, which is both 136 MB of unrequested disk and a source of
    // build non-determinism across machines. Pinning makes the toolchain an
    // explicit, reviewable decision.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.softwaremine.dps"

        // minSdk 26 (Android 8.0):
        //  - llama.cpp requires 64-bit ABIs, universally present from this level
        //  - java.time and modern file APIs available without desugaring
        //  - ~98% device reach
        minSdk = 26
        targetSdk = 35

        versionCode = 1
        versionName = "2.0.0-foundation"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only 64-bit ABIs. 32-bit devices cannot address the working set a
        // 1.5B parameter model requires, so shipping armeabi-v7a would produce
        // installs that are guaranteed to OOM. Excluding it is a correctness
        // decision, not a size optimisation.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Points the Ollama development runtime at the host machine.
            // 10.0.2.2 is the emulator's alias for the host loopback; for a
            // physical device over USB use `adb reverse tcp:11434 tcp:11434`
            // and this same value resolves correctly. See ADR-009.
            buildConfigField("String", "OLLAMA_BASE_URL", "\"http://10.0.2.2:11434\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds must never reach a network AI runtime. An empty
            // base URL makes the Ollama provider report itself unavailable,
            // enforcing Offline First at the build-configuration level rather
            // than by convention.
            buildConfigField("String", "OLLAMA_BASE_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Native .so files must not be compressed in the APK: llama.cpp is
        // loaded via mmap, which requires page-aligned uncompressed pages.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

/**
 * Kotlin JVM target.
 *
 * Declared at the module level, not inside `android { }` — the `kotlin`
 * extension is a sibling of `android`, not a member of it. Must match
 * `compileOptions` above, or Kotlin and Java sources compile to different
 * bytecode levels and fail to link.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Kotlin / concurrency ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Provides collectAsStateWithLifecycle — the lifecycle-aware collector the
    // chat surface uses so token streaming stops while the app is backgrounded.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Networking: Ollama development runtime + model downloads ---
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)

    // --- Test ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
