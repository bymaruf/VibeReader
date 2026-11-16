# VibeReader release keep rules.
#
# R8 full mode is intentionally conservative around reflection-heavy libraries
# used by the reader, persistence, and networking layers.

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Room resolves generated database implementations and DAO/entity members by
# name, so they must survive shrinking and obfuscation.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Room entities, DAOs, and app data models used by generated adapters,
# JSON conversion, or reflection.
-keep class com.example.vibereader.core.database.** { *; }
-keep class com.example.vibereader.data.room.** { *; }
-keep class com.example.vibereader.data.local.** { *; }
-keep class com.example.vibereader.model.** { *; }
-keep class com.example.vibereader.domain.model.** { *; }

# Kotlin serialization (if introduced by a generated or future model) relies on
# companion serializers and generated `$$serializer` classes.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.vibereader.**$$serializer { *; }
-keepclassmembers class com.example.vibereader.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.vibereader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# Gson type tokens and reflective fields.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# Retrofit service interfaces and annotations.
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Reader engines use reflection and bundled optional classes.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class nl.siegmann.epublib.** { *; }
-dontwarn nl.siegmann.epublib.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn org.xmlpull.**
-dontwarn org.kxml2.**
-dontwarn com.gemalto.jp2.**
