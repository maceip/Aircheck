package com.voicestudio.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.computeAnchorTimestampsUs as canonicalComputeAnchorTimestampsUs
import kotlin.math.max
import kotlin.math.min

/**
 * Binds a `WordToken.videoAnchorId` on the voice EDL timeline to a source video URI.
 */
data class VideoAnchorBinding(
    val videoAnchorId: String,
    val videoUri: String,
    val maxClipDurationUs: Long? = null
)

/**
 * Concrete Media3 clipping specification locking a bound video cut (`videoAnchorId`) strictly to
 * its post-compression `zeroCrossIn` sample offset in microseconds.
 */
data class Media3BeatClipSpec(
    val videoAnchorId: String,
    val clauseId: String,
    val word: String,
    val videoUri: String,
    val anchorSample: Long,
    val startPositionUs: Long,
    val endPositionUs: Long,
    val clippingConfiguration: MediaItem.ClippingConfiguration,
    val mediaItem: MediaItem
) {
    val durationUs: Long
        get() = (endPositionUs - startPositionUs).coerceAtLeast(0L)
}

/**
 * Beat-accurate Media3 Transformer exporter locking video cut boundaries to post-compression
 * `zeroCrossIn` sample offsets from [VoiceProjectManifest].
 */
object Media3BeatExporter {

    /**
     * Canonical beat-anchor microsecond calculation delegating directly to `:audio-edl`'s
     * [com.voicestudio.edl.computeAnchorTimestampsUs].
     */
    fun computeAnchorTimestampsUs(manifest: VoiceProjectManifest): Map<String, Long> =
        canonicalComputeAnchorTimestampsUs(manifest)

    /**
     * Returns the exact cumulative sample offset for every bound `videoAnchorId` under the
     * current `selectedTakeIndex` and `globalGapScale`.
     */
    fun computeAnchorSamples(manifest: VoiceProjectManifest): Map<String, Long> {
        val projection = ClipperTimelineProjection.fromManifest(manifest)
        val result = linkedMapOf<String, Long>()
        for (span in projection.wordSpans) {
            val anchorId = span.token.videoAnchorId
            if (!anchorId.isNullOrBlank()) {
                result[anchorId] = span.timelineZeroCrossIn
            }
        }
        return result
    }

    /**
     * Computes the total compressed timeline duration in microseconds.
     */
    fun computeTotalTimelineDurationUs(manifest: VoiceProjectManifest): Long {
        val projection = ClipperTimelineProjection.fromManifest(manifest)
        val rate = projection.sampleRate.coerceAtLeast(1).toLong()
        return (projection.totalTimelineSamples * 1_000_000L) / rate
    }

