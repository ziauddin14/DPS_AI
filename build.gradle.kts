/*
 * Project Falcon — DPS Android Client
 * Root build script.
 *
 * Plugins are declared with `apply false` here and applied per-module.
 * All versions live in `gradle/libs.versions.toml` (single source of truth
 * for dependencies, mirroring Development Rule 5).
 */

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
