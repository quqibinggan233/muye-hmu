# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.fengkao.timetable.**$$serializer { *; }
-keepclassmembers class com.fengkao.timetable.** { *** Companion; }
-keepclasseswithmembers class com.fengkao.timetable.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.hmu.muye.**$$serializer { *; }
-keepclassmembers class com.hmu.muye.** { *** Companion; }
-keepclasseswithmembers class com.hmu.muye.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
