package dev.relay.tv.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import dev.relay.tv.data.AppLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renderers with an optional lip-sync offset. The audio clock drives video timing, so reporting
 * the audio position [audioDelayMs] earlier makes video wait for sound that arrives late
 * (typical with soundbars); a negative value does the opposite.
 */
@OptIn(UnstableApi::class)
class RelayRenderersFactory(context: Context, private val audioDelayMs: Int) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return if (audioDelayMs == 0) sink else DelayedAudioSink(sink, audioDelayMs * 1000L)
    }
}

@OptIn(UnstableApi::class)
private class DelayedAudioSink(sink: AudioSink, private val delayUs: Long) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else (p - delayUs).coerceAtLeast(0L)
    }
}

/**
 * Switches the TV's output refresh rate to a whole multiple of the video's frame rate
 * (23.976 fps -> 23.976 Hz, 25 -> 50 Hz) at the same resolution, and restores it on exit.
 * The screen may blank for a second while the TV changes mode, as with any player doing this.
 */
class FrameRateMatcher(private val activity: Activity) {
    private var changed = false
    private var lastFps = 0f

    fun match(fps: Float) {
        if (fps <= 1f || abs(fps - lastFps) < 0.01f) return
        lastFps = fps
        val display = currentDisplay() ?: return
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .mapNotNull { m ->
                val ratio = m.refreshRate / fps
                val whole = ratio.roundToInt()
                val err = abs(ratio - whole)
                if (whole >= 1 && err < 0.01f * whole) Triple(m, whole, err) else null
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .firstOrNull()?.first
        if (best == null) {
            AppLog.d("Player", "No display mode matches %.3f fps".format(fps))
            return
        }
        if (best.modeId == cur.modeId) return
        val lp = activity.window.attributes
        lp.preferredDisplayModeId = best.modeId
        activity.window.attributes = lp
        changed = true
        AppLog.i("Player", "Refresh rate %.3f Hz for %.3f fps".format(best.refreshRate, fps))
    }

    fun restore() {
        if (!changed) return
        val lp = activity.window.attributes
        lp.preferredDisplayModeId = 0
        activity.window.attributes = lp
        changed = false
        lastFps = 0f
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
}

/** One or two short lines describing what's actually playing. */
@OptIn(UnstableApi::class)
object StreamInfo {
    fun describe(video: Format?, audio: Format?, networkBps: Long): String {
        val v = mutableListOf<String>()
        if (video != null && video.height > 0) {
            val h = video.height
            v += when {
                h >= 2100 -> "4K"
                h >= 1400 -> "1440p"
                h >= 1000 -> "1080p"
                h >= 700 -> "720p"
                else -> "${h}p"
            } + " (${video.width}×$h)"
            v += hdr(video)
            codec(video.sampleMimeType)?.let { v += it }
            if (video.frameRate > 0f) v += "%.3g fps".format(video.frameRate)
            if (video.bitrate > 0) v += "%.1f Mbps".format(video.bitrate / 1_000_000f)
        }
        val a = mutableListOf<String>()
        if (audio != null) {
            audioCodec(audio.sampleMimeType)?.let { a += it }
            if (audio.channelCount > 0) {
                a += when (audio.channelCount) {
                    1 -> "mono"
                    2 -> "stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${audio.channelCount} ch"
                }
            }
            audio.language?.takeIf { it != "und" }?.let { a += it }
        }
        if (networkBps > 0) a += "network %.0f Mbps".format(networkBps / 1_000_000f)
        return listOf(v.joinToString("  ·  "), a.joinToString("  ·  ")).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun hdr(f: Format): String = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }

    private fun codec(mime: String?): String? = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
        null -> null
        else -> mime.substringAfter('/').uppercase()
    }

    private fun audioCodec(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        MimeTypes.AUDIO_OPUS -> "Opus"
        null -> null
        else -> mime.substringAfter('/').uppercase()
    }
}

/**
 * A size that was chosen in pixels on a 1080p TV (where one dp is two pixels), as pixels on this screen.
 * On a TV it gives back the same number; on a phone, whose screen packs more pixels into the same space,
 * margins and padding keep their size instead of shrinking.
 */
fun android.content.Context.tvPx(px: Int): Int = Math.round(px * resources.displayMetrics.density / 2f)
