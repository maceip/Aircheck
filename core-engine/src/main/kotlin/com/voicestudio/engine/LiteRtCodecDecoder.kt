package com.voicestudio.engine

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Output of a single LiteRT 44.1 kHz DAC vocoder decode pass.
 * [latencyMs] measures `model.run` AND `outputs[0].readFloat()` together synchronously.
 */
data class DacDecodeResult(
    val pcm44k: FloatArray,
    val latencyMs: Double,
    val accelerator: Accelerator
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DacDecodeResult) return false
        return pcm44k.contentEquals(other.pcm44k) &&
            latencyMs == other.latencyMs &&
            accelerator == other.accelerator
    }

    override fun hashCode(): Int {
        var result = pcm44k.contentHashCode()
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + accelerator.hashCode()
        return result
    }
}

/**
 * LiteRT 2.3.0 `CompiledModel` vocoder decoding discrete s2-pro audio codebook tokens to
 * 44.1 kHz Float32 mono PCM via OTA-synced `codec_decoder_fp16.tflite`.
 *
 * Strictly obeys all `litert-runtime` mandates:
 * - Bundled `com.google.ai.edge.litert:litert:2.3.0` + `litert-gpu:2.3.0` CompiledModel API only.
 * - Single-thread confined background executor owns creation, warm-up, inference, and closing.
 * - Reusable `TensorBuffer`s allocated once and closed strictly BEFORE `CompiledModel.close()`.
 * - GPU-to-CPU fallback with `xnnPackFlags = 0x23` and versioned `xnnPackWeightCachePath` in `codeCacheDir`.
 * - Warm-up inference inside session init with full exception-safe cleanup on failure.
 * - Constitutional Law 2: fails honestly when `codec_decoder_fp16.tflite` is missing or invalid.
 */
