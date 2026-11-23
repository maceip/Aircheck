package com.voicestudio.engine

import com.google.ai.edge.litert.Accelerator
import com.voicestudio.edl.ACOUSTIC_PREFIX_TAIL_SAMPLES
import com.voicestudio.edl.DEFAULT_TAKES_PER_CLAUSE
import com.voicestudio.edl.ZERO_CROSSING_SCAN_SAMPLES
import java.io.File

/**
 * Discrete s2-pro Dual-AR codebook token sequence returned across JNI from `libvoicestudio_jni.so`.
 * Constructor signature `([IJF)V` is bound directly from `jni_bridge.cc`.
 */
data class NativeCandidateTokens(
    val discreteAudioTokens: IntArray,
    val seed: Long,
    val temperature: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeCandidateTokens) return false
        return discreteAudioTokens.contentEquals(other.discreteAudioTokens) &&
            seed == other.seed &&
            temperature == other.temperature
    }

    override fun hashCode(): Int {
        var result = discreteAudioTokens.contentHashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + temperature.hashCode()
        return result
    }
}

/**
 * Complete Stage A (llama.cpp GGUF+LoRA) -> LiteRT 44.1 kHz DAC -> Stage C (NEON DSP) take output.
 */
data class SynthesizedTakeOutput(
    val discreteAudioTokens: IntArray,
    val pcm44k: FloatArray,
    val seed: Long,
    val temperature: Float,
    val vocoderLatencyMs: Double,
    val vocoderAccelerator: Accelerator
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedTakeOutput) return false
        return discreteAudioTokens.contentEquals(other.discreteAudioTokens) &&
            pcm44k.contentEquals(other.pcm44k) &&
            seed == other.seed &&
            temperature == other.temperature &&
            vocoderLatencyMs == other.vocoderLatencyMs &&
            vocoderAccelerator == other.vocoderAccelerator
    }

    override fun hashCode(): Int {
        var result = discreteAudioTokens.contentHashCode()
        result = 31 * result + pcm44k.contentHashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + vocoderLatencyMs.hashCode()
        result = 31 * result + vocoderAccelerator.hashCode()
        return result
    }
}

/**
 * Safe JNI wrapper over `libvoicestudio_jni.so` (C++20 llama.cpp GGUF+LoRA + NEON DSP mastering).
 *
 * Constitutional Law 2: Never fabricates synthetic speech PCM or fake success handles when native
 * binaries or OTA model artifacts are absent or invalid.
 */
object VoiceEngineJni {

    const val DEFAULT_TARGET_LUFS: Float = -16.0f
    const val DEFAULT_NOISE_FLOOR_DBFS: Float = -58.0f

    @Volatile
    var isNativeLibraryLoaded: Boolean = false
        private set

