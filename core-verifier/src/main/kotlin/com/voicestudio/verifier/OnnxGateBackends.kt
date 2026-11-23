package com.voicestudio.verifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
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
        fun create(env: OrtEnvironment, modelFile: File): Result<OrtWeSpeakerEngine> = runCatching {
            require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
                "Missing or empty WeSpeaker ONNX model: ${modelFile.absolutePath}"
            }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            opts.use { options ->
                val session = env.createSession(modelFile.absolutePath, options)
                OrtWeSpeakerEngine(env, session)
            }
        }
    }
}

/**
 * Production ONNX Runtime Android engine for Microsoft DNSMOS p808 (`dnsmos_p808.onnx`, 4 MB, <15ms).
 * Supports both `[1, T, 120]` 16 kHz log-Mel spectrogram (`model_v8.onnx`) and `[1, N]` 16 kHz
 * waveform exports.
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
        val inputTensor = when (shape.size) {
            3 -> {
                val melBins = if (shape[2] > 0) shape[2].toInt() else 120
                val (mel, numFrames) = AudioDspMetrics.computeLogMelFilterbank(
                    pcm16k = pcm16k,
                    numMelBins = melBins,
                    frameLengthSamples = 320,
                    frameShiftSamples = 160,
                    fftSize = 512,
                    fMinHz = 0.0f,
                    fMaxHz = 8000.0f,
                    applyCmn = false
                )
                val targetFrames = if (shape[1] > 0) shape[1].toInt() else numFrames
                val finalMel = if (targetFrames == numFrames) {
                    mel
                } else {
                    val sized = FloatArray(targetFrames * melBins)
                    if (numFrames > 0) {
                        var dstFrame = 0
                        while (dstFrame < targetFrames) {
                            val copyFrames = minOf(numFrames, targetFrames - dstFrame)
                            mel.copyInto(
                                destination = sized,
                                destinationOffset = dstFrame * melBins,
                                startIndex = 0,
                                endIndex = copyFrames * melBins
                            )
                            dstFrame += copyFrames
                        }
                    }
                    sized
                }
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(finalMel),
                    longArrayOf(1L, targetFrames.toLong(), melBins.toLong())
                )
            }
            2 -> {
                val targetLen = if (shape[1] > 0) shape[1].toInt() else pcm16k.size
                val wave = FloatArray(targetLen)
                if (pcm16k.isNotEmpty()) {
                    var offset = 0
                    while (offset < targetLen) {
                        val copyLen = minOf(pcm16k.size, targetLen - offset)
                        pcm16k.copyInto(wave, destinationOffset = offset, startIndex = 0, endIndex = copyLen)
                        offset += copyLen
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(wave), longArrayOf(1L, targetLen.toLong()))
            }
            else -> error("Unsupported DNSMOS p808 input tensor rank: ${shape.contentToString()}")
        }

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

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        fun create(env: OrtEnvironment, modelFile: File): Result<OrtDnsmosP808Engine> = runCatching {
            require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
                "Missing or empty DNSMOS p808 ONNX model: ${modelFile.absolutePath}"
            }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            opts.use { options ->
                val session = env.createSession(modelFile.absolutePath, options)
                OrtDnsmosP808Engine(env, session)
            }
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
                    val (fbank, frames) = AudioDspMetrics.computeLogMelFilterbank(
                        pcm16k = pcm16k,
                        numMelBins = featDim,
                        frameLengthSamples = 400,
                        frameShiftSamples = 160,
                        fftSize = 512,
                        fMinHz = 20.0f,
                        fMaxHz = 7600.0f,
                        applyCmn = true
                    )
                    numInputFrames = frames
                    if (isChannelsFirstNemo) {
                        // NeMo Citrinet / Conformer CTC expects [B, featDim, T].
                        val transposed = FloatArray(featDim * frames)
                        for (t in 0 until frames) {
                            val srcBase = t * featDim
                            for (f in 0 until featDim) {
                                transposed[f * frames + t] = fbank[srcBase + f]
                            }
                        }
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(transposed),
                            longArrayOf(1L, featDim.toLong(), frames.toLong())
                        )
                    } else {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(fbank),
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

        fun create(env: OrtEnvironment, sherpaCtcModelDir: File): Result<OrtCtcAlignerEngine> = runCatching {
            val (onnxFile, tokensFile) = validateModelDir(sherpaCtcModelDir).getOrThrow()
            val (vocab, tokenToId, blankId) = loadTokens(tokensFile)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            opts.use { options ->
                val session = env.createSession(onnxFile.absolutePath, options)
                OrtCtcAlignerEngine(env, session, vocab, tokenToId, blankId)
            }
        }
    }
}
