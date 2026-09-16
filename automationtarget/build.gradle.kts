/*
 * M9 Phase 1 — deterministic automation test target.
 *
 * Exists SOLELY to give the real-device instrumented/process-death tests a
 * genuinely controlled, first-party UI to automate — never shipped, never
 * part of the DPS app's own release build or Play listing. See
 * docs/DAY-20-M9-IMPLEMENTATION-PLAN.md's own "Test Application Plan".
 *
 * Deliberately minimal: plain Android Views, no Compose, no navigation, no
 * dependency beyond androidx.core — the one screen this module has is not a
 * UI to maintain, it is a fixture.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.softwaremine.dps.automationtarget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.softwaremine.dps.automationtarget"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
