# Relay's own code is small: keep all of it, unrenamed, so serialization, reflection-free JSON
# and crash logs behave exactly as in debug builds. Shrinking still removes unused library code.
-dontobfuscate
-keep class dev.relay.tv.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# kotlinx.serialization (its own rules cover most of this; kept explicit for safety)
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# Media3 loads the FFmpeg audio renderer by reflection, and the decoder calls into Java from JNI.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.** { *; }
-dontwarn org.jellyfin.**

# OkHttp / Okio optional platform classes
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
