package dev.relay.tv.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.PlayItem

/**
 * Audio and subtitle languages, the per-series memory of what the viewer picked, and how
 * subtitles look. The choices themselves are made in [TrackPrefs]; this talks to the player.
 */
@OptIn(UnstableApi::class)
class PlayerTracks(private val app: RelayApp) {
    /** True while Relay sets the tracks itself, so those changes aren't taken for the viewer's choice. */
    private var applying = false
    private var userChanged = false

    fun audioOrder(): List<String> {
        val s = app.store.settings
        return TrackPrefs.audioOrder(s.audioLangs, app.session.langOrder(s), s.audioLang)
    }

    fun textOrder(): List<String> = TrackPrefs.textOrder(app.store.settings.subtitleLang)

    /** Starts [item] with the audio and subtitles remembered for its series, if any. */
    fun apply(player: Player, item: PlayItem) {
        val sid = item.seriesId ?: return
        val pref = app.store.trackPref(sid) ?: return
        applying = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguages(*TrackPrefs.preferring(pref.audio, audioOrder()).toTypedArray())
            .setPreferredTextLanguages(*TrackPrefs.preferring(pref.text, textOrder()).toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, pref.textOff)
            .build()
        applying = false
        AppLog.d("Player", "Series $sid tracks: audio=${pref.audio} subs=${if (pref.textOff) "off" else pref.text}")
    }

    /** The viewer (not Relay) changed the track selection. */
    fun onSelectionChanged() {
        if (!applying) userChanged = true
    }

    /** After the viewer changed tracks, remember what is playing for the series of [item]. */
    fun onTracksChanged(item: PlayItem?, tracks: Tracks) {
        if (!userChanged) return
        userChanged = false
        val sid = item?.seriesId ?: return
        val selected = buildList {
            for (g in tracks.groups) {
                if (!g.isSelected) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSelected(i)) continue
                    val kind = when (g.type) {
                        C.TRACK_TYPE_AUDIO -> TrackPrefs.Kind.Audio
                        C.TRACK_TYPE_TEXT -> TrackPrefs.Kind.Text
                        else -> continue
                    }
                    add(TrackPrefs.Selected(kind, g.getTrackFormat(i).language))
                }
            }
        }
        val pref = TrackPrefs.remembered(selected)
        app.store.setTrackPref(sid, pref)
        AppLog.i("Player", "Remembered for series $sid: audio=${pref.audio} subs=${pref.text ?: "off"}")
    }

    fun styleSubtitles(view: PlayerView) {
        val s = app.store.settings
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    if (s.subYellow) 0xFFFFE14D.toInt() else Color.WHITE,
                    if (s.subBox) 0x99000000.toInt() else Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    if (s.subBox) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    null,
                ),
            )
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * TrackPrefs.subtitleScale(s.subSize))
        }
    }
}
