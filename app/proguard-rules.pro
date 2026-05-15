# ═══════════════════════════════════════════════════════════════
#  Rails Mobile — ProGuard Rules
# ═══════════════════════════════════════════════════════════════

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Kotlin Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile **;
}

# ── Jetpack Compose ──
-keep class * extends androidx.compose.ui.node.ModifierNodeElement
-dontwarn androidx.compose.**

# ── Firebase Realtime Database ──
-keep class com.google.firebase.database.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Kotlin Serialization ──
# Serialization uses reflection at runtime — keep @Serializable classes and their serializers
-keepattributes *Annotation*, Signature
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **.serializable.** { *; }
-keepclassmembers class **.serializable.** {
    *** Companion;
}
-keepclasseswithmembers class **.serializable.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep @Serializable annotated classes
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    *** serializer(...);
}

# ── AndroidX / Support ──
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ── RAILS App — Keep data models used by Firebase ──
-keep class cz.julek.rails.network.** { *; }
-keep class cz.julek.rails.service.** { *; }
