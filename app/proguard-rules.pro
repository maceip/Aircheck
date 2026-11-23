# Aircheck R8 Full Mode Rules

# Keep Kotlinx Serialization generated serializers & Nav3 NavKey routes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Jetpack AppFunctions service entry point & serializable DTOs
-keep class * extends androidx.appfunctions.AppFunctionService { *; }
-keep @androidx.appfunctions.AppFunctionSerializable class * { *; }
-keepclassmembers class * {
    @androidx.appfunctions.AppFunctionDeclaration <methods>;
}

# Keep Room database & entities
-keep class * extends androidx.room.RoomDatabase { *; }
