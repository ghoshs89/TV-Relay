package dev.relay.tv.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.WatchEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val app get() = RelayApp.instance
    private lateinit var req: PlayRequest
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView

    private var liveIndex = 0
    private var hostAttempt = 0
    private var liveRetries = 0
    private var lastItem: PlayItem? = null
    private var lastDuration = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = app.pendingPlay
        if (r == null || r.items.isEmpty() || app.api.account == null) {
            finish()
            return
        }
        req = r
        AppLog.i("Player", "Open ${if (r.live) "live" else r.items[r.index].kind}: ${r.items[r.index].title} (${r.items.size} in queue)")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildPlayer()
        buildViews()
        if (req.live) playLive(req.index) else startVod()

        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress(finished = false)
            }
        }
    }

    // ---------- Setup ----------

    private fun buildPlayer() {
        val s = app.store.settings
        val dataSource = OkHttpDataSource.Factory(app.http).setUserAgent(s.userAgent.ifBlank { "RelayTV/1.0" })
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
        // ON = use FFmpeg only when the device can't decode a format itself (keeps AC3 passthrough).
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(s.audioLang.ifBlank { null })
                    .setPreferredTextLanguage(s.subtitleLang.ifBlank { null }),
            )
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 4_000)
            .build()
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        player.addListener(listener)
    }

    private fun buildViews() {
        val multi = !req.live && req.items.size > 1
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4_000
            setShowNextButton(multi)
            setShowPreviousButton(multi)
            setShowFastForwardButton(!req.live)
            setShowRewindButton(!req.live)
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(36, 18, 36, 18)
            setBackgroundColor(0xDD0B1220.toInt())
            visibility = View.GONE
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(48, 40, 48, 0) },
        )
        setContentView(root)
        view.requestFocus()
    }

    // ---------- Loading ----------

    private fun urlsFor(item: PlayItem): List<String> =
        item.urls.ifEmpty { app.api.streamUrls(item.kind, item.streamId, item.ext) }

    private fun mediaItem(item: PlayItem, attempt: Int): MediaItem {
        val urls = urlsFor(item)
        val url = if (urls.isEmpty()) "" else urls[attempt % urls.size]
        val b = MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.key)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build(),
            )
        if (url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun startVod() {
        val start = req.index.coerceIn(0, req.items.lastIndex)
        player.setMediaItems(req.items.map { mediaItem(it, 0) }, start, req.items[start].resumeMs.coerceAtLeast(0))
        player.pauseAtEndOfMediaItems = !app.store.settings.autoNext
        player.prepare()
        player.playWhenReady = true
        lastItem = req.items[start]
        showOverlay(label(req.items[start]))
    }

    private fun playLive(index: Int) {
        val n = req.items.size
        liveIndex = ((index % n) + n) % n
        hostAttempt = 0
        liveRetries = 0
        val item = req.items[liveIndex]
        player.setMediaItem(mediaItem(item, 0))
        player.prepare()
        player.playWhenReady = true
        showOverlay(label(item))
        app.store.record(
            req.profileId,
            WatchEntry(
                key = item.key, kind = "live", title = item.title, subtitle = item.subtitle,
                image = item.image, streamId = item.streamId, ext = item.ext,
                updated = System.currentTimeMillis(),
            ),
        )
    }

    private fun reconnectLive() {
        val item = currentItem() ?: return
        player.setMediaItem(mediaItem(item, hostAttempt))
        player.prepare()
        player.playWhenReady = true
    }

    private fun currentItem(): PlayItem? =
        if (req.live) req.items.getOrNull(liveIndex) else req.items.getOrNull(player.currentMediaItemIndex)

    private fun label(item: PlayItem): String = when (item.kind) {
        "live" -> listOfNotNull(item.subtitle, item.title).joinToString("   ")
        else -> listOfNotNull(item.title, item.subtitle).joinToString("  ·  ")
    }

    // ---------- Events ----------

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (req.live) return
            hostAttempt = 0
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                lastItem?.let { prev -> if (lastDuration > 0) record(prev, lastDuration, lastDuration) }
            }
            lastItem = currentItem()
            lastDuration = 0
            currentItem()?.let { showOverlay(label(it)) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                if (liveRetries > 0 || hostAttempt > 0) AppLog.i("Player", "Recovered after retry")
                AppLog.d("Player", "Ready: ${currentItem()?.title} via ${hostOf(currentItem()?.let { urlsFor(it).getOrNull(hostAttempt) })}")
                liveRetries = 0
                if (player.duration != C.TIME_UNSET) lastDuration = player.duration
            }
            if (state == Player.STATE_ENDED) {
                if (req.live) {
                    reconnectLive()
                } else {
                    saveProgress(finished = true)
                    finish()
                }
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val item = currentItem() ?: return
        AppLog.e("Player", "${e.errorCodeName} on ${item.kind} '${item.title}' via ${hostOf(urlsFor(item).getOrNull(hostAttempt))}", e)
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val urls = urlsFor(item)
        if (hostAttempt + 1 < urls.size) {
            hostAttempt++
            val pos = player.currentPosition
            val idx = if (req.live) 0 else player.currentMediaItemIndex
            player.replaceMediaItem(idx, mediaItem(item, hostAttempt))
            if (!req.live && pos > 0) player.seekTo(idx, pos)
            player.prepare()
            player.playWhenReady = true
            showOverlay("Trying backup server…")
            AppLog.w("Player", "Switching to server ${hostOf(urls.getOrNull(hostAttempt))}")
            return
        }
        if (req.live && liveRetries < 3) {
            liveRetries++
            hostAttempt = 0
            showOverlay("Reconnecting…")
            handler.postDelayed({ reconnectLive() }, 2_000)
            return
        }
        showOverlay("Can't play this stream (${e.errorCodeName})", 8_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            if (req.live) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        playLive(liveIndex - 1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        playLive(liveIndex + 1); return true
                    }
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        player.seekBack(); view.showController(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        player.seekForward(); view.showController(); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Progress ----------

    private fun saveProgress(finished: Boolean) {
        if (!::player.isInitialized || req.live) return
        val item = currentItem() ?: return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        lastDuration = dur
        record(item, if (finished) dur else player.currentPosition, dur)
    }

    private fun record(item: PlayItem, position: Long, duration: Long) {
        if (item.kind != "movie" && item.kind != "episode") return
        app.store.record(
            req.profileId,
            WatchEntry(
                key = item.key, kind = item.kind, title = item.title, subtitle = item.subtitle,
                image = item.image, streamId = item.streamId, ext = item.ext, seriesId = item.seriesId,
                position = position, duration = duration, updated = System.currentTimeMillis(),
            ),
        )
    }

    private fun hostOf(url: String?): String = url?.let { android.net.Uri.parse(it).host } ?: "?"

    private fun showOverlay(text: String, ms: Long = 3_000) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, ms)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveProgress(finished = false)
            player.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            player.removeListener(listener)
            player.release()
        }
        super.onDestroy()
    }
}