class LiteRtCodecDecoder(
    private val codeCacheDir: File,
    private val cpuThreads: Int = DEFAULT_CPU_THREADS
) : AutoCloseable {

    constructor(context: Context, cpuThreads: Int = DEFAULT_CPU_THREADS) : this(
        codeCacheDir = context.codeCacheDir,
        cpuThreads = cpuThreads
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voicestudio-litert-dac").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)

    // Owned exclusively by `executor` (single-thread confinement).
    private var activeSession: CompiledDacSession? = null
    private var loadedModelPath: String? = null
    private var loadedCacheVersion: String? = null

    @Volatile
    var activeAccelerator: Accelerator? = null
        private set

    val isLoaded: Boolean
        get() = activeAccelerator != null

    /**
     * Loads `codec_decoder_fp16.tflite` from disk on the confined single-thread executor,
     * attempting [preferredAccelerator] first and falling back cleanly to `Accelerator.CPU`
     * with `xnnPackFlags = 0x23` and versioned `xnnPackWeightCachePath`.
     */
    suspend fun load(
        modelFile: File,
        cacheVersion: String = "v1",
        preferredAccelerator: Accelerator = Accelerator.GPU
    ): Result<Accelerator> = withContext(dispatcher) {
        val validation = validateTfliteModelFile(modelFile)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        if (activeSession != null &&
            loadedModelPath == modelFile.absolutePath &&
            loadedCacheVersion == cacheVersion
        ) {
            return@withContext Result.success(activeSession!!.accelerator)
        }

        closeSessionInternal()

        val cacheFile = prepareVersionedXnnPackCacheFile(
            cacheDir = codeCacheDir,
            modelName = modelFile.name,
            cacheVersion = cacheVersion
        )

        val attemptOrder = if (preferredAccelerator == Accelerator.CPU) {
            listOf(Accelerator.CPU)
        } else {
            listOf(preferredAccelerator, Accelerator.CPU)
        }

        var lastError: Throwable? = null
        for (accel in attemptOrder) {
            try {
                val options = buildCompiledModelOptions(
                    accelerator = accel,
                    cpuThreads = cpuThreads,
                    xnnPackWeightCacheFile = cacheFile
                )
                val session = CompiledDacSession(
                    modelPath = modelFile.absolutePath,
                    accelerator = accel,
                    options = options,
                    environment = sharedEnvironment
                )
                activeSession = session
                loadedModelPath = modelFile.absolutePath
                loadedCacheVersion = cacheVersion
                activeAccelerator = accel
                return@withContext Result.success(accel)
            } catch (e: LiteRtException) {
                lastError = e
            } catch (e: UnsatisfiedLinkError) {
                lastError = e
                break
            } catch (e: RuntimeException) {
                lastError = e
            }
        }

        Result.failure(
            IllegalStateException(
                "Failed to compile LiteRT DAC vocoder (${modelFile.name}): ${lastError?.message ?: "unknown error"}",
                lastError
            )
        )
    }

    /**
     * Decodes discrete s2-pro audio codebook token IDs into 44.1 kHz Float32 mono PCM on the
     * confined single-thread executor, reusing allocated [TensorBuffer] instances.
     */
    suspend fun decode(discreteAudioTokens: IntArray): Result<DacDecodeResult> =
        withContext(dispatcher) {
            if (discreteAudioTokens.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("discreteAudioTokens must not be empty")
                )
            }
            val session = activeSession
                ?: return@withContext Result.failure(
                    IllegalStateException("LiteRtCodecDecoder is not loaded; sync OTA codec_decoder_fp16.tflite first")
                )
            try {
                Result.success(session.decode(discreteAudioTokens))
            } catch (e: LiteRtException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }
        }

    /**
     * Releases the active `CompiledModel` and its `TensorBuffer`s while keeping the executor ready
     * for the next synthesis batch (frees runtime workspace during idle periods).
     */
    suspend fun closeSession() = withContext(dispatcher) {
        closeSessionInternal()
    }

    private fun closeSessionInternal() {
        activeSession?.close()
        activeSession = null
        loadedModelPath = null
        loadedCacheVersion = null
        activeAccelerator = null
    }

    override fun close() {
        cleanupScope.launch {
            closeSessionInternal()
        }.invokeOnCompletion {
            cleanupScope.cancel()
            executor.shutdown()
        }
    }

    /**
     * Single-thread-owned LiteRT session holding one [CompiledModel] and reusable [TensorBuffer]s.
     * Closes every input and output [TensorBuffer] BEFORE calling [CompiledModel.close], including
     * when buffer creation or warm-up throws inside `init`.
     */
    private class CompiledDacSession(
        modelPath: String,
        val accelerator: Accelerator,
        options: CompiledModel.Options,
        environment: Environment
    ) : AutoCloseable {
        private val model: CompiledModel = CompiledModel.create(modelPath, options, environment)
        private val inputs = mutableListOf<TensorBuffer>()
        private val outputs = mutableListOf<TensorBuffer>()
        private val inputElementType: TensorType.ElementType
        private val fixedInputElementCount: Int

        init {
            try {
                inputs += model.createInputBuffers()
                outputs += model.createOutputBuffers()
                check(inputs.isNotEmpty() && outputs.isNotEmpty()) {
                    "LiteRT DAC vocoder must declare at least one input and one output tensor"
                }
                val resolvedSpec = inspectInputSpec()
                inputElementType = resolvedSpec.first
                fixedInputElementCount = resolvedSpec.second

                // Mandatory warm-up inference inside constructor so GPU shader compilation
                // does not stall the first synthesis call.
                val warmupCount = if (fixedInputElementCount > 0) {
                    fixedInputElementCount
                } else {
                    DEFAULT_WARMUP_TOKEN_COUNT
                }
                runOnce(IntArray(warmupCount))
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        private fun inspectInputSpec(): Pair<TensorType.ElementType, Int> {
            val candidateNames = listOf("tokens", "codes", "codebook_tokens", "input_0", "serving_default_tokens:0")
            for (name in candidateNames) {
                try {
                    val tensorType = model.getInputTensorType(name, DEFAULT_SIGNATURE_KEY)
                    val dims = tensorType.layout?.dimensions.orEmpty()
                    val staticCount = if (dims.isNotEmpty() && dims.all { it > 0 }) {
                        dims.fold(1) { acc, d -> acc * d }
                    } else {
                        0
                    }
                    return tensorType.elementType to staticCount
                } catch (_: Throwable) {
                    // Try next common signature input tensor name.
                }
            }
            return TensorType.ElementType.INT to 0
        }

        fun decode(discreteAudioTokens: IntArray): DacDecodeResult {
            val startNs = System.nanoTime()
            val pcm = if (fixedInputElementCount > 0 && discreteAudioTokens.size != fixedInputElementCount) {
                decodeChunked(discreteAudioTokens, fixedInputElementCount)
            } else {
                runOnce(discreteAudioTokens)
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            return DacDecodeResult(
                pcm44k = pcm,
                latencyMs = elapsedMs,
                accelerator = accelerator
            )
        }

        private fun decodeChunked(tokens: IntArray, chunkSize: Int): FloatArray {
            val chunks = ArrayList<FloatArray>()
            var totalSamples = 0
            var offset = 0
            while (offset < tokens.size) {
                val validCount = minOf(chunkSize, tokens.size - offset)
                val padded = IntArray(chunkSize)
                System.arraycopy(tokens, offset, padded, 0, validCount)
                val fullOut = runOnce(padded)
                val trimmed = if (validCount < chunkSize && fullOut.isNotEmpty()) {
                    val proportional = ((fullOut.size.toLong() * validCount) / chunkSize)
                        .toInt()
                        .coerceIn(1, fullOut.size)
                    fullOut.copyOf(proportional)
                } else {
                    fullOut
                }
                chunks += trimmed
                totalSamples += trimmed.size
                offset += validCount
            }
            val merged = FloatArray(totalSamples)
            var cursor = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, merged, cursor, chunk.size)
                cursor += chunk.size
            }
            return merged
        }

        private fun runOnce(tokens: IntArray): FloatArray {
            when (inputElementType) {
                TensorType.ElementType.INT -> inputs[0].writeInt(tokens)
                TensorType.ElementType.INT64 -> {
                    val longs = LongArray(tokens.size) { i -> tokens[i].toLong() }
                    inputs[0].writeLong(longs)
                }
                TensorType.ElementType.FLOAT -> {
                    val floats = FloatArray(tokens.size) { i -> tokens[i].toFloat() }
                    inputs[0].writeFloat(floats)
                }
                else -> inputs[0].writeInt(tokens)
            }
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        override fun close() {
            for (buf in inputs) {
                runCatching { buf.close() }
            }
            for (buf in outputs) {
                runCatching { buf.close() }
            }
            inputs.clear()
            outputs.clear()
            runCatching { model.close() }
        }
    }

    companion object {
        const val DEFAULT_CPU_THREADS: Int = 4
        const val XNNPACK_FLAGS_TRANSIENT_INDIRECTION: Int = 0x23
        private const val DEFAULT_SIGNATURE_KEY: String = "serving_default"
        private const val DEFAULT_WARMUP_TOKEN_COUNT: Int = 16
        private const val MIN_TFLITE_BYTES: Long = 16L

        // One process-wide Environment serves every LiteRT CompiledModel so the GPU accelerator
        // library is loaded once rather than on every create() call.
        private val sharedEnvironment: Environment by lazy { Environment.create() }

        /**
         * Validates that [modelFile] exists on disk and carries the FlatBuffers `TFL3` magic bytes.
         */
        fun validateTfliteModelFile(modelFile: File): Result<Unit> {
            if (!modelFile.exists() || !modelFile.isFile || !modelFile.canRead()) {
                return Result.failure(
                    IllegalStateException(
                        "Missing or unreadable LiteRT DAC model file: ${modelFile.path}"
                    )
                )
            }
            if (modelFile.length() < MIN_TFLITE_BYTES) {
                return Result.failure(
                    IllegalStateException(
                        "LiteRT DAC model file is truncated (${modelFile.length()} bytes): ${modelFile.path}"
                    )
                )
            }
            val header = ByteArray(8)
            RandomAccessFile(modelFile, "r").use { raf ->
                val read = raf.read(header)
                if (read < 8) {
                    return Result.failure(
                        IllegalStateException("Failed reading LiteRT header: ${modelFile.path}")
                    )
                }
            }
            val magic = String(header, 4, 4, Charsets.US_ASCII)
            if (magic != "TFL3") {
                return Result.failure(
                    IllegalStateException(
                        "Invalid LiteRT flatbuffer identifier '$magic' (expected 'TFL3'): ${modelFile.path}"
                    )
                )
            }
            return Result.success(Unit)
        }

        /**
         * Returns a versioned XNNPACK weight cache path under [cacheDir] and deletes stale versions.
         */
        fun prepareVersionedXnnPackCacheFile(
            cacheDir: File,
            modelName: String,
            cacheVersion: String
        ): File {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val safeVersion = cacheVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val prefix = "$modelName-"
            val suffix = ".xnnpack_cache"
            val target = File(cacheDir, "$prefix$safeVersion$suffix")
            cacheDir.listFiles()?.forEach { existing ->
                if (existing.isFile &&
                    existing.name.startsWith(prefix) &&
                    existing.name.endsWith(suffix) &&
                    existing.absolutePath != target.absolutePath
                ) {
                    existing.delete()
                }
            }
            return target
        }

        /**
         * Builds LiteRT [CompiledModel.Options] adhering to `litert-runtime` memory rules:
         * `xnnPackFlags = 0x23` (default `0x3` + `0x20` transient indirection buffer) and
         * versioned `xnnPackWeightCachePath` for CPU execution.
         */
        fun buildCompiledModelOptions(
            accelerator: Accelerator,
            cpuThreads: Int = DEFAULT_CPU_THREADS,
            xnnPackWeightCacheFile: File
        ): CompiledModel.Options = CompiledModel.Options(accelerator).apply {
            if (accelerator == Accelerator.CPU) {
                cpuOptions = CompiledModel.CpuOptions(
                    numThreads = cpuThreads.coerceIn(1, 8),
                    xnnPackFlags = XNNPACK_FLAGS_TRANSIENT_INDIRECTION,
                    xnnPackWeightCachePath = xnnPackWeightCacheFile.absolutePath
                )
            }
        }
    }
}
