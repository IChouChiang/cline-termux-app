# kotlinx.serialization: keep generated serializers reachable through reflection-free lookup.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.cline.termux.**$$serializer { *; }
-keepclassmembers class dev.cline.termux.** { *** Companion; }
-keepclasseswithmembers class dev.cline.termux.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
