# Media3
-keep class androidx.media3.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
