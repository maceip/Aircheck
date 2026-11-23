package com.voicestudio.verifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to `libvoicestudio_verifier.so` (`verifier_jni.cc`).
 * Gracefully reports `isNativeAvailable == false` on host JVM unit tests where the Android
 * arm64-v8a shared library is not loaded, while delegating to C++20 native code on Android.
 */
object VerifierJni {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var nativeLoaded: Boolean = false

    @Volatile
    private var loadAttempted: Boolean = false

    val isNativeAvailable: Boolean
        get() {
            if (!loadAttempted) {
                synchronized(this) {
                    if (!loadAttempted) {
                        nativeLoaded = try {
                            System.loadLibrary("voicestudio_verifier")
                            true
                        } catch (_: UnsatisfiedLinkError) {
                            false
                        } catch (_: SecurityException) {
                            false
                        } finally {
                            loadAttempted = true
                        }
                    }
                }
            }
            return nativeLoaded
        }

    external fun nativeInitVerifier(
        sherpaCtcModelDir: String,
        weSpeakerOnnxPath: String,
        dnsmosP808OnnxPath: String,
        masterCentroidBinPath: String
    ): Long

    external fun nativeVerifyCandidate(
        verifierHandle: Long,
        pcm44k: FloatArray,
        expectedText: String,
        maxCer: Float,
        maxPhonemeDurationMs: Float,
        minSpeakerCosineSim: Float,
        minDnsmosP808: Float,
        maxTailRmsDb: Float,
        minSyllablesPerSec: Float,
        maxSyllablesPerSec: Float
    ): String

    external fun nativeScanZeroCrossingsAndTail(
        pcm44k: FloatArray,
        wordStartSamples: LongArray,
        wordEndSamples: LongArray
    ): String

    external fun nativeDestroyVerifier(verifierHandle: Long)

    fun scanZeroCrossingsAndTailOrNull(
        pcm44k: FloatArray,
        wordStartSamples: LongArray,
        wordEndSamples: LongArray
    ): NativeScanResponse? {
        if (!isNativeAvailable) {
            return null
        }
        return runCatching {
            val raw = nativeScanZeroCrossingsAndTail(pcm44k, wordStartSamples, wordEndSamples)
            json.decodeFromString(NativeScanResponse.serializer(), raw)
        }.getOrNull()
    }

    fun decodeGateResponse(rawJson: String): NativeGateResponse =
        json.decodeFromString(NativeGateResponse.serializer(), rawJson)
}

@Serializable
data class NativeWordAlignment(
    val word: String = "",
    val startSample: Long = 0L,
    val endSample: Long = 0L,
    val zeroCrossIn: Long = 0L,
    val zeroCrossOut: Long = 0L,
    val f0PeakSample: Long = 0L
)

@Serializable
data class NativeGateResponse(
    val ok: Boolean = false,
    val error: String = "",
    val cer: Float = 1.0f,
    val maxPhonemeDurationMs: Float = 0.0f,
    val speakerCosineSim: Float = 0.0f,
    val dnsmosP808: Float = 0.0f,
    val tailRmsDb: Float = 0.0f,
    val tailHfSpectralFlux: Float = 0.0f,
    val tailHfGlitchDetected: Boolean = false,
    val syllablesPerSec: Float = 0.0f,
    val passedAllGates: Boolean = false,
    val failureReason: String = "",
    val tokens: List<NativeWordAlignment> = emptyList()
)

@Serializable
data class NativeWordBoundary(
    val startSample: Long = 0L,
    val endSample: Long = 0L,
    val zeroCrossIn: Long = 0L,
    val zeroCrossOut: Long = 0L,
    val f0PeakSample: Long = 0L
)

@Serializable
data class NativeScanResponse(
    val tailRmsDb: Float = -120.0f,
    val tailPeakFrameRmsDb: Float = -120.0f,
    val tailHfEnergyDb: Float = -120.0f,
    val tailHfSpectralFlux: Float = 0.0f,
    val tailHfGlitchDetected: Boolean = false,
    val boundaries: List<NativeWordBoundary> = emptyList()
)
