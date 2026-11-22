package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.BrandChannel
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ExportVideoResult(
    val mediaStoreUri: Uri,
    val shareIntent: Intent
)

/**
 * Encodes genuine 720x1280 @ 24fps H.264 + AAC .mp4 video via Android MediaCodec + MediaMuxer
 * with ML Kit landmark-driven mouth/jaw articulation synced to real .wav RMS audio,
 * timed B-roll cutaways, Space Grotesk lower-third chyrons, and optional Aircheck corner bug.
 */
class OnDeviceVideoComposer(private val context: Context) {

    suspend fun composeTalkingHeadMp4(
        project: ScriptProject,
        anchorBitmap: Bitmap,
        bRollByBeatId: Map<String, Bitmap>,
        wavAudioFile: File,
        channel: BrandChannel,
        outputMp4File: File,
        onProgress: (Float, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val width = 720
        val height = 1280
        val fps = 24
        val wavMeta = AudioEngine.parseWavHeader(wavAudioFile)
            ?: return@withContext Result.failure(IllegalStateException("Audio WAV track missing or unreadable."))
        val rmsEnvelope = AudioEngine.decodeFrameRmsEnvelope(wavAudioFile, fps)
        val totalFrames = max(fps * 3, rmsEnvelope.size)

        val spaceGrotesk: Typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.space_grotesk)
        }.getOrNull() ?: Typeface.SANS_SERIF
        val puckBugBitmap = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.aircheck_puck_icon)
        }.getOrNull()

        val scaledAnchor = centerCropTo(anchorBitmap, width, height)
        val scaledBRoll = bRollByBeatId.mapValues { (_, bmp) -> centerCropTo(bmp, width, height) }

        val activeBeats = project.activeBeats.ifEmpty {
            listOf(ScriptBeat(spokenText = project.title.ifBlank { "Aircheck" }, durationSec = 4))
        }
        val totalBeatWeight = activeBeats.sumOf { it.effectiveDurationSec }.coerceAtLeast(1)

        val accentColor = if (channel == BrandChannel.VOLT_LIME) 0xFFB8FF00.toInt() else 0xFF0088FF.toInt()
        val accentInk = if (channel == BrandChannel.VOLT_LIME) Color.BLACK else Color.WHITE

        outputMp4File.parentFile?.mkdirs()
        if (outputMp4File.exists()) outputMp4File.delete()

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // 1. Configure H.264 video encoder with Input Surface
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_600_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            // 2. Configure AAC audio encoder matching the real WAV sample rate & channels
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                wavMeta.sampleRate,
                wavMeta.channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Collect encoded packets first into memory-safe buffers so both tracks add before muxer.start()
            data class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
            val videoSamples = mutableListOf<EncodedSample>()
            var resolvedVideoFormat: MediaFormat? = null
            val videoInfo = MediaCodec.BufferInfo()

            onProgress(0.05f, "Encoding talking-head frames…")

            // Render every frame onto the hardware encoder surface
            val mouthX = project.stageConfig.mouthCenterX * width
            val mouthY = project.stageConfig.mouthCenterY * height
            val mouthW = (project.stageConfig.mouthWidthNorm * width).coerceIn(48f, 220f)
            val headline = project.stageConfig.chyronHeadline.ifBlank { project.title.ifBlank { "Aircheck" } }
            val subline = project.stageConfig.chyronSubline

            for (frameIdx in 0 until totalFrames) {
                val energy = if (frameIdx < rmsEnvelope.size) rmsEnvelope[frameIdx] else 0f
                val progressFrac = frameIdx.toFloat() / totalFrames.toFloat()
                val activeBeat = resolveBeatAt(activeBeats, totalBeatWeight, progressFrac)
                val bRollBmp = scaledBRoll[activeBeat.id]
                val showBRoll = bRollBmp != null && (frameIdx % (fps * 4)) in (fps * 2)..(fps * 3 + 12)

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    drawBroadcastFrame(
                        canvas = canvas,
                        width = width,
                        height = height,
                        frameIdx = frameIdx,
                        anchor = scaledAnchor,
                        bRoll = if (showBRoll) bRollBmp else null,
                        speechEnergy = energy,
                        mouthX = mouthX,
                        mouthY = mouthY,
                        mouthW = mouthW,
                        headline = headline,
                        subline = subline,
                        depthKeyword = activeBeat.depthTextTag,
                        citation = activeBeat.evidenceSource,
                        showBrandBug = project.stageConfig.showBrandBug,
                        puckBug = puckBugBitmap,
                        accentColor = accentColor,
                        accentInk = accentInk,
                        typeface = spaceGrotesk
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                // Drain available video packets
                drainEncoder(
                    encoder = videoEncoder,
                    bufferInfo = videoInfo,
                    endOfStream = false,
                    onFormat = { resolvedVideoFormat = it },
                    onPacket = { bytes, pts, flags ->
                        videoSamples.add(EncodedSample(bytes, pts, flags))
                    }
                )

                if (frameIdx % 6 == 0) {
                    onProgress(0.08f + (progressFrac * 0.65f), "Encoding frame ${frameIdx + 1}/$totalFrames")
                }
            }

            videoEncoder.signalEndOfInputStream()
            drainEncoder(
                encoder = videoEncoder,
                bufferInfo = videoInfo,
                endOfStream = true,
                onFormat = { resolvedVideoFormat = it },
                onPacket = { bytes, pts, flags ->
                    videoSamples.add(EncodedSample(bytes, pts, flags))
                }
            )
            inputSurface.release()

            // 3. Encode real 16-bit RIFF WAV PCM into AAC
            onProgress(0.76f, "Encoding AAC voice track…")
            val audioSamples = mutableListOf<EncodedSample>()
            var resolvedAudioFormat: MediaFormat? = null
            val audioInfo = MediaCodec.BufferInfo()

            val allWavBytes = wavAudioFile.readBytes()
            val pcmStart = wavMeta.dataOffset.coerceAtMost(allWavBytes.size)
            val pcmEnd = (wavMeta.dataOffset + wavMeta.dataSize).coerceAtMost(allWavBytes.size)
            val bytesPerSec = (wavMeta.sampleRate * wavMeta.channels * wavMeta.bytesPerSample).coerceAtLeast(1)

            var offset = pcmStart
            var inputDone = false
            var outputDone = false
            var totalBytesFed = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = audioEncoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcmEnd - offset
                        if (remaining <= 0) {
                            audioEncoder.queueInputBuffer(
                                inIdx, 0, 0,
                                (totalBytesFed * 1_000_000L) / bytesPerSec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val chunk = min(remaining, inBuf.capacity())
                            inBuf.put(allWavBytes, offset, chunk)
                            val ptsUs = (totalBytesFed * 1_000_000L) / bytesPerSec
                            audioEncoder.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                            offset += chunk
                            totalBytesFed += chunk
                        }
                    }
                }
                val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        resolvedAudioFormat = audioEncoder.outputFormat
                    }
                    outIdx >= 0 -> {
                        val outBuf = audioEncoder.getOutputBuffer(outIdx)
                        if (outBuf != null && audioInfo.size > 0 &&
                            (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(audioInfo.offset)
                            outBuf.limit(audioInfo.offset + audioInfo.size)
                            val copy = ByteArray(audioInfo.size)
                            outBuf.get(copy)
                            audioSamples.add(EncodedSample(copy, audioInfo.presentationTimeUs, audioInfo.flags))
                        }
                        audioEncoder.releaseOutputBuffer(outIdx, false)
                        if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            // 4. Mux H.264 + AAC into final .mp4 container
            onProgress(0.90f, "Muxing MP4 broadcast master…")
            val finalVideoFmt = resolvedVideoFormat
                ?: throw IllegalStateException("H.264 video encoder produced no output format.")
            val finalAudioFmt = resolvedAudioFormat
                ?: throw IllegalStateException("AAC audio encoder produced no output format.")

            val videoTrackIdx = muxer.addTrack(finalVideoFmt)
            val audioTrackIdx = muxer.addTrack(finalAudioFmt)
            muxer.start()

            val frameDurationUs = 1_000_000L / fps
            val writeInfo = MediaCodec.BufferInfo()
            videoSamples.forEachIndexed { idx, sample ->
                writeInfo.set(0, sample.data.size, idx * frameDurationUs, sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(videoTrackIdx, ByteBuffer.wrap(sample.data), writeInfo)
            }
            var lastAudioPts = -1L
            audioSamples.forEach { sample ->
                val pts = max(sample.ptsUs, lastAudioPts + 1)
                lastAudioPts = pts
                writeInfo.set(0, sample.data.size, pts, 0)
                muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(sample.data), writeInfo)
            }

            onProgress(1f, "Render complete")
            Result.success(outputMp4File)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (scaledAnchor !== anchorBitmap) scaledAnchor.recycle()
            scaledBRoll.values.forEach { it.recycle() }
            puckBugBitmap?.recycle()
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onFormat: (MediaFormat) -> Unit,
        onPacket: (ByteArray, Long, Int) -> Unit
    ) {
        while (true) {
            val idx = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormat(encoder.outputFormat)
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)
                    if (buf != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        buf.get(bytes)
                        onPacket(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun drawBroadcastFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameIdx: Int,
        anchor: Bitmap,
        bRoll: Bitmap?,
        speechEnergy: Float,
        mouthX: Float,
        mouthY: Float,
        mouthW: Float,
        headline: String,
        subline: String,
        depthKeyword: String,
        citation: String,
        showBrandBug: Boolean,
        puckBug: Bitmap?,
        accentColor: Int,
        accentInk: Int,
        typeface: Typeface
    ) {
        canvas.drawColor(Color.BLACK)

        // Subtle broadcast breathing zoom
        val zoom = 1.0f + 0.014f * sin(frameIdx * 0.06f)
        val drawBmp = bRoll ?: anchor
        canvas.save()
        canvas.scale(zoom, zoom, width * 0.5f, height * 0.42f)
        canvas.drawBitmap(drawBmp, 0f, 0f, null)

        // Audio-RMS-driven mouth & lower-jaw articulation on presenter frame
        if (bRoll == null && speechEnergy > 0.09f) {
            val openFrac = ((speechEnergy - 0.08f) / 0.85f).coerceIn(0f, 1f)
            val jawDropPx = openFrac * (mouthW * 0.34f)
            val lipHalfW = mouthW * 0.48f
            val lipHalfH = max(3f, jawDropPx * 0.52f)

            // 1. Shift lower chin strip softly downward proportional to speech energy
            val jawSrc = Rect(
                (mouthX - mouthW * 0.72f).toInt().coerceIn(0, width - 2),
                mouthY.toInt().coerceIn(0, height - 2),
                (mouthX + mouthW * 0.72f).toInt().coerceIn(1, width),
                (mouthY + mouthW * 0.95f).toInt().coerceIn(1, height)
            )
            val jawDst = RectF(
                jawSrc.left.toFloat(),
                jawSrc.top + jawDropPx * 0.55f,
                jawSrc.right.toFloat(),
                (jawSrc.bottom + jawDropPx * 0.55f).coerceAtMost(height.toFloat())
            )
            if (jawSrc.width() > 4 && jawSrc.height() > 4) {
                canvas.drawBitmap(anchor, jawSrc, jawDst, null)
            }

            // 2. Render natural interior mouth aperture + subtle upper teeth highlight
            val aperture = RectF(
                mouthX - lipHalfW * (0.78f + openFrac * 0.16f),
                mouthY - lipHalfH * 0.32f,
                mouthX + lipHalfW * (0.78f + openFrac * 0.16f),
                mouthY + lipHalfH
            )
            val cavityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    aperture.centerX(), aperture.top,
                    aperture.centerX(), aperture.bottom,
                    Color.argb((195 * openFrac).toInt().coerceIn(40, 215), 24, 10, 12),
                    Color.argb((225 * openFrac).toInt().coerceIn(60, 235), 14, 5, 7),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawOval(aperture, cavityPaint)

            if (openFrac > 0.28f) {
                val teethRect = RectF(
                    aperture.left + aperture.width() * 0.18f,
                    aperture.top + 1f,
                    aperture.right - aperture.width() * 0.18f,
                    aperture.top + aperture.height() * 0.32f
                )
                val teethPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((160 * openFrac).toInt().coerceIn(0, 175), 238, 234, 228)
                }
                canvas.drawRoundRect(teethRect, 4f, 4f, teethPaint)
            }
        }
        canvas.restore()

        // Cinematic bottom vignette for chyron readability
        val vignette = Paint().apply {
            shader = LinearGradient(
                0f, height * 0.68f, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(225, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height * 0.68f, width.toFloat(), height.toFloat(), vignette)

        // Optional punchy depth-text keyword pop-in
        if (depthKeyword.isNotBlank()) {
            val kwPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                textSize = 44f
                this.typeface = Typeface.create(typeface, Typeface.BOLD)
            }
            canvas.drawText(depthKeyword.uppercase(), 40f, height * 0.77f, kwPaint)
        }

        // Clean Broadcast Lower-Third Chyron
        val barTop = height - 210f
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 10, 12, 16) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 39, 48)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val chyronRect = RectF(32f, barTop, width - 32f, barTop + 132f)
        canvas.drawRoundRect(chyronRect, 14f, 14f, barPaint)
        canvas.drawRoundRect(chyronRect, 14f, 14f, borderPaint)

        // Accent vertical tally strip on chyron
        val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(RectF(32f, barTop, 44f, barTop + 132f), 6f, 6f, stripPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(244, 246, 250)
            textSize = 34f
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        canvas.drawText(headline.take(36), 62f, barTop + 56f, titlePaint)

        val subText = subline.ifBlank { citation }.take(46)
        if (subText.isNotBlank()) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 170, 185)
                textSize = 23f
                this.typeface = typeface
            }
            canvas.drawText(subText, 62f, barTop + 98f, subPaint)
        }

        // Optional Aircheck Triangle-Mic Corner Bug (top-right, restrained)
        if (showBrandBug && puckBug != null) {
            val bugRect = RectF(width - 96f, 44f, width - 36f, 104f)
            val clipPath = Path().apply { addOval(bugRect, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(puckBug, null, bugRect, null)
            canvas.restore()
        }
    }

    private fun resolveBeatAt(
        beats: List<ScriptBeat>,
        totalWeight: Int,
        fraction: Float
    ): ScriptBeat {
        val target = fraction * totalWeight
        var acc = 0f
        for (beat in beats) {
            acc += beat.effectiveDurationSec
            if (target <= acc) return beat
        }
        return beats.last()
    }

    private fun centerCropTo(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val dstAspect = dstW.toFloat() / dstH.toFloat()
        val cropRect = if (srcAspect > dstAspect) {
            val newW = (src.height * dstAspect).toInt()
            val left = (src.width - newW) / 2
            Rect(left, 0, left + newW, src.height)
        } else {
            val newH = (src.width / dstAspect).toInt()
            val top = ((src.height - newH) * 0.3f).toInt().coerceAtLeast(0)
            Rect(0, top, src.width, (top + newH).coerceAtMost(src.height))
        }
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, cropRect, Rect(0, 0, dstW, dstH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Persists the rendered .mp4 into Android MediaStore (Movies/Aircheck) and builds a system Share intent.
     */
    suspend fun exportToGalleryAndBuildShareIntent(
        mp4File: File,
        projectTitle: String
    ): Result<ExportVideoResult> = withContext(Dispatchers.IO) {
        if (!mp4File.exists() || mp4File.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("Render an Aircheck video first."))
        }
        return@withContext runCatching {
            val safeName = projectTitle
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(" ", "_")
                .ifBlank { "Aircheck_Broadcast" }
                .take(40)
            val fileName = "${safeName}_${System.currentTimeMillis()}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Aircheck")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore rejected MP4 video insert.")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(mp4File).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Unable to write MP4 bytes to MediaStore.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                mp4File
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, projectTitle.ifBlank { "Aircheck Video" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            ExportVideoResult(mediaStoreUri = uri, shareIntent = shareIntent)
        }
    }
}
