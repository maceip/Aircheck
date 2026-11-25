package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * Single app-wide recycled Media3 ExoPlayer pool backed by `SimpleCache` (`cacheDir/media3_lru`)
 * + `CacheDataSource.Factory` so progressive `+faststart` MP4 streaming starts in <150 ms
 * and screens never allocate `ExoPlayer.Builder` inside composables.
 */
@OptIn(UnstableApi::class)
class AircheckPlayerController(
    context: Context,
    okHttpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private var simpleCache: SimpleCache? = null
    private var backingPlayer: ExoPlayer? = null
    private var currentUriString: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val dur = backingPlayer?.duration ?: 0L
                if (dur > 0L) _durationMs.value = dur
            }
        }
    }

    private val cacheFactory: CacheDataSource.Factory by lazy {
        val cacheDir = File(appContext.cacheDir, "media3_lru").apply { if (!exists()) mkdirs() }
        val cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
            StandaloneDatabaseProvider(appContext)
        ).also { simpleCache = it }

        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)

        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun acquirePlayer(): ExoPlayer {
        val existing = backingPlayer
        if (existing != null) return existing
        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            val nonGoldfish = codecs.filterNot { it.name.startsWith("c2.goldfish.", ignoreCase = true) }
            if (nonGoldfish.isNotEmpty()) {
                nonGoldfish + codecs.filter { it.name.startsWith("c2.goldfish.", ignoreCase = true) }
            } else {
                codecs
            }
        }
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)
        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(buildMediaAudioAttributes(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(listener)
                backingPlayer = this
            }
    }

    companion object {
        fun buildMediaAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

        /**
         * Android 17 dedicated assistant audio stream routing (`USAGE_ASSISTANT` + `AUDIO_CONTENT_TYPE_SPEECH`)
         * for spoken agent cues without ducking or overwriting user media volume.
         */
        fun buildAssistantAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANT)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build()
    }

    fun prepareUri(uri: Uri, autoPlay: Boolean = false) {
        val player = acquirePlayer()
        val uriStr = uri.toString()
        if (currentUriString != uriStr || player.playbackState == Player.STATE_IDLE) {
            currentUriString = uriStr
            _durationMs.value = 0L
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
        player.playWhenReady = autoPlay
    }

    fun togglePlayPause() {
        val player = backingPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            } else if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition()
            }
            player.play()
        }
    }

    fun pause() {
        backingPlayer?.pause()
    }

    fun seekTo(positionMs: Long) {
        backingPlayer?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun currentPositionMs(): Long = backingPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun durationMsNow(): Long = backingPlayer?.duration?.coerceAtLeast(0L) ?: _durationMs.value

    fun release() {
        backingPlayer?.removeListener(listener)
        runCatching { backingPlayer?.release() }
        backingPlayer = null
        currentUriString = null
        runCatching { simpleCache?.release() }
        simpleCache = null
    }
}
