package com.voicestudio.verifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

data class RawWordSpan(
    val word: String,
    val startSample: Long,
    val endSample: Long
)

data class CtcAlignmentOutput(
    val recognizedText: String,
    val maxPhonemeDurationMs: Float,
    val wordSpans: List<RawWordSpan>
)

interface CtcAlignerEngine : AutoCloseable {
    fun align(pcm44k: FloatArray, expectedText: String): Result<CtcAlignmentOutput>
}

interface SpeakerEmbeddingEngine : AutoCloseable {
    fun extractEmbedding(pcm44k: FloatArray): Result<FloatArray>
}

interface DnsmosP808Engine : AutoCloseable {
    fun scoreAudio(pcm44k: FloatArray): Result<Float>
}

/**
 * Production ONNX Runtime Android engine for WeSpeaker ResNet34 (`wespeaker_resnet34.onnx`, 25 MB).
 * Inspects the loaded model's input tensor rank to supply either 80-dim 16 kHz log-Mel Fbank
 * (`[1, T, 80]`) or raw 16 kHz PCM (`[1, N]`), returning the 256-float speaker embedding.
 */
class OrtWeSpeakerEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) : SpeakerEmbeddingEngine {

    override fun extractEmbedding(pcm44k: FloatArray): Result<FloatArray> = runCatching {
        require(pcm44k.isNotEmpty()) { "pcm44k must not be empty" }
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: error("WeSpeaker ONNX model exposes no inputs")
        val inputName = inputEntry.key
        val tensorInfo = inputEntry.value.info as? TensorInfo
            ?: error("WeSpeaker input '$inputName' is not a tensor")
        val shape = tensorInfo.shape

        val pcm16k = AudioDspMetrics.resample44kTo16k(pcm44k)
        val inputTensor = when (shape.size) {
            3 -> {
                val featDim = if (shape[2] > 0) shape[2].toInt() else 80
                val (fbank, numFrames) = AudioDspMetrics.computeLogMelFilterbank(
                    pcm16k = pcm16k,
                    numMelBins = featDim,
                    frameLengthSamples = 400,
                    frameShiftSamples = 160,
                    fftSize = 512,
                    fMinHz = 20.0f,
                    fMaxHz = 7600.0f,
                    applyCmn = true
                )
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(fbank),
                    longArrayOf(1L, numFrames.toLong(), featDim.toLong())
                )
            }
            2 -> OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(pcm16k),
                longArrayOf(1L, pcm16k.size.toLong())
            )
            else -> error("Unsupported WeSpeaker input tensor rank: ${shape.contentToString()}")
        }

        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result.get(0) as? OnnxTensor
                    ?: error("WeSpeaker output 0 is not an OnnxTensor")
                val fb = outTensor.floatBuffer
                val raw = FloatArray(fb.remaining())
                fb.get(raw)
                require(raw.size == AudioDspMetrics.SPEAKER_EMBEDDING_DIM) {
                    "Expected ${AudioDspMetrics.SPEAKER_EMBEDDING_DIM}-float WeSpeaker embedding, got ${raw.size}"
                }
                for (v in raw) {
                    require(v.isFinite()) { "WeSpeaker produced non-finite embedding value" }
                }
                raw
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        fun create(
            env: OrtEnvironment,
            modelFile: File,
            preferHardwareAccelerator: Boolean = true
        ): Result<OrtWeSpeakerEngine> = runCatching {
            require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
                "Missing or empty WeSpeaker ONNX model: ${modelFile.absolutePath}"
            }
            val session = createAcceleratedOrtSession(env, modelFile, preferHardwareAccelerator)
            OrtWeSpeakerEngine(env, session)
        }
    }
}

/**
 * Production ONNX Runtime Android engine for Microsoft DNSMOS p808 (`dnsmos_p808.onnx`, 4 MB, <15ms).
 * Supports both `[1, 900, 120]` official Microsoft DNS-Challenge 16 kHz log-Mel spectrogram
 * (`model_v8.onnx`, `n_fft = 321`, `hop = 160`, `n_mels = 120`, `(power_to_db(ref=max) + 40) / 40`)
 * and `[1, N]` 16 kHz waveform exports.
 */
