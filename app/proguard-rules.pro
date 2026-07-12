# DobarBoiler ProGuard/R8 rules.
# Room, WorkManager, DataStore and OkHttp ship consumer rules; below covers the rest.

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep generated serializer lookups (Companion.serializer()).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
# OkHttp platform detection classes referenced reflectively but absent on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- androidx.security-crypto / Tink ---
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.crypto.tink.** { *; }

# --- WorkManager: workers are instantiated reflectively by class name ---
-keep class rs.novica.dobarboiler.scheduling.** extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker

# --- Kotlin coroutines debug metadata (safe to drop warnings) ---
-dontwarn kotlinx.coroutines.debug.**
