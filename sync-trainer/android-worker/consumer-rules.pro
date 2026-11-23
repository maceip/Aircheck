# VoiceStudio R8 Full Mode consumer keep rules for JNI, Kotlinx Serialization, and WorkManager.

# Keep Kotlinx Serialization serializers across all com.voicestudio.* modules
-keep,includedescriptorclasses class com.voicestudio.**$$serializer { *; }
-keepclassmembers class com.voicestudio.** {
    *** Companion;
}
-keepclasseswithmembers class com.voicestudio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep JNI entry points & C++ FindClass/GetMethodID DTOs
-keep class com.voicestudio.engine.NativeCandidateTokens { *; }
-keep class com.voicestudio.engine.VoiceEngineJni { *; }
-keep class com.voicestudio.edl.EdlController {
    native <methods>;
}
-keep class com.voicestudio.verifier.VerifierJni { *; }

# Keep WorkManager CoroutineWorker subclasses instantiated via reflection
-keep class com.voicestudio.sync.PreferenceSyncWorker { *; }
-keep class com.voicestudio.sync.VoiceSynthesisWorker { *; }
