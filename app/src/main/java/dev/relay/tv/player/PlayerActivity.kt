package dev.relay.tv.player

import android.annotation.SuppressLint
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
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.Toast
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.relay.tv.ui.ReminderCenter
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val app get() = RelayApp.instance
    private lateinit var req: PlayRequest

    /** The request this player was opened with, so closing can release it (it can hold a whole channel list). */
    private var openedWith: PlayRequest? = null
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private lateinit var pauseTitle: TextView
    private var pausedItemKey: String? = null
    private var pausedAtSec: Long? = null
    private var pauseGuideJob: Job? = null
    private lateinit var infoView: TextView

    /** Centred prompt shown after the first Back: says what a second one will do. */
    private lateinit var leaveHint: TextView
    private lateinit var channelsView: ComposeView
    private lateinit var channelsButton: TextView

    /** Phones only: hands what is playing to a TV of the household ("Play on TV"). */
    private lateinit var playOnTvButton: TextView
    private var playOnTvOpen by mutableStateOf(false)
    private lateinit var bandwidth: DefaultBandwidthMeter
    private lateinit var frameRate: FrameRateMatcher

    private var liveIndex = 0
    private var previousLiveIndex: Int? = null
    private var hostAttempt = 0
    private var liveRetries = 0
    private var behindLiveRetries = 0
    private var lastItem: PlayItem? = null
    private var lastDuration = 0L

    // Back: second press within this window exits.
    private var lastBackAt = 0L

    // Number keys typed on the remote, applied after a short pause.
    private val digits = ChannelDigits()

    // Stream info shows once per new item, when it first becomes ready.
    private var infoPending = false

    // When the current channel or video was asked for, to log how long the first picture took.
    private var tuneStartedAt = 0L
    private var skipBackSec = SkipSizes.DEFAULT_BACK
    private var skipForwardSec = SkipSizes.DEFAULT_FORWARD
    private var recordLiveWatch = Runnable { }
    private val retryLiveRunnable = Runnable { if (!failing && !stoppedInBackground) reconnectLive() }
    private val clearLiveRetries = Runnable { liveRetries = 0 }

    // Audio and subtitle languages, and the per-series memory of what the viewer picked.
    private val trackPrefs by lazy { PlayerTracks(app) }

    private var channelOverlayOpen by mutableStateOf(false)

    // Pause and rewind for live TV.
    private var timeshift: TimeshiftBuffer? = null
    private var tsOffset = 0L
    private val tsDisabled = mutableSetOf<String>()
    private val playlistSources = mutableSetOf<String>()
    private var liveUrls = emptyList<String>()
    @Volatile private var probeLiveUrl: String? = null

    private class LivePlaylist : IOException("Live source returned an HLS playlist")
    private var stoppedInBackground = false
    private var resumeVodAfterStop: Boolean? = null
    private lateinit var tsSource: ProgressiveMediaSource.Factory
    private lateinit var hlsSource: HlsMediaSource.Factory
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
            val playing = ::player.isInitialized && player.isPlaying
            if (::limits.isInitialized) limits.tick(SystemClock.elapsedRealtime(), playing)
            if (playing) app.telemetry.onPlaybackSecond(req.live)
            watchBreaks()
            updateUpNext()
            handler.postDelayed(this, 1_000)
        }
    }

    // ---------- Sleep timer and "Are you still watching?" ----------

    private lateinit var sleepTimer: SleepTimer
    private lateinit var stillWatching: StillWatching
    private var sleepMinutes = 0

    /** Once a second: the sleep timer's warning and stop, and the still-watching question. */
    private fun watchBreaks() {
        if (!::sleepTimer.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        when (sleepTimer.tick(now)) {
            SleepTimer.Event.Warn -> showOverlay("Sleep timer: stopping in 1 minute\nPress any key to keep watching", 15_000)
            SleepTimer.Event.Stop -> {
                AppLog.i("Player", "Sleep timer ended playback after $sleepMinutes minutes")
                saveProgress(finished = false)
                Toast.makeText(this, "Sleep timer: playback stopped", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            SleepTimer.Event.None -> Unit
        }
        when (stillWatching.tick(now, req.live, ::player.isInitialized && player.isPlaying)) {
            StillWatching.Event.Ask -> askStillWatching()
            StillWatching.Event.Stop -> {
                AppLog.i("Player", "Nobody answered \"Are you still watching?\"; closing the player")
                saveProgress(finished = false)
                finish()
            }
            StillWatching.Event.None -> Unit
        }
    }

    /** Pauses and asks. Keep watching resumes (live goes back to the live edge). */
    private fun askStillWatching() {
        AppLog.i("Player", "Asking whether anyone is still watching")
        handler.removeCallbacks(retryLiveRunnable)
        saveProgress(finished = false)
        player.pause()
        overlay.visibility = View.GONE
        view.hideController()
        pauseTitle.visibility = View.GONE
        failure.showStillWatching {
            failure.hide()
            stillWatching.answered(SystemClock.elapsedRealtime())
            stoppedInBackground = false
            if (req.live) playLive(liveIndex) else player.play()
            view.requestFocus()
        }
    }

    /** A key press or touch: restarts the still-watching clocks and extends a sleep timer that is about to end. */
    private fun noteInput() {
        if (!::sleepTimer.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        stillWatching.onInput(now)
        if (sleepTimer.keepWatching(now)) {
            showOverlay("Sleep timer: another ${SleepTimer.label(sleepMinutes)}", 4_000)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        noteInput()
    }

    // ---------- Kids viewing limits ----------

    /** Counts time actually playing on kids profiles and stops at the daily limit or bedtime. */
    private lateinit var limits: KidsLimitTracker

    private fun stopForLimit(reason: String) {
        AppLog.i("Access", "Viewing limit reached while playing: $reason")
        handler.removeCallbacks(retryLiveRunnable)
        handler.removeCallbacks(clearLiveRetries)
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
    private val hideLeaveHint = Runnable { if (::leaveHint.isInitialized) leaveHint.visibility = View.GONE }
    private val applyDigits = Runnable { jumpToNumber() }

    private val isTv: Boolean by lazy {
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        val r = app.pendingPlay
        val lock = app.session.profileSwitchLock(app.store)
        if (r == null || r.items.isEmpty() || app.api.account == null ||
            (lock != null && r.profileId != lock.profileId)
        ) {
            finish()
            return
        }
        req = r
        openedWith = r
        limits = KidsLimitTracker(
            enabled = app.store.profiles.firstOrNull { it.id == r.profileId }?.kids == true,
            addUsage = { app.store.addUsage(req.profileId, it) },
            verdict = { app.store.viewingVerdict(req.profileId) },
            warn = { showOverlay(it, 6_000) },
            onLimit = ::stopForLimit,
        )
        val startedAt = SystemClock.elapsedRealtime()
        sleepMinutes = SleepTimer.clean(app.store.settings.sleepMinutes)
        sleepTimer = SleepTimer(sleepMinutes * 60_000L, startedAt)
        stillWatching = StillWatching(app.store.settings.askStillWatching, startedAt)
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
        skipBackSec = SkipSizes.clean(s.skipBackSec, SkipSizes.DEFAULT_BACK)
        skipForwardSec = SkipSizes.clean(s.skipForwardSec, SkipSizes.DEFAULT_FORWARD)
        // Streams give up after 15 s without data (menus keep the longer default) so recovery starts sooner.
        val streamHttp = app.http.newBuilder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val response = chain.proceed(chain.request())
                if (url != probeLiveUrl || !response.isSuccessful) return@addInterceptor response
                try {
                    val head = response.peekBody(TimeshiftSupport.PLAYLIST_PREFIX_BYTES).bytes()
                    if (TimeshiftSupport.looksLikePlaylist(head, head.size)) throw LivePlaylist()
                    response
                } catch (e: IOException) {
                    response.close()
                    throw e
                }
            }
            .build()
        val dataSource = OkHttpDataSource.Factory(streamHttp).setUserAgent(s.userAgent.ifBlank { "RelayTV/1.0" })
        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)
        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (isPlaylistError(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(errorPolicy)
        hlsSource = HlsMediaSource.Factory(dataSource)
            .setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true))
            .setLoadErrorHandlingPolicy(errorPolicy)
        tsSource = ProgressiveMediaSource.Factory(
            TimeshiftDataSource.Factory { timeshift },
            DefaultExtractorsFactory().setTsExtractorFlags(tsFlags),
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
        val plan = BufferPlan.forLive(req.live)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(plan.minMs, plan.maxMs, plan.startMs, plan.afterStallMs)
            .build()
        bandwidth = DefaultBandwidthMeter.getSingletonInstance(this)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSources)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setBandwidthMeter(bandwidth)
            .setSeekBackIncrementMs(skipBackSec * 1_000L)
            .setSeekForwardIncrementMs(skipForwardSec * 1_000L)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            // Phones: headphones pulled out or a Bluetooth speaker dropping pauses instead of carrying on through the
            // speaker. Not on TVs, where a sound bar switching inputs can look the same and would pause live TV.
            .setHandleAudioBecomingNoisy(!isTv)
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
            setPadding(tvPx(36), tvPx(18), tvPx(36), tvPx(18))
            setBackgroundColor(0xDD0B1220.toInt())
            visibility = View.GONE
        }
        pauseTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = if (isTv) 23f else 18f
            setPadding(tvPx(20), tvPx(12), tvPx(20), tvPx(12))
            maxWidth = (resources.displayMetrics.widthPixels * 0.66f).toInt()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xD914171C.toInt())
                cornerRadius = tvPx(8).toFloat()
            }
            visibility = View.GONE
        }
        infoView = TextView(this).apply {
            setTextColor(0xFFE8EEF7.toInt())
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(tvPx(28), tvPx(14), tvPx(28), tvPx(14))
            setBackgroundColor(0xC0000000.toInt())
            visibility = View.GONE
        }
        leaveHint = TextView(this).apply {
            text = "Press Back again to leave the player"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(tvPx(44), tvPx(26), tvPx(44), tvPx(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF00B1220.toInt())
                setStroke(tvPx(3), 0xFF3DDC97.toInt())
                cornerRadius = tvPx(16).toFloat()
            }
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
            setPadding(tvPx(32), tvPx(18), tvPx(32), tvPx(18))
            setBackgroundColor(0xCC1E2B45.toInt())
            visibility = View.GONE
            isFocusable = false
            setOnClickListener {
                view.hideController()
                openChannelOverlay()
            }
        }
        playOnTvButton = TextView(this).apply {
            text = "Play on TV"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(tvPx(32), tvPx(18), tvPx(32), tvPx(18))
            setBackgroundColor(0xCC1E2B45.toInt())
            visibility = View.GONE
            isFocusable = false
            setOnClickListener {
                view.hideController()
                playOnTvOpen = true
                updatePauseTitle()
            }
        }
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                channelsButton.visibility = if (req.live && visibility == View.VISIBLE) View.VISIBLE else View.GONE
                playOnTvButton.visibility = if (visibility == View.VISIBLE && canPlayOnTv()) View.VISIBLE else View.GONE
            },
        )
        val playOnTvView = ComposeView(this).apply {
            setContent {
                if (playOnTvOpen) {
                    RelayTheme {
                        dev.relay.tv.ui.PlayOnTvDialog(
                            command = {
                                currentItem()?.let {
                                    dev.relay.tv.sync.PlayOnTvClient.commandFor(it, player.currentPosition, app.store.deviceId)
                                }
                            },
                            onSent = { message ->
                                playOnTvOpen = false
                                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
                                // The TV has it now: stop here so the provider sees one connection, not two.
                                saveProgress(finished = false)
                                finish()
                            },
                            onDismiss = {
                                playOnTvOpen = false
                                updatePauseTitle()
                            },
                        )
                    }
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val topLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pauseTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(this@PlayerActivity.overlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            topLabels,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(tvPx(48), tvPx(40), tvPx(48), 0) },
        )
        root.addView(
            infoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(tvPx(48), 0, tvPx(48), tvPx(48)) },
        )
        val topButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playOnTvButton)
            addView(
                channelsButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = tvPx(16) },
            )
        }
        root.addView(
            topButtons,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(tvPx(48), tvPx(40), tvPx(48), 0) },
        )
        root.addView(playOnTvView, FrameLayout.LayoutParams(0, 0))
        root.addView(
            leaveHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        tsBar = TimeshiftBar(this, touchControls = !isTv, onSeek = ::tsSeek, onLive = ::tsGoLive)
        tsBar.addTo(root)
        reminder = ReminderBanner(this, isTv, handler) { r ->
            saveProgress(finished = false)
            ReminderCenter.watch(this, r)
            finish()
        }
        reminder.addTo(root)
        upNext = UpNextCard(this, isTv, ::upNextPlayNow)
        upNext.addTo(root)
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
        fillScreen(root)
        view.requestFocus()
    }

    /**
     * Video uses the whole screen on phones: the status and navigation bars are hidden (a swipe from the
     * edge shows them for a moment), and everything keeps clear of a camera cut-out, which would otherwise
     * sit over the picture or the channel name. TVs have neither, so nothing changes there.
     */
    private fun fillScreen(root: View) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.setBackgroundColor(Color.BLACK)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back when a dialog or the notification shade takes the window; put them away again.
        if (hasFocus && !isInPictureInPictureMode) hideSystemBars()
    }

    private val failing: Boolean get() = ::failure.isInitialized && failure.showing

    /** A phone in a sync household, playing something a TV can take over (not catch-up). */
    private fun canPlayOnTv(): Boolean {
        if (isTv || !app.sync.enabled) return false
        val item = currentItem() ?: return false
        return dev.relay.tv.sync.PlayOnTvClient.commandFor(item, 0, app.store.deviceId) != null
    }

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
        val urls = if (req.live) liveUrls else urlsFor(item)
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
            setDirectLiveSource(item, hostAttempt)
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
        val urls = if (req.live) liveUrls else urlsFor(item)
        val url = if (urls.isEmpty()) "" else urls[attempt % urls.size]
        val hls = isHlsSource(url)
        if (req.live) probeLiveUrl = if (hls) null else url.toHttpUrlOrNull()?.toString()
        val b = MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.key)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build(),
            )
        if (hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun setDirectLiveSource(item: PlayItem, attempt: Int) {
        val mi = mediaItem(item, attempt)
        if (mi.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            player.setMediaSource(hlsSource.createMediaSource(mi))
        } else {
            player.setMediaItem(mi)
        }
    }

    private fun startVod() {
        tuneStartedAt = SystemClock.elapsedRealtime()
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

    private fun playLive(index: Int, playlistUrl: String? = null, keepRetries: Boolean = false) {
        handler.removeCallbacks(retryLiveRunnable)
        handler.removeCallbacks(clearLiveRetries)
        tuneStartedAt = SystemClock.elapsedRealtime()
        val n = req.items.size
        liveIndex = ((index % n) + n) % n
        hostAttempt = 0
        if (!keepRetries) liveRetries = 0
        behindLiveRetries = 0
        liveStalls = 0
        liveReady = false
        liveWatch.reset()
        if (failing) hideFailure()
        val item = req.items[liveIndex]
        stopTimeshift()
        pausedItemKey = null
        probeLiveUrl = null
        liveUrls = urlsFor(item)
        if (playlistUrl != null) liveUrls = listOf(playlistUrl) + liveUrls.filterNot { it == playlistUrl }
        val s = app.store.settings
        // An HLS source plays directly. Only MPEG-TS candidates go to the recorder.
        if (s.timeshift && item.key !in tsDisabled && liveUrls.firstOrNull()?.let { !isHlsSource(it) } == true) {
            val ts = TimeshiftBuffer(
                TimeshiftBuffer.sessionDir(cacheDir), app.http, liveUrls.filterNot(::isHlsSource),
                s.userAgent.ifBlank { "RelayTV/1.0" }, s.timeshiftMinutes,
            )
            timeshift = ts
            ts.start()
            tsOffset = 0L
            player.setMediaSource(tsSource.createMediaSource(tsItem(item, 0L)))
        } else {
            setDirectLiveSource(item, 0)
        }
        player.prepare()
        player.playWhenReady = true
        infoPending = true
        showOverlay(label(item))
        // Recorded as watched only once it has played for a while, so channels passed while zapping
        // don't fill Recently watched or cost a write each.
        val entry = WatchEntry(
            key = item.key, kind = "live", title = item.title, subtitle = item.subtitle,
            image = item.image, streamId = item.streamId, ext = item.ext,
            updated = System.currentTimeMillis(),
        )
        handler.removeCallbacks(recordLiveWatch)
        recordLiveWatch = Runnable { app.store.record(req.profileId, entry.copy(updated = System.currentTimeMillis())) }
        handler.postDelayed(recordLiveWatch, LIVE_WATCH_AFTER_MS)
    }

    private fun reconnectLive() {
        if (stoppedInBackground) return
        if (timeshift != null) {
            // The pause buffer reconnects on its own; restart cleanly at live without wiping retry count.
            playLive(liveIndex, keepRetries = true)
            return
        }
        val item = currentItem() ?: return
        setDirectLiveSource(item, hostAttempt)
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

    private fun isHlsSource(url: String): Boolean =
        TimeshiftSupport.isHls(url) || url in playlistSources

    private fun isPlaylistError(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is LivePlaylist }

    private fun updatePauseTitle() {
        if (!::pauseTitle.isInitialized) return
        val item = currentItem()
        val state = player.playbackState
        if (item == null || player.playWhenReady || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            pausedItemKey = null
            pauseGuideJob?.cancel()
            pauseTitle.visibility = View.GONE
            return
        }
        if (isInPictureInPictureMode || failing || stillWatching.asking || channelOverlayOpen || playOnTvOpen) {
            pauseTitle.visibility = View.GONE
            return
        }

        val channel = item.streamId.toIntOrNull()?.takeIf { item.kind == "live" }
        val newPause = pausedItemKey != item.key
        if (newPause) {
            pausedItemKey = item.key
            pausedAtSec = if (channel != null) liveFrameTime() else null
            handler.removeCallbacks(hideOverlay)
            overlay.visibility = View.GONE
            pauseGuideJob?.cancel()
        }
        val time = pausedAtSec
        val programme = if (channel != null && time != null) app.catalog.programmeAt(channel, time)?.title else null
        val details = item.subtitle?.takeIf { it.isNotBlank() }
        val (title, subtitle) = when (item.kind) {
            "live" -> if (programme.isNullOrBlank()) item.title to null else programme to item.title
            "catchup" -> if (details == null) item.title to null else details to item.title
            else -> item.title to details
        }
        pauseTitle.text = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (subtitle != null) {
                append('\n')
                val start = length
                append(subtitle)
                setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        pauseTitle.visibility = View.VISIBLE
        if (newPause && channel != null && time != null && programme == null) {
            pauseGuideJob = lifecycleScope.launch {
                withTimeoutOrNull(5_000) { app.catalog.shortEpgOrNull(channel) }
                updatePauseTitle()
            }
        }
    }

    private fun liveFrameTime(): Long? {
        val now = System.currentTimeMillis() / 1000
        val buffer = timeshift
        if (buffer != null) return if (buffer.bytesPerSecond > 0) now - tsBehindSec() else null
        val liveOffset = player.currentLiveOffset
        return if (liveOffset >= 0) now - liveOffset / 1000 else now
    }

    // ---------- Events ----------

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY)) {
                pausedItemKey = null
            }
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYER_ERROR,
                )
            ) updatePauseTitle()
        }

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
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && stillWatching.onAutoAdvance(SystemClock.elapsedRealtime())) {
                askStillWatching()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                if (liveRetries > 0 || hostAttempt > 0) AppLog.i("Player", "Recovered after retry")
                val activeUrl = currentItem()?.let { (if (req.live && liveUrls.isNotEmpty()) liveUrls else urlsFor(it)).getOrNull(hostAttempt) }
                AppLog.d("Player", "Ready: ${currentItem()?.title} via ${hostOf(activeUrl)}")
                behindLiveRetries = 0
                liveStalls = 0
                vodStalls = 0
                if (req.live) {
                    liveReady = true
                    // Clear the retry count only once the stream stays up for a few seconds, so a stream
                    // that buffers one packet and immediately closes does not loop forever.
                    handler.removeCallbacks(clearLiveRetries)
                    handler.postDelayed(clearLiveRetries, 8_000)
                } else {
                    liveRetries = 0
                }
                if (player.duration != C.TIME_UNSET) lastDuration = player.duration
                onReady()
            }
            if (state == Player.STATE_ENDED) {
                if (req.live) {
                    handler.removeCallbacks(clearLiveRetries)
                    // The server closed the stream. Reconnect, but not forever.
                    if (liveRetries < 3) {
                        liveRetries++
                        AppLog.w("Player", "Live stream ended; reconnecting ($liveRetries of 3)")
                        handler.removeCallbacks(retryLiveRunnable)
                        handler.postDelayed(retryLiveRunnable, 1_500)
                    } else if (!tryNextVersion()) {
                        showFailure("This channel isn't responding")
                    }
                } else {
                    finishedOnLeave = true
                    saveProgress(finished = true)
                    app.store.flush()
                    finish()
                }
            }
        }

        // Auto picture-in-picture follows whether something is playing and the picture's shape.
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPipParams()

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = refreshPipParams()

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            trackPrefs.onSelectionChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            trackPrefs.onTracksChanged(currentItem(), tracks)
        }
    }

    /** First READY of each item: match the refresh rate and flash the stream info. */
    private fun onReady() {
        if (tuneStartedAt > 0) {
            val ms = SystemClock.elapsedRealtime() - tuneStartedAt
            tuneStartedAt = 0
            val line = "First picture after $ms ms: ${currentItem()?.title}"
            if (ms > 1_500) AppLog.i("Player", line) else AppLog.d("Player", line)
        }
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
        handler.removeCallbacks(clearLiveRetries)
        val item = currentItem() ?: return
        lastErrorName = e.errorCodeName
        lastHttp = httpCodeOf(e)
        val recorderPlaylist = timeshift?.playlistUrl
        val directPlaylist = if (req.live && isPlaylistError(e)) liveUrls.getOrNull(hostAttempt) else null
        val playlist = recorderPlaylist ?: directPlaylist
        if (playlist != null && playlistSources.add(playlist)) {
            AppLog.i("Player", "Playing detected HLS directly from ${hostOf(playlist)}")
            playLive(liveIndex, playlist)
            if (recorderPlaylist != null) showOverlay("Pause and rewind aren't available on this HLS stream")
            return
        }
        val urls = if (req.live && liveUrls.isNotEmpty()) liveUrls else urlsFor(item)
        AppLog.e("Player", "${e.errorCodeName} on ${item.kind} '${item.title}' via ${hostOf(urls.getOrNull(hostAttempt))}", e)
        if (req.live && timeshift != null) {
            AppLog.w("Timeshift", "Pause buffer playback failed for ${item.title}; playing directly")
            tsDisabled += item.key
            playLive(liveIndex, keepRetries = true)
            showOverlay("Pause isn't available on this channel")
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && behindLiveRetries < 3) {
            behindLiveRetries++
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        behindLiveRetries = 0
        if (hostAttempt + 1 < urls.size) {
            hostAttempt++
            if (req.live) {
                setDirectLiveSource(item, hostAttempt)
            } else {
                val pos = player.currentPosition
                val idx = player.currentMediaItemIndex
                player.replaceMediaItem(idx, mediaItem(item, hostAttempt))
                if (pos > 0) player.seekTo(idx, pos)
            }
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
            handler.removeCallbacks(retryLiveRunnable)
            handler.postDelayed(retryLiveRunnable, 2_000)
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

    // ---------- Up next card (last 30 seconds) ----------

    private lateinit var upNext: UpNextCard
    private var upNextHiddenFor: String? = null
    private var upNextPick: dev.relay.tv.data.Recommendations.Pick? = null
    private var upNextLaunching = false
    private var finishedOnLeave = false

    private fun updateUpNext() {
        if (!::player.isInitialized || !::upNext.isInitialized || req.live) return
        val item = currentItem() ?: return upNext.hide()
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return upNext.hide()
        val offer = UpNextRules.offer(item.kind, dur, player.currentPosition, player.hasNextMediaItem(), app.store.settings.autoNext, upNextHiddenFor == item.key)
        when (offer) {
            null -> upNext.hide()
            is UpNextRules.NextEpisode -> {
                val next = req.items.getOrNull(player.currentMediaItemIndex + 1)
                upNext.show("Up next in ${offer.secondsLeft} s", listOfNotNull(next?.title, next?.subtitle).joinToString(" · "))
            }
            UpNextRules.Recommend -> {
                // Only this profile's own recommendations (worked out with its kids and hidden-category rules).
                val state = app.suggestions.state.takeIf { it.stamp.startsWith(req.profileId + "|") }
                val currentKey = if (item.kind == "episode" && item.seriesId != null) "series:${item.seriesId}" else item.key
                val pick = state?.rows?.forYou?.firstOrNull { p ->
                    p.title.key != currentKey &&
                        if (p.title.tv) app.catalog.seriesById.containsKey(p.title.id) else app.catalog.movieById.containsKey(p.title.id)
                }
                upNextPick = pick
                if (pick == null) return upNext.hide()
                upNext.show(pick.reason, dev.relay.tv.data.TitleParts.parse(pick.title.name).title)
            }
        }
    }

    private fun upNextPlayNow() {
        if (upNextLaunching) return
        upNext.hide()
        if (player.hasNextMediaItem()) {
            saveProgress(finished = true)
            player.seekToNextMediaItem()
            return
        }
        val pick = upNextPick ?: return
        val t = pick.title
        if (!t.tv) {
            val m = app.catalog.movieById[t.id] ?: return
            if (Playback.movie(this, m, m.ext, 0)) {
                finishedOnLeave = true
                saveProgress(finished = true)
                app.store.flush()
                finish()
            }
        } else {
            val s = app.catalog.seriesById[t.id] ?: return
            upNextLaunching = true
            lifecycleScope.launch {
                try {
                    val cached = app.suggestions.episodesOf(s.id)
                    val raw = cached ?: withTimeoutOrNull(8_000) {
                        try {
                            app.api.seriesInfo(s.id).seasons.values.flatten().also { app.suggestions.rememberEpisodes(s.id, it) }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val ordered = dev.relay.tv.data.UpNext.ordered(raw.orEmpty())
                    if (ordered.isNotEmpty() && Playback.episodes(this@PlayerActivity, s, ordered, 0, 0)) {
                        finishedOnLeave = true
                        saveProgress(finished = true)
                        app.store.flush()
                        finish()
                    } else {
                        Toast.makeText(this@PlayerActivity, "Couldn't start ${dev.relay.tv.data.TitleParts.parse(s.name).title}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    upNextLaunching = false
                }
            }
        }
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
        if (::upNext.isInitialized && upNext.shown) {
            upNextHiddenFor = currentItem()?.key
            upNext.hide()
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
            showOverlay(label(req.items[liveIndex]), BACK_WINDOW_MS)
        }
        showLeaveHint()
    }

    /** Says, big and in the middle, what the next Back will do, for as long as the second press counts. */
    private fun showLeaveHint() {
        leaveHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideLeaveHint)
        handler.postDelayed(hideLeaveHint, BACK_WINDOW_MS)
    }

    // ---------- Channel list overlay ----------

    private fun openChannelOverlay() {
        channelOverlayOpen = true
        channelsView.visibility = View.VISIBLE
        channelsView.requestFocus()
        updatePauseTitle()
    }

    private fun closeChannelOverlay() {
        channelOverlayOpen = false
        channelsView.visibility = View.GONE
        view.requestFocus()
        updatePauseTitle()
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

    // Lint calls super.dispatchKeyEvent "restricted" (a known false alarm for ComponentActivity);
    // overriding it and calling super is how every Activity handles keys.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) noteInput()
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
        if (::upNext.isInitialized && upNext.shown && !view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            upNextPlayNow()
            return true
        }
        // The transport buttons on some remotes are delivered to the activity without a focused control.
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                        else -> player.playWhenReady = !player.playWhenReady
                    }
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && req.live && timeshift != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    tsSeek(-skipBackSec); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    tsSeek(skipForwardSec); return true
                }
            }
            // While paused or behind live, Left/Right rewind and skip instead of opening menus.
            val shifted = !player.playWhenReady || tsBehindSec() > 3
            if (shifted && !view.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        tsSeek(-skipBackSec); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        tsSeek(skipForwardSec); return true
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
            leaveHint.visibility = View.GONE
            if (channelOverlayOpen) closeChannelOverlay()
        }
        updatePauseTitle()
    }

    private fun refreshPipParams() {
        if (!pipSupported || Build.VERSION.SDK_INT < 31 || isFinishing || isDestroyed) return
        try {
            pipParams()?.let { setPictureInPictureParams(it) }
        } catch (e: IllegalStateException) {
            AppLog.w("Player", "Couldn't update picture-in-picture", e)
        }
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
        val blockedByPrompt = failing ||
            (::stillWatching.isInitialized && stillWatching.asking) ||
            (::limits.isInitialized && limits.reached)
        if (blockedByPrompt) return
        if (stoppedInBackground) {
            // Came back after leaving the app: resume live, with a fresh pause buffer.
            stoppedInBackground = false
            playLive(liveIndex)
        } else if (::player.isInitialized) {
            if (req.live) {
                player.play()
            } else {
                resumeVodAfterStop?.let { player.playWhenReady = it }
                resumeVodAfterStop = null
            }
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retryLiveRunnable)
        handler.removeCallbacks(clearLiveRetries)
        if (::limits.isInitialized) {
            limits.flush()
            limits.pause()
        }
        if (::player.isInitialized) {
            if (!finishedOnLeave) saveProgress(finished = false)
            if (!req.live) resumeVodAfterStop = player.playWhenReady
            player.pause()
            val screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
            val keepPipPlaying = isInPictureInPictureMode && screenOn
            // Don't keep recording (or holding a live connection) while Relay is in the background or the screen is off.
            if (req.live && !keepPipPlaying) {
                stoppedInBackground = true
                player.stop()
                stopTimeshift()
            }
        }
    }

    /** Another screen's "Play on TV" is about to start something here: keep this one's place, then close. */
    fun finishForHandover() {
        if (isFinishing) return
        AppLog.i("Player", "Closing for Play on TV")
        saveProgress(finished = false)
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        // Progress and kids viewing time are sent to the other screens now, not every few seconds.
        app.sync.afterPlayback()
        handler.removeCallbacksAndMessages(null)
        stopTimeshift()
        if (::frameRate.isInitialized) frameRate.restore()
        if (::player.isInitialized) {
            player.removeListener(listener)
            player.release()
        }
        // Let the play list go, unless a newer request (Play on TV, a reminder) is already waiting.
        if (isFinishing && openedWith != null && app.pendingPlay === openedWith) app.pendingPlay = null
        openedWith = null
        super.onDestroy()
    }

    companion object {
        /** The player on screen, if any (for "Play on TV" handing over to this screen). Main thread. */
        var current: java.lang.ref.WeakReference<PlayerActivity>? = null
            private set

        private const val BACK_WINDOW_MS = 2_500L

        /** A live channel counts as watched after this long. */
        private const val LIVE_WATCH_AFTER_MS = 5_000L
    }
}