    @Volatile
    var nativeLoadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("voicestudio_jni")
            isNativeLibraryLoaded = true
        } catch (t: UnsatisfiedLinkError) {
            isNativeLibraryLoaded = false
            nativeLoadError = t.message ?: "Failed to load libvoicestudio_jni.so"
        }
    }

    external fun nativeGetLastError(): String

    external fun nativeInit(
        baseGgufPath: String,
        loraGgufPath: String,
        vocoderTflitePath: String,
        roomIrPcmPath: String
    ): Long

    external fun nativeInitWithLlamaLib(
        baseGgufPath: String,
        loraGgufPath: String,
        vocoderTflitePath: String,
        roomIrPcmPath: String,
        llamaLibPath: String?
    ): Long

    external fun nativeHotSwapLora(
        engineHandle: Long,
        newLoraGgufPath: String
    ): Boolean

    external fun nativeSynthesizeClause(
        engineHandle: Long,
        normalizedText: String,
        prefixPcmPath: String?,
        seed: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxAudioTokens: Int
    ): NativeCandidateTokens?

    external fun nativeGetResolvedLlamaLibPath(engineHandle: Long): String

    external fun nativeSynthesizeCandidates(
        engineHandle: Long,
        normalizedText: String,
        prefixPcmPath: String?,
        numCandidates: Int
    ): Array<NativeCandidateTokens>?

    external fun nativeMasterAndWritePcm(
        inputPcm: FloatArray,
        roomIrPcmPath: String?,
        roomTonePcmPath: String?,
        outputPcmPath: String?,
        targetLufs: Float,
        noiseFloorDbfs: Float
    ): FloatArray?

    external fun nativeExtractAcousticPrefixTail(
        inputPcm: FloatArray,
        tailSamples: Int
    ): FloatArray

    external fun nativeFindNearestZeroCrossing(
        pcm: FloatArray,
        targetSample: Long,
        scanRadius: Int
    ): Long

    external fun nativeFindNearestZeroCrossingInFile(
        pcmFilePath: String,
        targetSample: Long,
        scanRadius: Int
    ): Long

    external fun nativeEvictWeightPages(engineHandle: Long): Long

    external fun nativeDestroy(engineHandle: Long)

    private fun requireNativeLoaded(): Result<Unit> =
        if (isNativeLibraryLoaded) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Native library libvoicestudio_jni.so is not loaded: ${nativeLoadError ?: "unknown error"}"
                )
            )
        }

    /**
     * Resolves `libllama.so` from an explicit OTA path/directory, the app's `nativeLibraryDir`,
     * or OTA bundle-relative `android-libs[/arm64-v8a]/libllama.so` directories.
     * Returns null if none exist on disk so native `dlopen` performs standard linker lookup
     * and fails explicitly if unavailable (Constitutional Law 2).
     */
    fun resolveLlamaLibraryPath(
        baseGgufPath: String,
        nativeLibraryDir: String? = null,
        explicitLlamaLibPath: String? = null
    ): String? {
        fun resolveFileOrDir(candidatePath: String?): String? {
            val trimmed = candidatePath?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val f = File(trimmed)
            if (f.isFile) return f.absolutePath
            if (f.isDirectory) {
                val names = listOf(
                    "libllama.so",
                    "arm64-v8a/libllama.so",
                    "libllama.dylib"
                )
                for (name in names) {
                    val nested = File(f, name)
                    if (nested.isFile) return nested.absolutePath
                }
            }
            return null
        }

        // Explicit path/dir takes strict priority.
        if (!explicitLlamaLibPath.isNullOrBlank()) {
            return resolveFileOrDir(explicitLlamaLibPath) ?: explicitLlamaLibPath.trim()
        }

        // App nativeLibraryDir (e.g. context.applicationInfo.nativeLibraryDir).
        resolveFileOrDir(nativeLibraryDir)?.let { return it }

        // OTA bundle-relative android-libs directories next to baseGgufPath.
        val bundleDir = File(baseGgufPath).parentFile
        if (bundleDir != null) {
            val otaCandidates = listOf(
                File(bundleDir, "android-libs/libllama.so"),
                File(bundleDir, "android-libs/arm64-v8a/libllama.so"),
                File(bundleDir, "libllama.so"),
                bundleDir.parentFile?.let { File(it, "android-libs/libllama.so") },
                bundleDir.parentFile?.let { File(it, "android-libs/arm64-v8a/libllama.so") }
            )
            for (cand in otaCandidates) {
                if (cand != null && cand.isFile) {
                    return cand.absolutePath
                }
            }
        }
        return null
    }

    fun initEngine(
        baseGgufPath: String,
        loraGgufPath: String,
        vocoderTflitePath: String,
        roomIrPcmPath: String,
        llamaLibPath: String? = null,
        nativeLibraryDir: String? = null
    ): Result<Long> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        val resolvedLlamaPath = resolveLlamaLibraryPath(
            baseGgufPath = baseGgufPath,
            nativeLibraryDir = nativeLibraryDir,
            explicitLlamaLibPath = llamaLibPath
        )
        val handle = if (resolvedLlamaPath.isNullOrBlank()) {
            nativeInit(baseGgufPath, loraGgufPath, vocoderTflitePath, roomIrPcmPath)
        } else {
            nativeInitWithLlamaLib(
                baseGgufPath,
                loraGgufPath,
                vocoderTflitePath,
                roomIrPcmPath,
                resolvedLlamaPath
            )
        }
        return if (handle != 0L) {
            Result.success(handle)
        } else {
            val detail = nativeGetLastError().ifBlank { "VoiceEngine nativeInit returned 0" }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun getResolvedLlamaLibPath(engineHandle: Long): Result<String> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        if (engineHandle == 0L) {
            return Result.failure(IllegalStateException("Invalid VoiceEngine handle (0)"))
        }
        return Result.success(nativeGetResolvedLlamaLibPath(engineHandle))
    }

    fun synthesizeClause(
        engineHandle: Long,
        normalizedText: String,
        prefixPcmPath: String? = null,
        seed: Long = 1001L,
        temperature: Float = 0.72f,
        topP: Float = 0.80f,
        topK: Int = 30,
        maxAudioTokens: Int = 2048
    ): Result<NativeCandidateTokens> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        if (engineHandle == 0L) {
            return Result.failure(IllegalStateException("Invalid VoiceEngine handle (0)"))
        }
        val candidate = nativeSynthesizeClause(
            engineHandle = engineHandle,
            normalizedText = normalizedText,
            prefixPcmPath = prefixPcmPath,
            seed = seed,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxAudioTokens = maxAudioTokens
        )
        return if (candidate != null) {
            Result.success(candidate)
        } else {
            val detail = nativeGetLastError().ifBlank { "VoiceEngine nativeSynthesizeClause failed" }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun hotSwapLora(engineHandle: Long, newLoraGgufPath: String): Result<Boolean> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        if (engineHandle == 0L) {
            return Result.failure(IllegalStateException("Invalid VoiceEngine handle (0)"))
        }
        val ok = nativeHotSwapLora(engineHandle, newLoraGgufPath)
        return if (ok) {
            Result.success(true)
        } else {
            val detail = nativeGetLastError().ifBlank { "VoiceEngine nativeHotSwapLora failed" }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun synthesizeTokenCandidates(
        engineHandle: Long,
        normalizedText: String,
        prefixPcmPath: String?,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE
    ): Result<List<NativeCandidateTokens>> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        if (engineHandle == 0L) {
            return Result.failure(IllegalStateException("Invalid VoiceEngine handle (0)"))
        }
        val candidates = nativeSynthesizeCandidates(
            engineHandle = engineHandle,
            normalizedText = normalizedText,
            prefixPcmPath = prefixPcmPath,
            numCandidates = numCandidates
        )
        return if (candidates != null) {
            Result.success(candidates.toList())
        } else {
            val detail = nativeGetLastError().ifBlank {
                "VoiceEngine nativeSynthesizeCandidates failed"
            }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun masterAndWritePcm(
        inputPcm: FloatArray,
        roomIrPcmPath: String? = null,
        roomTonePcmPath: String? = null,
        outputPcmPath: String? = null,
        targetLufs: Float = DEFAULT_TARGET_LUFS,
        noiseFloorDbfs: Float = DEFAULT_NOISE_FLOOR_DBFS
    ): Result<FloatArray> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        val mastered = nativeMasterAndWritePcm(
            inputPcm = inputPcm,
            roomIrPcmPath = roomIrPcmPath,
            roomTonePcmPath = roomTonePcmPath,
            outputPcmPath = outputPcmPath,
            targetLufs = targetLufs,
            noiseFloorDbfs = noiseFloorDbfs
        )
        return if (mastered != null) {
            Result.success(mastered)
        } else {
            val detail = nativeGetLastError().ifBlank { "nativeMasterAndWritePcm failed" }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun extractAcousticPrefixTail(
        inputPcm: FloatArray,
        tailSamples: Int = ACOUSTIC_PREFIX_TAIL_SAMPLES
    ): Result<FloatArray> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        return Result.success(nativeExtractAcousticPrefixTail(inputPcm, tailSamples))
    }

    fun findNearestZeroCrossing(
        pcm: FloatArray,
        targetSample: Long,
        scanRadius: Int = ZERO_CROSSING_SCAN_SAMPLES
    ): Result<Long> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        return Result.success(nativeFindNearestZeroCrossing(pcm, targetSample, scanRadius))
    }

    fun findNearestZeroCrossingInFile(
        pcmFilePath: String,
        targetSample: Long,
        scanRadius: Int = ZERO_CROSSING_SCAN_SAMPLES
    ): Result<Long> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        val idx = nativeFindNearestZeroCrossingInFile(pcmFilePath, targetSample, scanRadius)
        return if (idx >= 0L) {
            Result.success(idx)
        } else {
            val detail = nativeGetLastError().ifBlank {
                "Failed scanning zero crossing in $pcmFilePath"
            }
            Result.failure(IllegalStateException(detail))
        }
    }

    fun evictWeightPages(engineHandle: Long): Result<Long> {
        requireNativeLoaded().onFailure { return Result.failure(it) }
        if (engineHandle == 0L) {
            return Result.success(0L)
        }
        return Result.success(nativeEvictWeightPages(engineHandle))
    }

    fun destroyEngine(engineHandle: Long) {
        if (isNativeLibraryLoaded && engineHandle != 0L) {
            nativeDestroy(engineHandle)
        }
    }
}

