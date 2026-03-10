# Add project specific ProGuard rules here.
-keep class com.arcle.intelligence.telemetry.** { *; }
-keep class com.arcle.intelligence.memory.** { *; }
-keepattributes *Annotation*
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.arcle.intelligence.**$$serializer { *; }
-keepclassmembers class com.arcle.intelligence.** {
    *** Companion;
}
-keepclasseswithmembers class com.arcle.intelligence.** {
    kotlinx.serialization.KSerializer serializer(...);
}
