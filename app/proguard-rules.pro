# ── Project-specific ProGuard rules ──────────────────────────────────

# ── Room: keep entities, DAOs, and database classes ──
-keep class com.ioniq.data.model.** { *; }
-keep class com.ioniq.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ── BLE: Nordic library + our transport/manager classes ──
-keep class no.nordicsemi.android.ble.** { *; }
-keep class no.nordicsemi.android.support.v18.scanner.** { *; }
-keep class com.ioniq.ble.** { *; }

# ── OBD parsing ──
-keep class com.ioniq.obd.** { *; }

# ── Home Assistant integration ──
-keep class com.ioniq.ha.** { *; }

# ── Android Auto / media services ──
-keep class com.ioniq.auto.** { *; }
-keep class * extends android.support.v4.media.MediaBrowserServiceCompat { *; }
-keep class * extends androidx.media.MediaBrowserServiceCompat { *; }

# ── Foreground service (VehicleMonitorService) ──
-keep class com.ioniq.service.** { *; }

# ── OkHttp / Retrofit / WebSocket ──
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.java_websocket.**
-keep class org.java_websocket.** { *; }
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

# Retrofit: preserve generic signatures for Gson converter
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# ── Kotlin coroutines ──
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── Compose: keep @Composable functions and runtime ──
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── Accompanist (permissions) ──
-dontwarn com.google.accompanist.**

# ── Timber logging ──
-dontwarn timber.log.**
-keep class timber.log.** { *; }

# ── AndroidX Security Crypto ──
-dontwarn androidx.security.**
-keep class androidx.security.crypto.** { *; }

# ── WorkManager ──
-dontwarn androidx.work.**
-keep class androidx.work.** { *; }

# ── Keep enum values (used in ObdTransport.ConnectionState, etc.) ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Keep Parcelable creators ──
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Keep BuildConfig ──
-keep class com.ioniq.BuildConfig { *; }
