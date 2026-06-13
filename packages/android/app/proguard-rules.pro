# Keep OpenAPI generated models
-keep class com.opencode.app.api.** { *; }
-keep class com.opencode.app.api.models.** { *; }
-keep class com.opencode.app.api.infrastructure.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.opencode.app.**$$serializer { *; }
-keepclassmembers class com.opencode.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