    /**
     * Builds exact Media3 [MediaItem.ClippingConfiguration] specs for every bound video anchor
     * present in [manifest], ordered chronologically by post-compression `zeroCrossIn` offset.
     */
    @OptIn(UnstableApi::class)
    fun buildMedia3ClippingSpecs(
        manifest: VoiceProjectManifest,
        videoBindings: Map<String, VideoAnchorBinding>
    ): List<Media3BeatClipSpec> {
        if (manifest.clauses.isEmpty() || videoBindings.isEmpty()) return emptyList()

        val projection = ClipperTimelineProjection.fromManifest(manifest)
        val anchorTimestampsUs = computeAnchorTimestampsUs(manifest)
        val totalDurationUs = computeTotalTimelineDurationUs(manifest)

        data class MatchedAnchor(
            val span: TimelineWordSpan,
            val binding: VideoAnchorBinding,
            val startUs: Long
        )

        // Preserve latest matching word span per videoAnchorId to match computeAnchorTimestampsUs.
        val matchedById = linkedMapOf<String, MatchedAnchor>()
        for (span in projection.wordSpans) {
            val anchorId = span.token.videoAnchorId ?: continue
            if (anchorId.isBlank()) continue
            val binding = videoBindings[anchorId] ?: continue
            val startUs = anchorTimestampsUs[anchorId] ?: continue
            matchedById[anchorId] = MatchedAnchor(span = span, binding = binding, startUs = startUs)
        }

        val ordered = matchedById.values.sortedBy { it.startUs }
        if (ordered.isEmpty()) return emptyList()

        return ordered.mapIndexed { index, entry ->
            val startUs = entry.startUs.coerceAtLeast(0L)
            val nextStartUs = ordered.getOrNull(index + 1)?.startUs ?: totalDurationUs
            val unboundedEndUs = max(nextStartUs, startUs + 1L)
            val maxDuration = entry.binding.maxClipDurationUs
            val endUs = if (maxDuration != null && maxDuration > 0L) {
                min(unboundedEndUs, startUs + maxDuration)
            } else {
                unboundedEndUs
            }

            val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(startUs)
                .setEndPositionUs(endUs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(entry.binding.videoAnchorId)
                .setUri(entry.binding.videoUri)
                .setClippingConfiguration(clippingConfiguration)
                .build()

            Media3BeatClipSpec(
                videoAnchorId = entry.binding.videoAnchorId,
                clauseId = entry.span.clauseId,
                word = entry.span.token.word,
                videoUri = entry.binding.videoUri,
                anchorSample = entry.span.timelineZeroCrossIn,
                startPositionUs = startUs,
                endPositionUs = endUs,
                clippingConfiguration = clippingConfiguration,
                mediaItem = mediaItem
            )
        }
    }

    /**
     * Convenience overload accepting a list of [VideoAnchorBinding]s.
     */
    fun buildMedia3ClippingSpecs(
        manifest: VoiceProjectManifest,
        videoBindings: List<VideoAnchorBinding>
    ): List<Media3BeatClipSpec> = buildMedia3ClippingSpecs(
        manifest = manifest,
        videoBindings = videoBindings.associateBy { it.videoAnchorId }
    )

    /**
     * Convenience overload accepting a map of `videoAnchorId -> videoUri`.
     */
    fun buildMedia3ClippingSpecsFromUris(
        manifest: VoiceProjectManifest,
        anchorUris: Map<String, String>
    ): List<Media3BeatClipSpec> = buildMedia3ClippingSpecs(
        manifest = manifest,
        videoBindings = anchorUris.mapValues { (anchorId, uri) ->
            VideoAnchorBinding(videoAnchorId = anchorId, videoUri = uri)
        }
    )

    /**
     * Builds an [androidx.media3.transformer.Composition] placing every bound video cut onto a
     * video [EditedMediaItemSequence] locked to its post-compression `zeroCrossIn` offset (with
     * explicit Media3 timeline gaps for leading/unanchored intervals), plus an optional master
     * voice audio sequence when [masterAudioUri] is supplied.
     */
    @OptIn(UnstableApi::class)
    fun buildComposition(
        manifest: VoiceProjectManifest,
        videoBindings: List<VideoAnchorBinding>,
        masterAudioUri: String? = null
    ): Composition {
        val specs = buildMedia3ClippingSpecs(manifest, videoBindings)
        require(specs.isNotEmpty() || !masterAudioUri.isNullOrBlank()) {
            "Cannot build Media3 Composition without bound video cuts or a master audio URI."
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()

        if (specs.isNotEmpty()) {
            val videoSeqBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            var cursorUs = 0L
            for (spec in specs) {
                val leadingGapUs = spec.startPositionUs - cursorUs
                if (leadingGapUs > 0L) {
                    videoSeqBuilder.addGap(leadingGapUs)
                }
                val editedVideoItem = EditedMediaItem.Builder(spec.mediaItem)
                    .setRemoveAudio(true)
                    .build()
                videoSeqBuilder.addItem(editedVideoItem)
                cursorUs = max(cursorUs, spec.endPositionUs)
            }
            sequences += videoSeqBuilder.build()
        }

        if (!masterAudioUri.isNullOrBlank()) {
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(masterAudioUri))
                .setRemoveVideo(true)
                .build()
            sequences += EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
        }

        return Composition.Builder(sequences).build()
    }
}
