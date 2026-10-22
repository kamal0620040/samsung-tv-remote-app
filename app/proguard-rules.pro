# Keep rules for the libraries this app uses, ready for when release
# minification is enabled (see app/build.gradle.kts).

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# org.json ships with Android but is referenced from code
-dontwarn org.json.**

# Kotlin coroutines service registrations and internals
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin metadata
-keep class kotlin.Metadata { *; }
