package com.hamzawy.radio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.min

/**
 * Owns the player and the media session. It runs as a foreground media service, so the radio
 * keeps playing when the app is closed or the TV goes back to its home screen.
 *
 * It also reconnects by itself: when a stream drops it retries, then walks through the
 * station's alternate URLs, backing off up to 32 seconds between rounds.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private var activeNonce = 0L
    private var urlIndex = 0
    private var fails = 0
    private var hadSuccess = false

    private val retryRunnable = Runnable { retryNow() }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val nonce = mediaItem?.mediaMetadata?.extras?.getLong(EXTRA_NONCE, 0L) ?: 0L
            if (nonce != activeNonce) {
                // The app asked for a station (or for the same one again): start from its first URL.
                activeNonce = nonce
                urlIndex = 0
                fails = 0
                hadSuccess = false
                handler.removeCallbacks(retryRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    fails = 0
                    hadSuccess = true
                }
                Player.STATE_ENDED -> handler.post { onFailure() }
                else -> {
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.post { onFailure() }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // The user paused: stop any pending reconnect.
            if (!playWhenReady) handler.removeCallbacks(retryRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        player.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun urlsOf(item: MediaItem): List<String> =
        item.mediaMetadata.extras?.getStringArrayList(EXTRA_URLS) ?: emptyList()

    /** Called when the stream failed or ended: decide which URL to try next and when. */
    private fun onFailure() {
        val item = player.currentMediaItem ?: return
        if (!player.playWhenReady) return
        val urls = urlsOf(item)
        if (urls.isEmpty()) return
        handler.removeCallbacks(retryRunnable)

        var delayMs = 0L
        if (hadSuccess) {
            // It was playing and then dropped: retry the same URL straight away.
            hadSuccess = false
        } else {
            fails++
            if (fails > MAX_FAILS) return
            urlIndex = (urlIndex + 1) % urls.size
            if (fails >= urls.size) {
                // Every URL has been tried once: wait a little longer each round.
                delayMs = BASE_DELAY_MS shl min(fails - urls.size, 4)
            }
        }
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun retryNow() {
        val item = player.currentMediaItem ?: return
        if (!player.playWhenReady) return
        val urls = urlsOf(item)
        if (urls.isEmpty()) return
        val url = urls[urlIndex.coerceIn(0, urls.size - 1)]
        player.setMediaItem(item.buildUpon().setUri(url).build())
        player.prepare()
    }

    companion object {
        /** Key of the list of alternate stream URLs stored in each MediaItem's metadata extras. */
        const val EXTRA_URLS = "urls"

        /** Changes every time the app asks for a station, so a repeated request restarts the retry logic. */
        const val EXTRA_NONCE = "nonce"

        private const val USER_AGENT = "HamzawyRadio/1.0 (Android TV)"
        private const val MAX_FAILS = 40
        private const val BASE_DELAY_MS = 2_000L
    }
}