/**
 * End-to-end Milestone 1 synthesis coordinator combining:
 *   1. Native C++20 `llama.cpp` GGUF + Rank-32 LoRA token rollout (`VoiceEngineJni`)
 *   2. LiteRT 2.3.0 `CompiledModel` 44.1 kHz DAC vocoder (`LiteRtCodecDecoder`)
 *   3. Native C++20 NEON 5ms DSP mastering (`VoiceEngineJni.masterAndWritePcm`)
 */
class VoiceEnginePipeline(
    private val codecDecoder: LiteRtCodecDecoder
) : AutoCloseable {

    @Volatile
    private var engineHandle: Long = 0L
    private var roomIrPath: String? = null
    private var roomTonePath: String? = null

    suspend fun initialize(
        baseGgufPath: String,
        loraGgufPath: String,
        vocoderTflitePath: String,
        roomIrPcmPath: String,
        roomTonePcmPath: String? = null,
        cacheVersion: String = "v1",
        preferredAccelerator: Accelerator = Accelerator.GPU,
        llamaLibPath: String? = null,
        nativeLibraryDir: String? = null
    ): Result<Long> {
        val decoderResult = codecDecoder.load(
            modelFile = File(vocoderTflitePath),
            cacheVersion = cacheVersion,
            preferredAccelerator = preferredAccelerator
        )
        decoderResult.onFailure { return Result.failure(it) }

        val nativeResult = VoiceEngineJni.initEngine(
            baseGgufPath = baseGgufPath,
            loraGgufPath = loraGgufPath,
            vocoderTflitePath = vocoderTflitePath,
            roomIrPcmPath = roomIrPcmPath,
            llamaLibPath = llamaLibPath,
            nativeLibraryDir = nativeLibraryDir
        )
        return nativeResult.onSuccess { handle ->
            val previous = engineHandle
            engineHandle = handle
            if (previous != 0L && previous != handle) {
                VoiceEngineJni.destroyEngine(previous)
            }
            roomIrPath = roomIrPcmPath.ifBlank { null }
            roomTonePath = roomTonePcmPath?.ifBlank { null }
        }.onFailure {
            codecDecoder.closeSession()
        }
    }

    fun hotSwapLora(newLoraGgufPath: String): Result<Boolean> =
        VoiceEngineJni.hotSwapLora(engineHandle, newLoraGgufPath)

    suspend fun synthesizeCandidates(
        normalizedText: String,
        prefixPcmPath: String?,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE
    ): Result<List<SynthesizedTakeOutput>> {
        val tokenCandidates = VoiceEngineJni.synthesizeTokenCandidates(
            engineHandle = engineHandle,
            normalizedText = normalizedText,
            prefixPcmPath = prefixPcmPath,
            numCandidates = numCandidates
        ).getOrElse { return Result.failure(it) }

        val outputs = ArrayList<SynthesizedTakeOutput>(tokenCandidates.size)
        for (candidate in tokenCandidates) {
            val decoded = codecDecoder.decode(candidate.discreteAudioTokens)
                .getOrElse { return Result.failure(it) }

            val mastered = VoiceEngineJni.masterAndWritePcm(
                inputPcm = decoded.pcm44k,
                roomIrPcmPath = roomIrPath,
                roomTonePcmPath = roomTonePath,
                outputPcmPath = null
            ).getOrElse { return Result.failure(it) }

            outputs += SynthesizedTakeOutput(
                discreteAudioTokens = candidate.discreteAudioTokens,
                pcm44k = mastered,
                seed = candidate.seed,
                temperature = candidate.temperature,
                vocoderLatencyMs = decoded.latencyMs,
                vocoderAccelerator = decoded.accelerator
            )
        }
        return Result.success(outputs)
    }

    suspend fun evictIdleMemory(): Result<Long> {
        codecDecoder.closeSession()
        return VoiceEngineJni.evictWeightPages(engineHandle)
    }

    override fun close() {
        val handle = engineHandle
        engineHandle = 0L
        VoiceEngineJni.destroyEngine(handle)
        codecDecoder.close()
    }
}
