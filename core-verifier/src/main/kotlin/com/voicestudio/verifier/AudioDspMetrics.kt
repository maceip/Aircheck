package com.voicestudio.verifier

import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.WordToken
import com.voicestudio.edl.ZERO_CROSSING_SCAN_SAMPLES
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic signal-processing, CTC alignment, centroid loading, and 64-byte-aligned PCM I/O
 * primitives mirroring `core-verifier/src/main/cpp/verifier_gates.cc`.
 *
 * Constitutional Law 2: Contains strictly real mathematical DSP/string/binary operations — ZERO
 * synthetic or fabricated ML scores.
 */
object AudioDspMetrics {
    const val TAIL_WINDOW_MS: Int = 80
    const val SPEAKER_EMBEDDING_DIM: Int = 256
    const val SPEAKER_CENTROID_BYTES: Long = SPEAKER_EMBEDDING_DIM * 4L
    const val PCM_ALIGNMENT_BYTES: Int = 64
    const val MAX_TAIL_HF_SPECTRAL_FLUX: Float = 0.008f
    const val MAX_TAIL_HF_ENERGY_DB: Float = -48.0f
    const val MAX_TAIL_PEAK_FRAME_RMS_DB: Float = -38.0f

    data class TailAnalysisResult(
        val tailRmsDb: Float,
        val tailPeakFrameRmsDb: Float,
        val tailHfEnergyDb: Float,
        val tailHfSpectralFlux: Float,
        val hfGlitchDetected: Boolean
    )

    data class CtcGreedyResult(
        val decodedText: String,
        val maxPhonemeDurationMs: Float
    )

    data class CtcViterbiResult(
        val wordSpans: List<RawWordSpan>,
        val maxPhonemeDurationMs: Float
    )

