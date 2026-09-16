/*
 * Project Falcon — DPS Android Client
 * Settings / module graph.
 *
 * Currently a single application module (`:app`) whose internal package
 * boundaries mirror the future module split exactly. See ADR-005.
 * When build times or team size justify it, `:core`, `:domain`, `:data`
 * and `:ai` become real Gradle modules with no source changes required.
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DPS"

include(":app")

// M9 Phase 1's deterministic automation test target — never shipped, never
// part of :app's own release build or Play listing. See
// automationtarget/build.gradle.kts's own doc.
include(":automationtarget")
