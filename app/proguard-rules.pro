# Project Falcon — DPS Android Client
# R8 / ProGuard rules for release builds.

# --- kotlinx.serialization ---
# The serializer for a @Serializable class is generated as a companion; R8
# cannot see the reflective link, so both sides must be kept.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.softwaremine.dps.**$$serializer { *; }
-keepclassmembers class com.softwaremine.dps.** {
    *** Companion;
}
-keepclasseswithmembers class com.softwaremine.dps.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- JNI boundary (llama.cpp, ADR-004) ---
# Native code resolves these symbols by name at runtime. Renaming or removing
# them produces an UnsatisfiedLinkError that no build-time check will catch,
# so the bridge is kept verbatim.
-keep class com.softwaremine.dps.data.runtime.llamacpp.LlamaCppBridge {
    native <methods>;
    *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Coroutines ---
-dontwarn kotlinx.coroutines.**

# Keep sealed error/state hierarchies intact for readable crash reports.
-keep class com.softwaremine.dps.core.error.DpsError { *; }
-keep class com.softwaremine.dps.core.error.DpsError$* { *; }