    fun normalizeTranscript(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = true
        for (i in text.indices) {
            val ch = text[i]
            when {
                ch.isLetterOrDigit() -> {
                    sb.append(ch.lowercaseChar())
                    lastWasSpace = false
                }
                ch == '\'' && sb.isNotEmpty() && !lastWasSpace &&
                    i + 1 < text.length && text[i + 1].isLetter() -> {
                    sb.append('\'')
                    lastWasSpace = false
                }
                ch.isWhitespace() || ch == '-' -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
            }
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    fun computeCer(expectedText: String, recognizedText: String): Float {
        val ref = normalizeTranscript(expectedText)
        val hyp = normalizeTranscript(recognizedText)
        if (ref.isEmpty()) {
            return if (hyp.isEmpty()) 0.0f else 1.0f
        }
        if (ref == hyp) {
            return 0.0f
        }

        val n = ref.length
        val m = hyp.length
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            val rc = ref[i - 1]
            for (j in 1..m) {
                val cost = if (rc == hyp[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                val substitution = prev[j - 1] + cost
                curr[j] = min(deletion, min(insertion, substitution))
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return (prev[m].toDouble() / n.toDouble()).toFloat()
    }

    private fun isAsciiVowel(c: Char): Boolean =
        c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'

    fun countWordSyllables(word: String): Int {
        val clean = buildString(word.length) {
            for (ch in word) {
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    append(ch.lowercaseChar())
                }
            }
        }
        if (clean.isEmpty()) return 0
        if (clean.length <= 2) return 1

        val endsWithConsonantLe =
            clean.length >= 3 &&
                clean[clean.length - 2] == 'l' &&
                clean[clean.length - 1] == 'e' &&
                !isAsciiVowel(clean[clean.length - 3])

        val stem = when {
            clean.length > 3 && clean.endsWith("ed") -> {
                val before = clean[clean.length - 3]
                if (before != 't' && before != 'd') clean.dropLast(2) else clean
            }
            clean.length > 3 && clean.endsWith("es") -> {
                val before = clean[clean.length - 3]
                val sibilant =
                    before == 's' || before == 'x' || before == 'z' || before == 'c' || before == 'g' ||
                        (clean.length > 4 && before == 'h' &&
                            (clean[clean.length - 4] == 'c' || clean[clean.length - 4] == 's'))
                if (!sibilant) clean.dropLast(2) else clean
            }
            clean.length > 2 && clean.endsWith('e') && !endsWithConsonantLe -> clean.dropLast(1)
            else -> clean
        }

        var syllables = 0
        var prevVowel = false
        for (ch in stem) {
            val isVowel = isAsciiVowel(ch)
            if (isVowel && !prevVowel) {
                syllables++
            }
            prevVowel = isVowel
        }

        for (i in 0 until stem.length - 1) {
            val a = stem[i]
            val b = stem[i + 1]
            val isHiatus =
                (a == 'i' && (b == 'a' || b == 'o' || b == 'u')) ||
                    (a == 'e' && (b == 'o' || (b == 'a' && i + 2 == stem.length))) ||
                    (a == 'u' && b == 'a')
            if (!isHiatus) continue
            val isTionSionCious =
                i >= 1 && (stem[i - 1] == 't' || stem[i - 1] == 's' || stem[i - 1] == 'c') &&
                    a == 'i' && b == 'o'
            if (!isTionSionCious) {
                syllables++
            }
        }

        return max(1, syllables)
    }

    fun countTranscriptSyllables(text: String): Int {
        val norm = normalizeTranscript(text)
        if (norm.isEmpty()) return 0
        var total = 0
        for (token in norm.split(' ')) {
            if (token.isNotEmpty()) {
                total += countWordSyllables(token)
            }
        }
        return total
    }

    fun computeSyllablesPerSec(
        expectedText: String,
        activeSpeechSamples: Long,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): Float {
        if (activeSpeechSamples <= 0L || sampleRateHz <= 0) return 0.0f
        val syllables = countTranscriptSyllables(expectedText)
        if (syllables <= 0) return 0.0f
        val durationSec = activeSpeechSamples.toDouble() / sampleRateHz.toDouble()
        if (durationSec <= 1e-6) return 0.0f
        return (syllables.toDouble() / durationSec).toFloat()
    }

    fun loadMasterCentroid(file: File): Result<FloatArray> {
        if (!file.isFile || !file.canRead()) {
            return Result.failure(IllegalArgumentException("Missing master speaker centroid file: ${file.absolutePath}"))
        }
        if (file.length() != SPEAKER_CENTROID_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid master_centroid.bin size: expected $SPEAKER_CENTROID_BYTES bytes " +
                        "(256 float32s), got ${file.length()} bytes (${file.absolutePath})"
                )
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse { return Result.failure(it) }
        if (bytes.size.toLong() != SPEAKER_CENTROID_BYTES) {
            return Result.failure(IllegalStateException("Short read on master_centroid.bin: ${file.absolutePath}"))
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val raw = FloatArray(SPEAKER_EMBEDDING_DIM)
        var normSq = 0.0
        for (i in 0 until SPEAKER_EMBEDDING_DIM) {
            val v = buf.float
            if (!v.isFinite()) {
                return Result.failure(
                    IllegalArgumentException("Non-finite value at index $i in master_centroid.bin")
                )
            }
            raw[i] = v
            normSq += v.toDouble() * v.toDouble()
        }
        val norm = sqrt(normSq)
        if (norm <= 1e-12) {
            return Result.failure(IllegalArgumentException("Zero-norm vector in master_centroid.bin"))
        }
        val normalized = FloatArray(SPEAKER_EMBEDDING_DIM) { i -> (raw[i].toDouble() / norm).toFloat() }
        return Result.success(normalized)
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0.0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val va = a[i]
            val vb = b[i]
            if (!va.isFinite() || !vb.isFinite()) return 0.0f
            val da = va.toDouble()
            val db = vb.toDouble()
            dot += da * db
            normA += da * da
            normB += db * db
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12) return 0.0f
        return (dot / denom).coerceIn(-1.0, 1.0).toFloat()
    }

    fun computeTailRmsDb(
        pcm44k: FloatArray,
        tailMs: Int = TAIL_WINDOW_MS,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): Float {
        if (pcm44k.isEmpty() || tailMs <= 0 || sampleRateHz <= 0) return -120.0f
        val windowSamples = min(pcm44k.size, ((sampleRateHz.toLong() * tailMs) / 1000L).toInt())
        if (windowSamples <= 0) return -120.0f
        val start = pcm44k.size - windowSamples
        var sumSq = 0.0
        for (i in start until pcm44k.size) {
            val s = pcm44k[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / windowSamples.toDouble())
        return if (rms <= 1e-6) -120.0f else (20.0 * log10(rms)).toFloat()
    }

    fun analyzeTail(
        pcm44k: FloatArray,
        tailMs: Int = TAIL_WINDOW_MS,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ,
        maxTailHfSpectralFlux: Float = MAX_TAIL_HF_SPECTRAL_FLUX,
        maxTailHfEnergyDb: Float = MAX_TAIL_HF_ENERGY_DB,
        maxTailPeakFrameRmsDb: Float = MAX_TAIL_PEAK_FRAME_RMS_DB
    ): TailAnalysisResult {
        val tailRmsDb = computeTailRmsDb(pcm44k, tailMs, sampleRateHz)
        if (pcm44k.isEmpty() || tailMs <= 0 || sampleRateHz <= 0) {
            return TailAnalysisResult(tailRmsDb, -120.0f, -120.0f, 0.0f, false)
        }
        val windowSamples = min(pcm44k.size, ((sampleRateHz.toLong() * tailMs) / 1000L).toInt())
        if (windowSamples <= 0) {
            return TailAnalysisResult(tailRmsDb, -120.0f, -120.0f, 0.0f, false)
        }
        val tailStart = pcm44k.size - windowSamples

        // 1. Short-window (10 ms) peak RMS inside the 80 ms tail.
        val subWin = max(64, min(windowSamples, sampleRateHz / 100))
        val subHop = max(32, subWin / 2)
        var peakSubRms = 0.0
        var pos = tailStart
        while (pos + subWin <= pcm44k.size) {
            var sq = 0.0
            for (i in 0 until subWin) {
                val s = pcm44k[pos + i].toDouble()
                sq += s * s
            }
            peakSubRms = max(peakSubRms, sqrt(sq / subWin.toDouble()))
            pos += subHop
        }
        if (pcm44k.size >= subWin) {
            val endPos = pcm44k.size - subWin
            var sq = 0.0
            for (i in 0 until subWin) {
                val s = pcm44k[endPos + i].toDouble()
                sq += s * s
            }
            peakSubRms = max(peakSubRms, sqrt(sq / subWin.toDouble()))
        }
        val peakFrameRmsDb = if (peakSubRms <= 1e-6) -120.0f else (20.0 * log10(peakSubRms)).toFloat()

        // 2. 256-pt Hann STFT across tail for >= 4 kHz energy & positive spectral flux.
        val fftSize = 256
        val fftHop = 128
        var maxHfFlux = 0.0
        var maxHfRms = 0.0
        if (windowSamples >= fftSize) {
            val hann = DoubleArray(fftSize) { i ->
                0.5 * (1.0 - cos((2.0 * PI * i) / (fftSize - 1).toDouble()))
            }
            var hannEnergy = 0.0
            for (w in hann) hannEnergy += w * w

            val nyquistBin = fftSize / 2
            val hfStartBin = ceil((4000.0 * fftSize) / sampleRateHz.toDouble())
                .toInt()
                .coerceIn(1, nyquistBin)

            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            var prevMag = DoubleArray(nyquistBin + 1)
            var currMag = DoubleArray(nyquistBin + 1)
            var hasPrev = false

            var framePos = tailStart
            while (framePos + fftSize <= pcm44k.size) {
                for (i in 0 until fftSize) {
                    real[i] = pcm44k[framePos + i].toDouble() * hann[i]
                    imag[i] = 0.0
                }
                radix2Fft(real, imag)

                var hfPower = 0.0
                for (k in hfStartBin..nyquistBin) {
                    val magRaw = hypot(real[k], imag[k])
                    currMag[k] = magRaw / fftSize.toDouble()
                    val weight = if (k == nyquistBin) 1.0 else 2.0
                    hfPower += weight * magRaw * magRaw
                }
                val hfRms = sqrt(hfPower / (fftSize.toDouble() * hannEnergy))
                maxHfRms = max(maxHfRms, hfRms)

                if (hasPrev) {
                    var fluxSq = 0.0
                    for (k in hfStartBin..nyquistBin) {
                        val diff = currMag[k] - prevMag[k]
                        if (diff > 0.0) fluxSq += diff * diff
                    }
                    maxHfFlux = max(maxHfFlux, sqrt(fluxSq))
                }
                val tmp = prevMag
                prevMag = currMag
                currMag = tmp
                hasPrev = true
                framePos += fftHop
            }
        }

        val hfEnergyDb = if (maxHfRms <= 1e-6) -120.0f else (20.0 * log10(maxHfRms)).toFloat()
        val hfFlux = maxHfFlux.toFloat()
        val hfBurst = hfEnergyDb > maxTailHfEnergyDb && hfFlux > maxTailHfSpectralFlux
        val impulsiveClick = peakFrameRmsDb > maxTailPeakFrameRmsDb
        return TailAnalysisResult(
            tailRmsDb = tailRmsDb,
            tailPeakFrameRmsDb = peakFrameRmsDb,
            tailHfEnergyDb = hfEnergyDb,
            tailHfSpectralFlux = hfFlux,
            hfGlitchDetected = hfBurst || impulsiveClick
        )
    }

    fun findNearestZeroCrossing(
        pcm44k: FloatArray,
        targetSample: Long,
        scanRadius: Int = ZERO_CROSSING_SCAN_SAMPLES
    ): Long {
        if (pcm44k.isEmpty()) return 0L
        val lastIdx = (pcm44k.size - 1).toLong()
        val clampedTarget = targetSample.coerceIn(0L, lastIdx)
        val radius = max(0, scanRadius).toLong()
        val lo = max(0L, clampedTarget - radius).toInt()
        val hi = min(lastIdx, clampedTarget + radius).toInt()

        var bestIdx = clampedTarget.toInt()
        var bestAbs = abs(pcm44k[bestIdx])
        var bestDist = 0L
        var foundSignChange = false

        for (i in (lo + 1)..hi) {
            val prev = pcm44k[i - 1]
            val curr = pcm44k[i]
            if ((prev <= 0.0f && curr >= 0.0f) || (prev >= 0.0f && curr <= 0.0f)) {
                val absPrev = abs(prev)
                val absCurr = abs(curr)
                val cand = when {
                    absPrev < absCurr -> i - 1
                    absCurr < absPrev -> i
                    abs((i - 1).toLong() - clampedTarget) <= abs(i.toLong() - clampedTarget) -> i - 1
                    else -> i
                }
                val candAbs = abs(pcm44k[cand])
                val candDist = abs(cand.toLong() - clampedTarget)
                val better = !foundSignChange ||
                    (candAbs + 1e-6f < bestAbs) ||
                    (abs(candAbs - bestAbs) <= 1e-6f && candDist < bestDist)
                if (better) {
                    foundSignChange = true
                    bestIdx = cand
                    bestAbs = candAbs
                    bestDist = candDist
                }
            }
        }
        if (foundSignChange) return bestIdx.toLong()

        bestDist = Long.MAX_VALUE
        for (i in lo..hi) {
            val v = abs(pcm44k[i])
            val dist = abs(i.toLong() - clampedTarget)
            if (v + 1e-6f < bestAbs || (abs(v - bestAbs) <= 1e-6f && dist < bestDist)) {
                bestAbs = v
                bestIdx = i
                bestDist = dist
            }
        }
        return bestIdx.toLong()
    }

    fun findF0PeakSample(
        pcm44k: FloatArray,
        startSample: Long,
        endSample: Long,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): Long {
        if (pcm44k.isEmpty()) return 0L
        val lastIdx = (pcm44k.size - 1).toLong()
        val lo = startSample.coerceIn(0L, lastIdx).toInt()
        val hi = endSample.coerceIn(lo.toLong(), lastIdx).toInt()
        val spanLen = hi - lo + 1
        if (spanLen <= 1 || sampleRateHz <= 0) return lo.toLong()

        fun findAbsPeakInRange(rLo: Int, rHi: Int): Long {
            val a = rLo.coerceIn(lo, hi)
            val b = rHi.coerceIn(a, hi)
            var peakIdx = a
            var peakAmp = abs(pcm44k[a])
            for (i in (a + 1)..b) {
                val amp = abs(pcm44k[i])
                if (amp > peakAmp) {
                    peakAmp = amp
                    peakIdx = i
                }
            }
            return peakIdx.toLong()
        }

        if (spanLen < 256) {
            return findAbsPeakInRange(lo, hi)
        }

        val frameLen = min(1280, spanLen)
        val halfWin = frameLen / 2
        val hop = max(128, frameLen / 4)
        val tauMin = (sampleRateHz / 450).coerceIn(2, halfWin - 1)
        val tauMax = (sampleRateHz / 75).coerceIn(tauMin, halfWin - 1)

        val cmndf = DoubleArray(tauMax + 1)
        var bestVoicedScore = -1.0
        var bestVoicedCenter = lo + spanLen / 2
        var bestRms = -1.0
        var bestRmsCenter = lo + spanLen / 2

        fun evaluateFrame(fStart: Int) {
            var energySq = 0.0
            for (i in 0 until frameLen) {
                val s = pcm44k[fStart + i].toDouble()
                energySq += s * s
            }
            val rms = sqrt(energySq / frameLen.toDouble())
            val center = fStart + frameLen / 2
            if (rms > bestRms) {
                bestRms = rms
                bestRmsCenter = center
            }
            if (rms <= 1e-5) return

            var runningSum = 0.0
            cmndf[0] = 1.0
            for (tau in 1..tauMax) {
                var d = 0.0
                for (j in 0 until halfWin) {
                    val delta = pcm44k[fStart + j].toDouble() - pcm44k[fStart + j + tau].toDouble()
                    d += delta * delta
                }
                runningSum += d
                cmndf[tau] = if (runningSum > 1e-12) (d * tau.toDouble()) / runningSum else 1.0
            }

            var chosenTau = -1
            var tau = tauMin
            while (tau <= tauMax) {
                if (cmndf[tau] < 0.15) {
                    while (tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau]) {
                        tau++
                    }
                    chosenTau = tau
                    break
                }
                tau++
            }
            if (chosenTau < 0) {
                chosenTau = tauMin
                for (t in (tauMin + 1)..tauMax) {
                    if (cmndf[t] < cmndf[chosenTau]) chosenTau = t
                }
            }

            val minCmndf = cmndf[chosenTau]
            if (minCmndf < 0.35) {
                val periodicity = (1.0 - minCmndf).coerceIn(0.0, 1.0)
                val voicedScore = periodicity * rms
                if (voicedScore > bestVoicedScore) {
                    bestVoicedScore = voicedScore
                    bestVoicedCenter = center
                }
            }
        }

        var pos = lo
        while (pos + frameLen <= hi + 1) {
            evaluateFrame(pos)
            pos += hop
        }
        val lastStart = hi + 1 - frameLen
        if (lastStart >= lo && ((lastStart - lo) % hop != 0)) {
            evaluateFrame(lastStart)
        }

        val chosenCenter = if (bestVoicedScore > 0.0) bestVoicedCenter else bestRmsCenter
        val searchHalf = max(64, hop / 2)
        return findAbsPeakInRange(chosenCenter - searchHalf, chosenCenter + searchHalf)
    }

    fun scanWordBoundaries(
        pcm44k: FloatArray,
        rawSpans: List<RawWordSpan>,
        scanRadius: Int = ZERO_CROSSING_SCAN_SAMPLES,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): List<WordToken> {
        if (pcm44k.isEmpty()) return emptyList()
        val lastIdx = (pcm44k.size - 1).toLong()
        return rawSpans.map { span ->
            val start = span.startSample.coerceIn(0L, lastIdx)
            val end = span.endSample.coerceIn(start, lastIdx)
            val zcIn = findNearestZeroCrossing(pcm44k, start, scanRadius)
            val zcOut = max(zcIn, findNearestZeroCrossing(pcm44k, end, scanRadius))
            val f0Peak = findF0PeakSample(pcm44k, start, end, sampleRateHz)
            WordToken(
                word = span.word,
                startSample = start,
                endSample = end,
                zeroCrossIn = zcIn,
                zeroCrossOut = zcOut,
                f0PeakSample = f0Peak,
                videoAnchorId = null
            )
        }
    }

    fun resample44kTo16k(
        pcm44k: FloatArray,
        srcSampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ,
        dstSampleRateHz: Int = 16_000
    ): FloatArray {
        if (pcm44k.isEmpty()) return FloatArray(0)
        if (srcSampleRateHz == dstSampleRateHz) return pcm44k.copyOf()

        val ratio = srcSampleRateHz.toDouble() / dstSampleRateHz.toDouble()
        val dstLen = max(1, (pcm44k.size.toDouble() / ratio).toInt())
        val out = FloatArray(dstLen)
        val halfKernel = 4
        val cutoff = dstSampleRateHz.toDouble() / srcSampleRateHz.toDouble()

        for (i in 0 until dstLen) {
            val srcPos = i.toDouble() * ratio
            val center = srcPos.toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (k in -halfKernel..halfKernel) {
                val idx = center + k
                if (idx !in pcm44k.indices) continue
                val x = srcPos - idx.toDouble()
                val arg = x * cutoff
                val sinc = if (abs(arg) < 1e-9) 1.0 else sin(PI * arg) / (PI * arg)
                val win = 0.54 + 0.46 * cos((PI * x) / (halfKernel + 1).toDouble())
                val w = sinc * win
                acc += pcm44k[idx].toDouble() * w
                weightSum += w
            }
            out[i] = if (weightSum > 1e-12) (acc / weightSum).toFloat() else 0.0f
        }
        return out
    }

    /**
     * Computes row-major `[numFrames, numMelBins]` log-Mel filterbank features on 16 kHz audio for
     * WeSpeaker ResNet34 (`numMelBins = 80`), DNSMOS p808 (`numMelBins = 120`), and CTC models.
     */
    fun computeLogMelFilterbank(
        pcm16k: FloatArray,
        sampleRateHz: Int = 16_000,
        numMelBins: Int = 80,
        frameLengthSamples: Int = 400,
        frameShiftSamples: Int = 160,
        fftSize: Int = 512,
        fMinHz: Float = 20.0f,
        fMaxHz: Float = 7600.0f,
        applyCmn: Boolean = true
    ): Pair<FloatArray, Int> {
        if (pcm16k.isEmpty()) return FloatArray(0) to 0
        val padded = if (pcm16k.size < frameLengthSamples) {
            FloatArray(frameLengthSamples).also { pcm16k.copyInto(it) }
        } else {
            pcm16k
        }
        val numFrames = max(1, 1 + (padded.size - frameLengthSamples) / frameShiftSamples)
        val numSpecBins = fftSize / 2 + 1

        // Povey window (Hamming raised to 0.85) matching Kaldi/WeSpeaker/sherpa-onnx Fbank.
        val window = DoubleArray(frameLengthSamples) { i ->
            (0.5 - 0.5 * cos((2.0 * PI * i) / (frameLengthSamples - 1).toDouble())).pow(0.85)
        }

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMinHz.toDouble())
        val melMax = hzToMel(min(fMaxHz.toDouble(), sampleRateHz * 0.5))
        val melPoints = DoubleArray(numMelBins + 2) { m ->
            melToHz(melMin + (melMax - melMin) * m.toDouble() / (numMelBins + 1).toDouble())
        }
        val binFreqStep = sampleRateHz.toDouble() / fftSize.toDouble()
        val filterBank = Array(numMelBins) { m ->
            val left = melPoints[m]
            val center = melPoints[m + 1]
            val right = melPoints[m + 2]
            DoubleArray(numSpecBins) { k ->
                val f = k * binFreqStep
                when {
                    f < left || f > right -> 0.0
                    f <= center && center > left -> (f - left) / (center - left)
                    right > center -> (right - f) / (right - center)
                    else -> 0.0
                }
            }
        }

        val features = FloatArray(numFrames * numMelBins)
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        val powerSpec = DoubleArray(numSpecBins)

        for (t in 0 until numFrames) {
            val offset = t * frameShiftSamples
            // Pre-emphasis (0.97) + DC removal.
            var mean = 0.0
            for (i in 0 until frameLengthSamples) {
                mean += padded[offset + i].toDouble()
            }
            mean /= frameLengthSamples.toDouble()

            real.fill(0.0)
            imag.fill(0.0)
            var prevSample = padded[offset].toDouble() - mean
            for (i in 0 until frameLengthSamples) {
                val cur = padded[offset + i].toDouble() - mean
                val emph = if (i == 0) cur * 0.03 else cur - 0.97 * prevSample
                prevSample = cur
                real[i] = emph * window[i]
            }
            radix2Fft(real, imag)

            for (k in 0 until numSpecBins) {
                powerSpec[k] = real[k] * real[k] + imag[k] * imag[k]
            }

            val base = t * numMelBins
            for (m in 0 until numMelBins) {
                var energy = 0.0
                val weights = filterBank[m]
                for (k in 0 until numSpecBins) {
                    val w = weights[k]
                    if (w > 0.0) energy += w * powerSpec[k]
                }
                features[base + m] = ln(max(energy, 1e-10)).toFloat()
            }
        }

        if (applyCmn && numFrames > 1) {
            for (m in 0 until numMelBins) {
                var sum = 0.0
                for (t in 0 until numFrames) {
                    sum += features[t * numMelBins + m].toDouble()
                }
                val mean = (sum / numFrames.toDouble()).toFloat()
                for (t in 0 until numFrames) {
                    features[t * numMelBins + m] -= mean
                }
            }
        }

        return features to numFrames
    }

    fun ctcGreedyDecode(
        logProbs: FloatArray,
        numFrames: Int,
        vocabSize: Int,
        blankId: Int,
        vocab: List<String>,
        frameShiftSamples: Int,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): CtcGreedyResult {
        if (numFrames <= 0 || vocabSize <= 0 || vocab.size < vocabSize || frameShiftSamples <= 0) {
            return CtcGreedyResult("", 0.0f)
        }
        val sb = StringBuilder()
        var prevId = -1
        var runFrames = 0
        var maxRunFrames = 0

        for (t in 0 until numFrames) {
            val base = t * vocabSize
            var bestId = 0
            var bestVal = logProbs[base]
            for (v in 1 until vocabSize) {
                val score = logProbs[base + v]
                if (score > bestVal) {
                    bestVal = score
                    bestId = v
                }
            }

            if (bestId == prevId) {
                if (bestId != blankId) {
                    runFrames++
                    if (runFrames > maxRunFrames) maxRunFrames = runFrames
                }
            } else {
                if (bestId != blankId) {
                    runFrames = 1
                    if (runFrames > maxRunFrames) maxRunFrames = runFrames
                    val tok = vocab[bestId]
                    when {
                        tok.isEmpty() || tok == " " || tok == "|" || tok == "▁" -> {
                            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                        }
                        tok.startsWith("▁") -> {
                            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                            sb.append(tok.substring(1))
                        }
                        else -> sb.append(tok)
                    }
                } else {
                    runFrames = 0
                }
                prevId = bestId
            }
        }

        val frameMs = (frameShiftSamples.toDouble() * 1000.0) / sampleRateHz.toDouble()
        return CtcGreedyResult(
            decodedText = sb.toString().trim(),
            maxPhonemeDurationMs = (maxRunFrames * frameMs).toFloat()
        )
    }

    fun ctcViterbiForceAlign(
        logProbs: FloatArray,
        numFrames: Int,
        vocabSize: Int,
        blankId: Int,
        targetTokenIds: IntArray,
        vocab: List<String>,
        frameShiftSamples: Int,
        totalSamples: Long,
        sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
    ): CtcViterbiResult? {
        if (numFrames <= 0 || vocabSize <= 0 || blankId !in 0 until vocabSize ||
            targetTokenIds.isEmpty() || vocab.size < vocabSize ||
            frameShiftSamples <= 0 || totalSamples <= 0L || numFrames < targetTokenIds.size
        ) {
            return null
        }

        val numTokens = targetTokenIds.size
        val numStates = 2 * numTokens + 1
        val ext = IntArray(numStates) { s -> if ((s and 1) == 1) targetTokenIds[s / 2] else blankId }

        val negInf = Double.NEGATIVE_INFINITY
        var prevDp = DoubleArray(numStates) { negInf }
        var currDp = DoubleArray(numStates) { negInf }
        val backptr = IntArray(numFrames * numStates) { -1 }

        prevDp[0] = logProbs[blankId].toDouble()
        if (numStates > 1) {
            prevDp[1] = logProbs[ext[1]].toDouble()
        }

        for (t in 1 until numFrames) {
            currDp.fill(negInf)
            val frameBase = t * vocabSize
            val bpBase = t * numStates
            for (s in 0 until numStates) {
                var bestScore = prevDp[s]
                var bestPrev = s
                if (s >= 1 && prevDp[s - 1] > bestScore) {
                    bestScore = prevDp[s - 1]
                    bestPrev = s - 1
                }
                if (s >= 2 && ext[s] != blankId && ext[s] != ext[s - 2] && prevDp[s - 2] > bestScore) {
                    bestScore = prevDp[s - 2]
                    bestPrev = s - 2
                }
                if (bestScore.isFinite()) {
                    currDp[s] = bestScore + logProbs[frameBase + ext[s]].toDouble()
                    backptr[bpBase + s] = bestPrev
                }
            }
            val tmp = prevDp
            prevDp = currDp
            currDp = tmp
        }

        var endState = numStates - 1
        if (numStates >= 2 && prevDp[numStates - 2] > prevDp[numStates - 1]) {
            endState = numStates - 2
        }
        if (!prevDp[endState].isFinite()) return null

        val statePath = IntArray(numFrames)
        var cur = endState
        for (t in (numFrames - 1) downTo 0) {
            statePath[t] = cur
            if (t > 0) {
                val prev = backptr[t * numStates + cur]
                if (prev < 0) return null
                cur = prev
            }
        }

        val firstFrame = IntArray(numTokens) { Int.MAX_VALUE }
        val lastFrame = IntArray(numTokens) { -1 }
        for (t in 0 until numFrames) {
            val s = statePath[t]
            if ((s and 1) == 1) {
                val idx = s / 2
                if (t < firstFrame[idx]) firstFrame[idx] = t
                if (t > lastFrame[idx]) lastFrame[idx] = t
            }
        }

        val frameMs = (frameShiftSamples.toDouble() * 1000.0) / sampleRateHz.toDouble()
        var maxDurMs = 0.0f
        val spans = mutableListOf<RawWordSpan>()
        val activeWord = StringBuilder()
        var wordStartFrame = 0
        var wordEndFrame = 0
        var inWord = false

        fun flushWord() {
            if (!inWord || activeWord.isEmpty()) {
                inWord = false
                activeWord.setLength(0)
                return
            }
            val sSample = (wordStartFrame.toLong() * frameShiftSamples).coerceIn(0L, totalSamples - 1L)
            val eSample = ((wordEndFrame + 1L) * frameShiftSamples).coerceIn(sSample, totalSamples - 1L)
            spans.add(RawWordSpan(activeWord.toString(), sSample, eSample))
            activeWord.setLength(0)
            inWord = false
        }

        for (i in 0 until numTokens) {
            if (lastFrame[i] < 0) return null
            val piece = vocab[targetTokenIds[i]]
            val isBoundary = piece.isEmpty() || piece == " " || piece == "|" || piece == "▁"
            if (!isBoundary) {
                val durMs = ((lastFrame[i] - firstFrame[i] + 1) * frameMs).toFloat()
                if (durMs > maxDurMs) maxDurMs = durMs
            }
            when {
                isBoundary -> flushWord()
                piece.startsWith("▁") -> {
                    flushWord()
                    val cleanPiece = piece.substring(1)
                    if (cleanPiece.isNotEmpty()) {
                        activeWord.append(cleanPiece)
                        wordStartFrame = firstFrame[i]
                        wordEndFrame = lastFrame[i]
                        inWord = true
                    }
                }
                else -> {
                    if (!inWord) {
                        wordStartFrame = firstFrame[i]
                        inWord = true
                    }
                    activeWord.append(piece)
                    wordEndFrame = lastFrame[i]
                }
            }
        }
        flushWord()
        return CtcViterbiResult(spans, maxDurMs)
    }

    /**
     * Writes a headerless 44.1 kHz Float32 little-endian `.pcm` file padded with trailing zeros
     * to a strict 64-byte alignment boundary (`file.length() % 64L == 0L`) via atomic `.tmp` rename.
     */
    fun writeAlignedPcmFile(pcm44k: FloatArray, outputFile: File): Long {
        outputFile.parentFile?.mkdirs()
        val rawBytes = pcm44k.size.toLong() * 4L
        val alignedBytes = ((rawBytes + PCM_ALIGNMENT_BYTES - 1L) / PCM_ALIGNMENT_BYTES) * PCM_ALIGNMENT_BYTES
        val paddingFloats = ((alignedBytes - rawBytes) / 4L).toInt()

        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        val chunkFloats = 4096
        val byteBuf = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN)

        FileOutputStream(tmpFile).use { fos ->
            var idx = 0
            while (idx < pcm44k.size) {
                val count = min(chunkFloats, pcm44k.size - idx)
                byteBuf.clear()
                for (j in 0 until count) {
                    byteBuf.putFloat(pcm44k[idx + j])
                }
                fos.write(byteBuf.array(), 0, count * 4)
                idx += count
            }
            if (paddingFloats > 0) {
                byteBuf.clear()
                for (j in 0 until paddingFloats) {
                    byteBuf.putFloat(0.0f)
                }
                fos.write(byteBuf.array(), 0, paddingFloats * 4)
            }
            fos.fd.sync()
        }

        if (outputFile.exists() && !outputFile.delete()) {
            tmpFile.delete()
            throw IllegalStateException("Failed to replace existing PCM file: ${outputFile.absolutePath}")
        }
        if (!tmpFile.renameTo(outputFile)) {
            tmpFile.delete()
            throw IllegalStateException("Atomic rename failed for PCM file: ${outputFile.absolutePath}")
        }
        return pcm44k.size.toLong()
    }

    private fun radix2Fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len.toDouble()
            val wlenR = cos(ang)
            val wlenI = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until half) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + half] * wr - imag[i + k + half] * wi
                    val vi = real[i + k + half] * wi + imag[i + k + half] * wr
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + half] = ur - vr
                    imag[i + k + half] = ui - vi
                    val nextWr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