class OrtDnsmosP808Engine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) : DnsmosP808Engine {

    override fun scoreAudio(pcm44k: FloatArray): Result<Float> = runCatching {
        require(pcm44k.isNotEmpty()) { "pcm44k must not be empty" }
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: error("DNSMOS p808 ONNX model exposes no inputs")
        val inputName = inputEntry.key
        val tensorInfo = inputEntry.value.info as? TensorInfo
            ?: error("DNSMOS input '$inputName' is not a tensor")
        val shape = tensorInfo.shape

        val pcm16k = AudioDspMetrics.resample44kTo16k(pcm44k)
        when (shape.size) {
            3 -> {
                val melBins = if (shape[2] > 0) shape[2].toInt() else AudioDspMetrics.DNSMOS_MEL_BINS
                val targetFrames = if (shape[1] > 0) shape[1].toInt() else AudioDspMetrics.DNSMOS_TARGET_FRAMES
                val windows = buildWindowedMelFeatures(
                    pcm16k = pcm16k,
                    targetFrames = targetFrames,
                    melBins = melBins
                )
                require(windows.isNotEmpty()) { "DNSMOS p808 produced zero evaluation windows" }
                var sumMos = 0.0
                for (feat in windows) {
                    val inputTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(feat),
                        longArrayOf(1L, targetFrames.toLong(), melBins.toLong())
                    )
                    inputTensor.use { tensor ->
                        session.run(mapOf(inputName to tensor)).use { result ->
                            val outTensor = result.get(0) as? OnnxTensor
                                ?: error("DNSMOS output 0 is not an OnnxTensor")
                            val fb = outTensor.floatBuffer
                            val scores = FloatArray(fb.remaining())
                            fb.get(scores)
                            require(scores.isNotEmpty()) { "DNSMOS p808 returned empty output tensor" }
                            val mos = if (scores.size >= 3) scores[2] else scores[0]
                            require(mos.isFinite()) { "DNSMOS p808 produced non-finite score" }
                            sumMos += mos.toDouble()
                        }
                    }
                }
                (sumMos / windows.size.toDouble()).toFloat()
            }
            2 -> {
                val targetLen = if (shape[1] > 0) shape[1].toInt() else pcm16k.size
                val wave = tileToMinLength(AudioDspMetrics.trimDigitalSilence(pcm16k), targetLen)
                    .let { if (it.size == targetLen) it else it.copyOfRange(0, targetLen) }
                val inputTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(wave),
                    longArrayOf(1L, targetLen.toLong())
                )
                inputTensor.use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val outTensor = result.get(0) as? OnnxTensor
                            ?: error("DNSMOS output 0 is not an OnnxTensor")
                        val fb = outTensor.floatBuffer
                        val scores = FloatArray(fb.remaining())
                        fb.get(scores)
                        require(scores.isNotEmpty()) { "DNSMOS p808 returned empty output tensor" }
                        // Single-output p808 returns MOS at [0]; 3-output [SIG, BAK, OVRL] returns OVRL at [2].
                        val mos = if (scores.size >= 3) scores[2] else scores[0]
                        require(mos.isFinite()) { "DNSMOS p808 produced non-finite score" }
                        mos
                    }
                }
            }
            else -> error("Unsupported DNSMOS p808 input tensor rank: ${shape.contentToString()}")
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val INPUT_LENGTH_SEC: Double = 9.01

        internal fun tileToMinLength(audio: FloatArray, minSamples: Int): FloatArray {
            if (audio.isEmpty() || audio.size >= minSamples) return audio
            val tiled = FloatArray(minSamples)
            var offset = 0
            while (offset < minSamples) {
                val copyLen = minOf(audio.size, minSamples - offset)
                audio.copyInto(tiled, destinationOffset = offset, startIndex = 0, endIndex = copyLen)
                offset += copyLen
            }
            return tiled
        }

        /**
         * Prepares official Microsoft DNS-Challenge 9.01s sliding windows (1.0s hop) of
         * `[targetFrames, melBins]` features (`n_fft = 321`, `hop = 160`, `n_mels = 120`,
         * `(power_to_db(ref = max) + 40.0) / 40.0`) matching `enroll_voice_assets.DnsmosP808Engine`.
         */
        internal fun buildWindowedMelFeatures(
            pcm16k: FloatArray,
            targetFrames: Int = AudioDspMetrics.DNSMOS_TARGET_FRAMES,
            melBins: Int = AudioDspMetrics.DNSMOS_MEL_BINS
        ): List<FloatArray> {
            require(pcm16k.isNotEmpty()) { "pcm16k must not be empty" }
            val inputSamples = AudioDspMetrics.DNSMOS_INPUT_SAMPLES
            val prepared = tileToMinLength(AudioDspMetrics.trimDigitalSilence(pcm16k), inputSamples)
            val hop = AudioDspMetrics.DNSMOS_SAMPLE_RATE_HZ
            val numHops = max(
                1,
                (floor(prepared.size.toDouble() / hop.toDouble()) - INPUT_LENGTH_SEC).toInt() + 1
            )
            val windows = ArrayList<FloatArray>(numHops)
            for (idx in 0 until numHops) {
                val start = idx * hop
                val end = start + inputSamples
                if (end > prepared.size) break
                // Match `seg[:-160]` (144,000 samples -> exactly 900 centered frames at hop=160).
                val seg = prepared.copyOfRange(start, end - AudioDspMetrics.DNSMOS_HOP_LENGTH)
                val (mel, numFrames) = AudioDspMetrics.computeDnsmosP808MelSpectrogram(
                    pcm16k = seg,
                    sampleRateHz = AudioDspMetrics.DNSMOS_SAMPLE_RATE_HZ,
                    fftSize = AudioDspMetrics.DNSMOS_FFT_SIZE,
                    hopLength = AudioDspMetrics.DNSMOS_HOP_LENGTH,
                    numMelBins = melBins
                )
                windows.add(fitFramesWithEdgePad(mel, numFrames, targetFrames, melBins))
            }
            return windows
        }

        private fun fitFramesWithEdgePad(
            mel: FloatArray,
            numFrames: Int,
            targetFrames: Int,
            melBins: Int
        ): FloatArray {
            if (numFrames == targetFrames) return mel
            val sized = FloatArray(targetFrames * melBins)
            if (numFrames <= 0) return sized
            val copyFrames = minOf(numFrames, targetFrames)
            mel.copyInto(sized, destinationOffset = 0, startIndex = 0, endIndex = copyFrames * melBins)
            if (copyFrames < targetFrames) {
                val lastRowStart = (copyFrames - 1) * melBins
                for (f in copyFrames until targetFrames) {
                    mel.copyInto(
                        destination = sized,
                        destinationOffset = f * melBins,
                        startIndex = lastRowStart,
                        endIndex = lastRowStart + melBins
                    )
                }
            }
            return sized
        }

        fun create(
            env: OrtEnvironment,
            modelFile: File,
            preferHardwareAccelerator: Boolean = true
        ): Result<OrtDnsmosP808Engine> = runCatching {
            require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
                "Missing or empty DNSMOS p808 ONNX model: ${modelFile.absolutePath}"
            }
            val session = createAcceleratedOrtSession(env, modelFile, preferHardwareAccelerator)
            OrtDnsmosP808Engine(env, session)
        }
    }
}

