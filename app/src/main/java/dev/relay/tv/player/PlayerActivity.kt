package dev.relay.tv.player

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.relay.tv.ui.ReminderCenter
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.compose.runtime.snapshotFlow
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.relay.tv.data.PlaybackDiagnosis
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.WatchEntry
import dev.relay.tv.data.LazyItems
import dev.relay.tv.tv.WatchNext
import kotlinx.coroutines.Dispatchers
import dev.relay.tv.ui.RelayTheme
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
    private lateinit var infoView: TextView
    private lateinit var channelsView: ComposeView
    private lateinit var channelsButton: TextView
    private lateinit var bandwidth: DefaultBandwidthMeter
    private lateinit var frameRate: FrameRateMatcher

    private var liveIndex = 0
    private var previousLiveIndex: Int? = null
    private var hostAttempt = 0
    private var liveRetries = 0
    private var lastItem: PlayItem? = null
    private var lastDuration = 0L

    // Back: second press within this window exits.
    private var lastBackAt = 0L

    // Number keys typed on the remote, applied after a short pause.
    private val digits = ChannelDigits()

    // Stream info shows once per new item, when it first becomes ready.
    private var infoPending = false

    // Audio and subtitle languages, and the per-series memory of what the viewer picked.
    private val trackPrefs by lazy { PlayerTracks(app) }

    private var channelOverlayOpen by mutableStateOf(false)

    // Pause and rewind for live TV.
    private var timeshift: TimeshiftBuffer? = null
    private var tsOffset = 0L
    private val tsDisabled = mutableSetOf<String>()
    private var stoppedInBackground = false
    private lateinit var tsSource: ProgressiveMediaSource.Factory
    private lateinit var tsBar: TimeshiftBar
    private val tsTicker = object : Runnable {
        override fun run() {
            updateTsBar()
            handler.postDelayed(this, 500)
        }
    }

    // Programme reminder shown over the video.
    private lateinit var reminder: ReminderBanner

    // Stuck-buffering recovery, and the panel shown when a stream can't be recovered.
    private val liveWatch = StallWatchdog(StallWatchdog.LIVE_MS) { SystemClock.elapsedRealtime() }
    private val vodWatch = StallWatchdog(StallWatchdog.VOD_MS) { SystemClock.elapsedRealtime() }
    private var liveStalls = 0
    /** This channel version has played at least once since it was tuned. */
    private var liveReady = false
    private var vodStalls = 0
    private var failPos = 0L
    private var lastHttp: Int? = null
    private var lastErrorName = "STALL"
    private var diagnoseJob: kotlinx.coroutines.Job? = null
    private lateinit var failure: FailurePanel
    private val watchdog = object : Runnable {
        override fun run() {
            checkStall()
            if (::limits.isInitialized) limits.tick(SystemClock.elapsedRealtime(), ::player.isInitialized && player.isPlaying)
            handler.postDelayed(this, 1_000)
        }
    }

    // ---------- Kids viewing limits ----------

    /** Counts time actually playing on kids profiles and stops at the daily limit or bedtime. */
    private lateinit var limits: KidsLimitTracker

    private fun stopForLimit(reason: String) {
        AppLog.i("Access", "Viewing limit reached while playing: $reason")
        saveProgress(finished = false)
        player.stop()
        stopTimeshift()
        diagnoseJob?.cancel()
        overlay.visibility = View.GONE
        view.hideController()
        failure.showTimesUp(reason)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val hideInfo = Runnable { infoView.visibility = View.GONE }
    private val applyDigits = Runnable { jumpToNumber() }

    private val isTv: Boolean by lazy {
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = app.pendingPlay
        if (r == null || r.items.isEmpty() || app.api.account == null) {
            finish()
            return
        }
        req = r
        limits = KidsLimitTracker(
            enabled = app.store.profiles.firstOrNull { it.id == r.profileId }?.kids == true,
            addUsage = { app.store.addUsage(req.profileId, it) },
            verdict = { app.store.viewingVerdict(req.profileId) },
            warn = { showOverlay(it, 6_000) },
            onLimit = ::stopForLimit,
        )
        AppLog.i("Player", "Open${if (r.live) "live" else r.items[r.index].kind}: ${r.items[r.index].title} (${r.items.size} in queue)")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frameRate = FrameRateMatcher(this)
        buildPlayer()
        buildViews()
        if (req.live) playLive(req.index) else startVod()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress(finished = false)
            }
        }
        lifecycleScope.launch {
            snapshotFlow { ReminderCenter.due }.collect { r -> if (r != null && r != reminder.shown) reminder.show(r) }
        }
        handler.post(tsTicker)
        handler.post(watchdog)
    }

    // ---------- Setup ----------

    private fun buildPlayer() {
        val s = app.store.settings
        // Streams give up after 15 s without data (menus keep the longer default) so recovery starts sooner.
        val streamHttp = app.http.newBuilder().readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val dataSource = OkHttpDataSource.Factory(streamHttp).setUserAgent(s.userAgent.ifBlank { "RelayTV/1.0" })
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
        tsSource = ProgressiveMediaSource.Factory(
            TimeshiftDataSource.Factory { timeshift },
            DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            ),
        )
        // ON = use FFmpeg only when the device can't decode a format itself (keeps AC3 passthrough).
        val renderers = RelayRenderersFactory(this, s.audioDelayMs)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages(*trackPrefs.audioOrder().toTypedArray())
                    .setPreferredTextLanguages(*trackPrefs.textOrder().toTypedArray()),
            )
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 4_000)
            .build()
        bandwidth = DefaultBandwidthMeter.getSingletonInstance(this)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setBandwidthMeter(bandwidth)
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
        if (s.audioDelayMs != 0) AppLog.i("Player", "Lip-sync offset ${s.audioDelayMs} ms")
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
        trackPrefs.styleSubtitles(view)

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(36, 18, 36, 18)
            setBackgroundColor(0xDD0B1220.toInt())
            visibility = View.GONE
        }
        infoView = TextView(this).apply {
            setTextColor(0xFFE8EEF7.toInt())
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(28, 14, 28, 14)
            setBackgroundColor(0xC0000000.toInt())
            visibility = View.GONE
        }
        channelsView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                RelayTheme {
                    if (channelOverlayOpen) {
                        ChannelOverlay(
                            items = req.items,
                            current = liveIndex,
                            onPick = { i ->
                                closeChannelOverlay()
                                switchLive(i)
                            },
                        )
                    }
                }
            }
        }

        channelsButton = TextView(this).apply {
            text = "☰  Channels"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 18, 32, 18)
            setBackgroundColor(0xCC1E2B45.toInt())
            visibility = View.GONE
            isFocusable = false
            setOnClickListener {
                view.hideController()
                openChannelOverlay()
            }
        }
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                channelsButton.visibility = if (req.live && visibility == View.VISIBLE) View.VISIBLE else View.GONE
            },
        )

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
        root.addView(
            infoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(48, 0, 48, 48) },
        )
        root.addView(
            channelsButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(48, 40, 48, 0) },
        )
        tsBar = TimeshiftBar(this, touchControls = !isTv, onSeek = ::tsSeek, onLive = ::tsGoLive)
        tsBar.addTo(root)
        reminder = ReminderBanner(this, isTv, handler) { r ->
            saveProgress(finished = false)
            ReminderCenter.watch(this, r)
            finish()
        }
        reminder.addTo(root)
        failure = FailurePanel(
            this,
            onRetry = { retryAfterFailure() },
            onNext = {
                hideFailure()
                switchLive(liveIndex + 1)
            },
        )
        failure.addTo(root)
        root.addView(channelsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        view.requestFocus()
    }

    private val failing: Boolean get() = ::failure.isInitialized && failure.showing

    /** Stops trying and offers Retry (and Next channel on live TV). */
    private fun showFailure(message: String) {
        AppLog.e("Player", "Gave up: $message (${currentItem()?.title})")
        failPos = if (req.live) 0L else player.currentPosition
        player.stop()
        stopTimeshift()
        overlay.visibility = View.GONE
        view.hideController()
        failure.setMessage(message, "Checking what went wrong…")
        diagnose()
        failure.show(canSkip = req.live && req.items.size > 1)
    }

    /** Checks the account and the last error, then explains the failure in plain words. */
    private fun diagnose() {
        diagnoseJob?.cancel()
        val http = lastHttp
        val err = lastErrorName
        diagnoseJob = lifecycleScope.launch {
            val login = try {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { app.api.authenticate() }
                    ?.let { PlaybackDiagnosis.Login.Ok(it, System.currentTimeMillis() / 1000) }
                    ?: PlaybackDiagnosis.Login.Unreachable
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("rejected", ignoreCase = true) == true) PlaybackDiagnosis.Login.Rejected(e.message.orEmpty())
                else PlaybackDiagnosis.Login.Unreachable
            }
            val r = PlaybackDiagnosis.diagnose(http, err, login, req.live)
            AppLog.w("Player", "Diagnosis: ${r.title} (error $err, HTTP ${http ?: "-"})")
            if (failing) failure.setMessage(r.title, r.hint)
        }
    }

    private fun httpCodeOf(e: Throwable?): Int? {
        var c = e
        while (c != null) {
            if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return null
    }

    private fun hideFailure() {
        failure.hide()
        view.requestFocus()
    }

    private fun retryAfterFailure() {
        if (limits.reached) {
            finish()
            return
        }
        hideFailure()
        val item = currentItem() ?: return
        AppLog.i("Player", "Retry requested for ${item.title}")
        if (req.live) {
            playLive(liveIndex)
            return
        }
        hostAttempt = 0
        vodStalls = 0
        val idx = player.currentMediaItemIndex
        player.replaceMediaItem(idx, mediaItem(item, 0))
        player.seekTo(idx, failPos)
        player.prepare()
        player.playWhenReady = true
    }

    /** Runs every second: reconnects when playback has been stuck buffering too long. */
    private fun checkStall() {
        if (!::player.isInitialized || failing || stoppedInBackground) return
        val buffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        val watch = if (req.live) liveWatch else vodWatch
        if (watch.tick(buffering)) onStall()
    }

    private fun onStall() {
        val item = currentItem() ?: return
        lastErrorName = "STALL"
        lastHttp = null
        val urls = urlsFor(item)
        if (req.live) {
            liveStalls++
            AppLog.w("Player", "Live '${item.title}' stuck buffering (reconnect $liveStalls of ${StallWatchdog.LIVE_TRIES})")
            if (liveStalls > StallWatchdog.LIVE_TRIES || (!liveReady && liveStalls > 1)) {
                if (tryNextVersion()) return
                showFailure("This channel isn't responding")
                return
            }
            if (timeshift != null) {
                // The recorder may be stuck too: play this channel directly from now on.
                tsDisabled += item.key
                stopTimeshift()
            }
            hostAttempt = if (urls.isEmpty()) 0 else (hostAttempt + 1) % urls.size
            showOverlay("Reconnecting…")
            player.setMediaItem(mediaItem(item, hostAttempt))
            player.prepare()
            player.playWhenReady = true
            return
        }
        vodStalls++
        AppLog.w("Player", "'${item.title}' stuck buffering (attempt $vodStalls of ${urls.size})")
        if (vodStalls > urls.size.coerceAtLeast(1)) {
            showFailure("This video isn't responding")
            return
        }
        hostAttempt = if (urls.isEmpty()) 0 else (hostAttempt + 1) % urls.size
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        player.replaceMediaItem(idx, mediaItem(item, hostAttempt))
        player.seekTo(idx, pos)
        player.prepare()
        player.playWhenReady = true
        showOverlay(if (urls.size > 1) "Trying backup server…" else "Reconnecting…")
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
        trackPrefs.apply(player, req.items[start])
        player.setMediaItems(req.items.map { mediaItem(it, 0) }, start, req.items[start].resumeMs.coerceAtLeast(0))
        player.pauseAtEndOfMediaItems = !app.store.settings.autoNext
        player.prepare()
        player.playWhenReady = true
        lastItem = req.items[start]
        infoPending = true
        showOverlay(label(req.items[start]))
    }

    /** Changes channel and remembers where we came from, for the Back flip. */
    private fun switchLive(index: Int) {
        val n = req.items.size
        val target = ((index % n) + n) % n
        if (target == liveIndex) return
        previousLiveIndex = liveIndex
        playLive(target)
    }

    private fun playLive(index: Int) {
        val n = req.items.size
        liveIndex = ((index % n) + n) % n
        hostAttempt = 0
        liveRetries = 0
        liveStalls = 0
        liveReady = false
        liveWatch.reset()
        if (failing) hideFailure()
        val item = req.items[liveIndex]
        stopTimeshift()
        val s = app.store.settings
        // The pause buffer records MPEG-TS; HLS channels play directly.
        if (s.timeshift && item.key !in tsDisabled && TimeshiftSupport.supports(urlsFor(item))) {
            val ts = TimeshiftBuffer(
                java.io.File(cacheDir, "timeshift"), app.http, urlsFor(item),
                s.userAgent.ifBlank { "RelayTV/1.0" }, s.timeshiftMinutes,
            )
            timeshift = ts
            ts.start()
            tsOffset = 0L
            player.setMediaSource(tsSource.createMediaSource(tsItem(item, 0L)))
        } else {
            player.setMediaItem(mediaItem(item, 0))
        }
        player.prepare()
        player.playWhenReady = true
        infoPending = true
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
        if (stoppedInBackground) return
        if (timeshift != null) {
            // The pause buffer reconnects on its own; restart cleanly at live.
            playLive(liveIndex)
            return
        }
        val item = currentItem() ?: return
        player.setMediaItem(mediaItem(item, hostAttempt))
        player.prepare()
        player.playWhenReady = true
    }

    private fun tsItem(item: PlayItem, offset: Long): MediaItem = MediaItem.Builder()
        .setUri(TimeshiftBuffer.uriAt(offset))
        .setMediaId(item.key)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build())
        .build()

    private fun stopTimeshift() {
        timeshift?.close()
        timeshift = null
        if (::tsBar.isInitialized) tsBar.hide()
    }

    /** Byte position of what's on screen now, estimated from the recording's average bitrate. */
    private fun tsPlayingByte(): Long {
        val b = timeshift ?: return 0L
        return TimeshiftMath.playingByte(tsOffset, player.currentPosition, b.bytesPerSecond)
    }

    private fun tsBehindSec(): Int {
        val b = timeshift ?: return 0
        return TimeshiftMath.behindSec(b.written, tsPlayingByte(), b.bytesPerSecond)
    }

    private fun tsSeek(deltaSec: Int) {
        val b = timeshift ?: return
        val rate = b.bytesPerSecond
        if (rate <= 0) {
            showOverlay("Pause buffer is still filling…")
            return
        }
        val item = currentItem() ?: return
        val target = TimeshiftMath.seekTarget(tsPlayingByte(), deltaSec, rate, b.written, b.firstAvailable)
        tsOffset = target
        player.setMediaSource(tsSource.createMediaSource(tsItem(item, target)))
        player.prepare()
        player.play()
        updateTsBar()
    }

    private fun tsGoLive() = tsSeek(Int.MAX_VALUE / 4)

    private fun updateTsBar() {
        if (!::tsBar.isInitialized) return
        val b = timeshift
        if (b == null || channelOverlayOpen) {
            tsBar.hide()
            return
        }
        val text = TimeshiftMath.barText(tsBehindSec(), paused = !player.playWhenReady, tv = isTv)
        if (text == null) tsBar.hide() else tsBar.show(text)
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
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) infoPending = true
            currentItem()?.let { showOverlay(label(it)) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                if (liveRetries > 0 || hostAttempt > 0) AppLog.i("Player", "Recovered after retry")
                AppLog.d("Player", "Ready: ${currentItem()?.title} via ${hostOf(currentItem()?.let { urlsFor(it).getOrNull(hostAttempt) })}")
                liveRetries = 0
                liveStalls = 0
                vodStalls = 0
                if (req.live) liveReady = true
                if (player.duration != C.TIME_UNSET) lastDuration = player.duration
                onReady()
            }
            if (state == Player.STATE_ENDED) {
                if (req.live) {
                    // The server closed the stream. Reconnect, but not forever.
                    if (liveRetries < 3) {
                        liveRetries++
                        AppLog.w("Player", "Live stream ended; reconnecting ($liveRetries of 3)")
                        reconnectLive()
                    } else if (!tryNextVersion()) {
                        showFailure("This channel isn't responding")
                    }
                } else {
                    saveProgress(finished = true)
                    finish()
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            trackPrefs.onSelectionChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            trackPrefs.onTracksChanged(currentItem(), tracks)
        }
    }

    /** First READY of each item: match the refresh rate and flash the stream info. */
    private fun onReady() {
        val video = player.videoFormat
        if (app.store.settings.matchFrameRate && isTv) {
            video?.frameRate?.let { frameRate.match(it) }
        }
        if (infoPending) {
            infoPending = false
            if (app.store.settings.showStreamInfo) {
                val text = StreamInfo.describe(video, player.audioFormat, bandwidth.bitrateEstimate)
                if (text.isNotBlank()) {
                    infoView.text = text
                    infoView.visibility = View.VISIBLE
                    handler.removeCallbacks(hideInfo)
                    handler.postDelayed(hideInfo, 5_000)
                }
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val item = currentItem() ?: return
        lastErrorName = e.errorCodeName
        lastHttp = httpCodeOf(e)
        AppLog.e("Player", "${e.errorCodeName} on ${item.kind} '${item.title}' via ${hostOf(urlsFor(item).getOrNull(hostAttempt))}", e)
        if (req.live && timeshift != null) {
            AppLog.w("Timeshift", "Pause buffer playback failed for ${item.title}; playing directly")
            tsDisabled += item.key
            playLive(liveIndex)
            showOverlay("Pause isn't available on this channel")
            return
        }
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
        // A version that never started is likely broken: go straight to the next one.
        if (req.live && !liveReady && tryNextVersion()) return
        if (req.live && liveRetries < 3) {
            liveRetries++
            hostAttempt = 0
            showOverlay("Reconnecting…")
            handler.postDelayed({ if (!failing) reconnectLive() }, 2_000)
            return
        }
        if (req.live && tryNextVersion()) return
        showFailure(if (req.live) "This channel isn't responding" else "Can't play this video (${e.errorCodeName})")
    }

    /** Swaps the current channel for its next version (HD, FHD, backup), if it has one left. */
    private fun tryNextVersion(): Boolean {
        if (!req.live) return false
        val item = req.items.getOrNull(liveIndex) ?: return false
        // Entries from long lists don't carry their versions yet; work them out the first time a channel fails.
        val alts = if (item.alternatesKnown) item.alternates
        else item.streamId.toIntOrNull()?.let { req.fallbacks?.invoke(it) }.orEmpty()
        val altId = alts.firstOrNull() ?: return false
        val ch = altId.toIntOrNull()?.let { app.catalog.channelById[it] } ?: return false
        val next = item.copy(
            key = "live:${ch.id}", title = ch.name, streamId = altId, image = ch.logo ?: item.image,
            alternates = alts.drop(1), alternatesKnown = true,
        )
        val queue = req.items
        req = req.copy(items = (queue as? LazyItems)?.with(liveIndex, next) ?: queue.toMutableList().also { it[liveIndex] = next })
        AppLog.w("Player", "'${item.title}' failed; trying another version: ${ch.name}")
        playLive(liveIndex)
        showOverlay("Trying another version\n${ch.name}")
        return true
    }

    // ---------- Back ----------

    private fun onBack() {
        if (failing) {
            saveProgress(finished = false)
            finish()
            return
        }
        if (reminder.shown != null) {
            reminder.dismiss()
            return
        }
        if (channelOverlayOpen) {
            closeChannelOverlay()
            return
        }
        if (view.isControllerFullyVisible) {
            view.hideController()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < BACK_WINDOW_MS) {
            saveProgress(finished = false)
            finish()
            return
        }
        lastBackAt = now
        val prev = previousLiveIndex
        if (req.live && prev != null) {
            // Live: Back flips to the previous channel; a quick second Back exits.
            switchLive(prev)
            showOverlay("${label(req.items[liveIndex])}\nPress Back again to exit", BACK_WINDOW_MS)
        } else {
            showOverlay("Press Back again to exit", BACK_WINDOW_MS)
        }
    }

    // ---------- Channel list overlay ----------

    private fun openChannelOverlay() {
        channelOverlayOpen = true
        channelsView.visibility = View.VISIBLE
        channelsView.requestFocus()
    }

    private fun closeChannelOverlay() {
        channelOverlayOpen = false
        channelsView.visibility = View.GONE
        view.requestFocus()
    }

    // ---------- Number keys ----------

    private fun digitPressed(d: Int) {
        showOverlay("Channel  ${digits.press(d)}", 1_800)
        handler.removeCallbacks(applyDigits)
        handler.postDelayed(applyDigits, 1_500)
    }

    /** Finds the number in the current list first, then across all channels. */
    private fun jumpToNumber() {
        val num = digits.take() ?: return
        val inList = (req.items as? LazyItems)?.indexOfNumber(num) ?: req.items.indexOfFirst { it.subtitle == num.toString() }
        if (inList >= 0) {
            switchLive(inList)
            return
        }
        // Restricted profiles can't reach a channel by number that they couldn't browse to.
        val access = app.catalog.restrictions(app.session.restrict(app.store.settings))
        val ch = access.findByNumber(app.catalog.channels, num)
        if (ch == null) {
            showOverlay("No channel $num")
            return
        }
        // Switch to that channel's category so Up/Down keep making sense.
        val list = app.catalog.channelsByCat[ch.categoryId].orEmpty().filter { access.allowsCategory(it.categoryId) }.ifEmpty { listOf(ch) }
        val items = Playback.liveItems(list)
        val prevItem = req.items.getOrNull(liveIndex)
        req = req.copy(items = items, index = items.indexOfChannel(ch.id).coerceAtLeast(0))
        // Keep the flip-back target valid in the new list when possible.
        previousLiveIndex = prevItem?.streamId?.toIntOrNull()?.let { items.indexOfChannel(it) }?.takeIf { it >= 0 }
        playLive(req.index)
    }

    // ---------- Keys ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (failing && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // Retry / Next channel buttons take the remote while the failure panel is up.
            return super.dispatchKeyEvent(event)
        }
        if (channelOverlayOpen) {
            // The overlay handles navigation; Back is routed through onBack().
            return super.dispatchKeyEvent(event)
        }
        val r = reminder.shown
        if (r != null && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            reminder.watch(r)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && req.live && timeshift != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    tsSeek(-10); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    tsSeek(30); return true
                }
            }
            // While paused or behind live, Left/Right rewind and skip instead of opening menus.
            val shifted = !player.playWhenReady || tsBehindSec() > 3
            if (shifted && !view.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        tsSeek(-10); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        tsSeek(30); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!player.playWhenReady) {
                        player.play(); return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            if (req.live) {
                val code = event.keyCode
                if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                    digitPressed(code - KeyEvent.KEYCODE_0)
                    return true
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        switchLive(liveIndex - 1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        switchLive(liveIndex + 1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        openChannelOverlay(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> {
                        // Live controls (audio and subtitle tracks) live under the controller.
                        view.showController(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        openChannelOverlay(); return true
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

    // ---------- Picture-in-picture (phones) ----------

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 26 && !isTv &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val size = player.videoSize
        val (w, h) = PipRatio.safe(size.width, size.height)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying)
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically via setAutoEnterEnabled; older phones need this call.
        if (pipSupported && Build.VERSION.SDK_INT in 26..30 && player.isPlaying) {
            pipParams()?.let { enterPictureInPictureMode(it) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            overlay.visibility = View.GONE
            infoView.visibility = View.GONE
            if (channelOverlayOpen) closeChannelOverlay()
        }
    }

    private fun refreshPipParams() {
        if (pipSupported && Build.VERSION.SDK_INT >= 31) pipParams()?.let { setPictureInPictureParams(it) }
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
        val entry = WatchEntry(
            key = item.key, kind = item.kind, title = item.title, subtitle = item.subtitle,
            image = item.image, streamId = item.streamId, ext = item.ext, seriesId = item.seriesId,
            position = position, duration = duration, updated = System.currentTimeMillis(),
        )
        app.store.record(req.profileId, entry)
        val pid = req.profileId
        val ctx = applicationContext
        app.appScope.launch(Dispatchers.IO) { WatchNext.update(ctx, pid, entry) }
    }

    private fun hostOf(url: String?): String = url?.let { android.net.Uri.parse(it).host } ?: "?"

    private fun showOverlay(text: String, ms: Long = 3_000) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, ms)
    }

    override fun onResume() {
        super.onResume()
        refreshPipParams()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedInBackground) {
            // Came back after leaving the app: resume live, with a fresh pause buffer.
            stoppedInBackground = false
            playLive(liveIndex)
        } else if (::player.isInitialized) {
            player.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::limits.isInitialized) {
            limits.flush()
            limits.pause()
        }
        if (::player.isInitialized) {
            saveProgress(finished = false)
            player.pause()
            // Don't keep recording (and using the connection) while Relay is in the background.
            if (req.live && timeshift != null && !isInPictureInPictureMode) {
                stoppedInBackground = true
                player.stop()
                stopTimeshift()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopTimeshift()
        if (::frameRate.isInitialized) frameRate.restore()
        if (::player.isInitialized) {
            player.removeListener(listener)
            player.release()
        }
        super.onDestroy()
    }

    companion object {
        private const val BACK_WINDOW_MS = 2_500L
    }
}
