# Leo — SHS LAB ProGuard Rules

# Keep all Leo classes (agent core must not be obfuscated)
-keep class com.shslab.leo.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Security crypto
-keep class androidx.security.crypto.** { *; }

# JSON
-keep class org.json.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep accessibility service
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep services
-keep class * extends android.app.Service { *; }

# Keep BroadcastReceivers
-keep class * extends android.content.BroadcastReceiver { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