/**
 * Production ONNX Runtime CTC Forced Aligner engine reading a `sherpa-onnx` CTC directory
 * (`tokens.txt` + `*.onnx` CTC encoder). Runs real ONNX CTC frame posteriors followed by exact
 * CTC greedy decode (for Levenshtein CER) and CTC Viterbi forced alignment (for word spans &
 * `maxPhonemeDurationMs`).
 */
class OrtCtcAlignerEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val vocab: List<String>,
    private val tokenToId: Map<String, Int>,
    private val blankId: Int
) : CtcAlignerEngine {

    override fun align(pcm44k: FloatArray, expectedText: String): Result<CtcAlignmentOutput> = runCatching {
        require(pcm44k.isNotEmpty()) { "pcm44k must not be empty" }
        val pcm16k = AudioDspMetrics.resample44kTo16k(pcm44k)

        val inputs = linkedMapOf<String, OnnxTensor>()
        val createdTensors = mutableListOf<OnnxTensor>()
        try {
            val inputEntries = session.inputInfo.entries.toList()
            require(inputEntries.isNotEmpty()) { "CTC ONNX model exposes no inputs" }

            val primaryEntry = inputEntries[0]
            val primaryInfo = primaryEntry.value.info as? TensorInfo
                ?: error("CTC primary input is not a tensor")
            val shape = primaryInfo.shape
            var numInputFrames = pcm16k.size

            val primaryTensor = when (shape.size) {
                3 -> {
                    val isChannelsFirstNemo = shape[1] > 0L && shape[2] <= 0L
                    val featDim = when {
                        isChannelsFirstNemo -> shape[1].toInt()
                        shape[2] > 0L -> shape[2].toInt()
                        else -> 80
                    }
                    val (tensorData, frames) = buildCtcFeatureTensorData(
                        pcm16k = pcm16k,
                        featDim = featDim,
                        isChannelsFirstNemo = isChannelsFirstNemo
                    )
                    numInputFrames = frames
                    if (isChannelsFirstNemo) {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(tensorData),
                            longArrayOf(1L, featDim.toLong(), frames.toLong())
                        )
                    } else {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(tensorData),
                            longArrayOf(1L, frames.toLong(), featDim.toLong())
                        )
                    }
                }
                2 -> OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(pcm16k),
                    longArrayOf(1L, pcm16k.size.toLong())
                )
                else -> error("Unsupported CTC input rank: ${shape.contentToString()}")
            }
            createdTensors.add(primaryTensor)
            inputs[primaryEntry.key] = primaryTensor

            if (inputEntries.size >= 2) {
                val lenEntry = inputEntries[1]
                val lenTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(numInputFrames.toLong())),
                    longArrayOf(1L)
                )
                createdTensors.add(lenTensor)
                inputs[lenEntry.key] = lenTensor
            }

            session.run(inputs).use { result ->
                val outTensor = result.get(0) as? OnnxTensor
                    ?: error("CTC output 0 is not an OnnxTensor")
                val outShape = (outTensor.info as TensorInfo).shape
                require(outShape.size == 3 && outShape[0] >= 1L) {
                    "Expected CTC logits shape [B, T, V], got ${outShape.contentToString()}"
                }
                val numFrames = outShape[1].toInt()
                val vocabSize = outShape[2].toInt()
                require(numFrames > 0 && vocabSize >= vocab.size) {
                    "CTC output dimensions ($numFrames x $vocabSize) incompatible with vocab size ${vocab.size}"
                }

                val fb = outTensor.floatBuffer
                val rawLogits = FloatArray(numFrames * vocabSize)
                fb.get(rawLogits)
                val logProbs = toRowLogSoftmax(rawLogits, numFrames, vocabSize)
                val frameShiftSamples = max(1, pcm44k.size / numFrames)

                val greedy = AudioDspMetrics.ctcGreedyDecode(
                    logProbs = logProbs,
                    numFrames = numFrames,
                    vocabSize = vocabSize,
                    blankId = blankId,
                    vocab = vocab,
                    frameShiftSamples = frameShiftSamples
                )

                val targetIds = encodeTextToTokenIds(expectedText)
                val viterbi = if (targetIds.isNotEmpty() && numFrames >= targetIds.size) {
                    AudioDspMetrics.ctcViterbiForceAlign(
                        logProbs = logProbs,
                        numFrames = numFrames,
                        vocabSize = vocabSize,
                        blankId = blankId,
                        targetTokenIds = targetIds,
                        vocab = vocab,
                        frameShiftSamples = frameShiftSamples,
                        totalSamples = pcm44k.size.toLong()
                    )
                } else {
                    null
                }

                CtcAlignmentOutput(
                    recognizedText = greedy.decodedText,
                    maxPhonemeDurationMs = viterbi?.maxPhonemeDurationMs ?: greedy.maxPhonemeDurationMs,
                    wordSpans = viterbi?.wordSpans ?: emptyList()
                )
            }
        } finally {
            createdTensors.forEach { runCatching { it.close() } }
        }
    }

    private fun encodeTextToTokenIds(expectedText: String): IntArray {
        val norm = AudioDspMetrics.normalizeTranscript(expectedText)
        if (norm.isEmpty()) return IntArray(0)
        val hasSentencePiecePieces = tokenToId.keys.any { it.startsWith("▁") && it.length > 1 }
        if (hasSentencePiecePieces) {
            val ids = mutableListOf<Int>()
            for (word in norm.split(' ')) {
                if (word.isEmpty()) continue
                val prefixed = "▁$word"
                var pos = 0
                while (pos < prefixed.length) {
                    var matchedId: Int? = null
                    var matchedEnd = pos
                    for (end in prefixed.length downTo pos + 1) {
                        val sub = prefixed.substring(pos, end)
                        val id = tokenToId[sub] ?: tokenToId[sub.uppercase()]
                        if (id != null && id != blankId) {
                            matchedId = id
                            matchedEnd = end
                            break
                        }
                    }
                    if (matchedId != null) {
                        ids.add(matchedId)
                        pos = matchedEnd
                    } else {
                        pos += 1
                    }
                }
            }
            if (ids.isNotEmpty()) return ids.toIntArray()
        }
        val spaceId = tokenToId["|"] ?: tokenToId[" "] ?: tokenToId["▁"]
        val ids = mutableListOf<Int>()
        for (ch in norm) {
            val id = if (ch == ' ') {
                spaceId
            } else {
                tokenToId[ch.toString()] ?: tokenToId[ch.uppercaseChar().toString()]
            }
            if (id != null && id != blankId) {
                ids.add(id)
            }
        }
        return ids.toIntArray()
    }

    private fun toRowLogSoftmax(logits: FloatArray, rows: Int, cols: Int): FloatArray {
        val out = FloatArray(logits.size)
        for (r in 0 until rows) {
            val base = r * cols
            var maxVal = Float.NEGATIVE_INFINITY
            for (c in 0 until cols) {
                val v = logits[base + c]
                if (v > maxVal) maxVal = v
            }
            var sumExp = 0.0
            for (c in 0 until cols) {
                sumExp += exp((logits[base + c] - maxVal).toDouble())
            }
            val logZ = maxVal.toDouble() + ln(max(sumExp, 1e-12))
            for (c in 0 until cols) {
                out[base + c] = (logits[base + c].toDouble() - logZ).toFloat()
            }
        }
        return out
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /**
         * Builds per-feature CMVN-normalized (`(x - mean) / (std + 1e-5)`) log-Mel features
         * matching NeMo Citrinet-512 (`normalize_type: per_feature`). When `isChannelsFirstNemo`
         * is true, transposes `[T, C]` to `[C, T]` for `[1, 80, T]` NeMo ONNX input.
         */
        internal fun buildCtcFeatureTensorData(
            pcm16k: FloatArray,
            featDim: Int = 80,
            isChannelsFirstNemo: Boolean = true
        ): Pair<FloatArray, Int> {
            val (fbank, frames) = AudioDspMetrics.computeLogMelFilterbank(
                pcm16k = pcm16k,
                numMelBins = featDim,
                frameLengthSamples = 400,
                frameShiftSamples = 160,
                fftSize = 512,
                fMinHz = 20.0f,
                fMaxHz = 7600.0f,
                applyCmn = true,
                applyCmvn = true
            )
            if (!isChannelsFirstNemo) return fbank to frames
            // NeMo Citrinet / Conformer CTC expects [B, featDim, T].
            val transposed = FloatArray(featDim * frames)
            for (t in 0 until frames) {
                val srcBase = t * featDim
                for (f in 0 until featDim) {
                    transposed[f * frames + t] = fbank[srcBase + f]
                }
            }
            return transposed to frames
        }

        fun validateModelDir(dir: File): Result<Pair<File, File>> = runCatching {
            require(dir.isDirectory && dir.canRead()) {
                "Missing sherpa_ctc_model_dir directory: ${dir.absolutePath}"
            }
            val tokensFile = File(dir, "tokens.txt")
            require(tokensFile.isFile && tokensFile.canRead() && tokensFile.length() > 0L) {
                "Missing tokens.txt in sherpa_ctc_model_dir: ${dir.absolutePath}"
            }
            val preferredNames = listOf("model.int8.onnx", "model.onnx", "ctc.onnx")
            val onnxFile = preferredNames.map { File(dir, it) }
                .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                ?: dir.listFiles()
                    ?.sortedBy { it.name }
                    ?.firstOrNull { it.isFile && it.name.endsWith(".onnx") && it.length() > 0L }
                ?: throw IllegalArgumentException(
                    "No non-empty .onnx CTC model found in sherpa_ctc_model_dir: ${dir.absolutePath}"
                )
            onnxFile to tokensFile
        }

        fun loadTokens(tokensFile: File): Triple<List<String>, Map<String, Int>, Int> {
            val entries = mutableListOf<Pair<Int, String>>()
            tokensFile.readLines().forEachIndexed { lineIndex, rawLine ->
                val line = rawLine.trimEnd()
                if (line.isEmpty()) return@forEachIndexed
                val lastSpace = line.lastIndexOf(' ')
                if (lastSpace > 0) {
                    val maybeId = line.substring(lastSpace + 1).toIntOrNull()
                    if (maybeId != null && maybeId >= 0) {
                        entries.add(maybeId to line.substring(0, lastSpace))
                        return@forEachIndexed
                    }
                }
                entries.add(lineIndex to line)
            }
            require(entries.isNotEmpty()) { "tokens.txt contains no vocabulary tokens" }
            val maxId = entries.maxOf { it.first }
            val vocab = MutableList(maxId + 1) { "" }
            val tokenToId = linkedMapOf<String, Int>()
            var blankId = 0
            for ((id, token) in entries) {
                vocab[id] = token
                tokenToId[token] = id
                if (token == "<blk>" || token == "<blank>" || token == "<eps>") {
                    blankId = id
                }
            }
            return Triple(vocab, tokenToId, blankId)
        }

        fun create(
            env: OrtEnvironment,
            sherpaCtcModelDir: File,
            preferHardwareAccelerator: Boolean = true
        ): Result<OrtCtcAlignerEngine> = runCatching {
            val (onnxFile, tokensFile) = validateModelDir(sherpaCtcModelDir).getOrThrow()
            val (vocab, tokenToId, blankId) = loadTokens(tokensFile)
            val session = createAcceleratedOrtSession(env, onnxFile, preferHardwareAccelerator)
            OrtCtcAlignerEngine(env, session, vocab, tokenToId, blankId)
        }
    }
}

internal fun createAcceleratedOrtSession(
    env: OrtEnvironment,
    modelFile: File,
    preferHardwareAccelerator: Boolean
): OrtSession {
    if (preferHardwareAccelerator) {
        val hwAttempt = runCatching {
            val hwOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addNnapi()
            }
            hwOpts.use { options -> env.createSession(modelFile.absolutePath, options) }
        }
        if (hwAttempt.isSuccess) {
            return hwAttempt.getOrThrow()
        }
    }
    val cpuOpts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    return cpuOpts.use { options -> env.createSession(modelFile.absolutePath, options) }
}
