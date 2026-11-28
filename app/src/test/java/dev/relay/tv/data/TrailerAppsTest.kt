package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerAppsTest {
    @Test
    fun smartTubeComesFirstThenMorpheThenReVancedThenYouTube() {
        val all = listOf("com.google.android.youtube", "app.revanced.android.youtube", "app.morphe.android.youtube", "com.teamsmart.videomanager.tv")
        assertEquals("com.teamsmart.videomanager.tv", TrailerApps.pick(all))
        assertEquals("app.morphe.android.youtube", TrailerApps.pick(all - "com.teamsmart.videomanager.tv"))
        assertEquals("app.revanced.android.youtube", TrailerApps.pick(listOf("com.google.android.youtube", "app.revanced.android.youtube")))
        assertEquals("com.google.android.youtube", TrailerApps.pick(listOf("com.google.android.youtube")))
    }

    @Test
    fun theTvBuildOfYouTubeIsPreferredToThePhoneBuild() {
        assertEquals("com.google.android.youtube.tv", TrailerApps.pick(listOf("com.google.android.youtube", "com.google.android.youtube.tv")))
    }

    @Test
    fun anySmartTubeBuildCountsAndRvxIsReVanced() {
        assertEquals("com.liskovsoft.smarttubetv.beta", TrailerApps.pick(listOf("app.rvx.android.youtube", "com.liskovsoft.smarttubetv.beta")))
        assertEquals("app.rvx.android.youtube", TrailerApps.pick(listOf("com.google.android.youtube", "app.rvx.android.youtube")))
    }

    @Test
    fun browsersKidsAndMusicAreNeverChosen() {
        assertNull(TrailerApps.pick(listOf("com.android.chrome", "org.mozilla.firefox", "com.google.android.apps.youtube.kids")))
        assertNull(TrailerApps.pick(listOf("com.google.android.youtube.tvkids", "com.google.android.apps.youtube.music")))
        assertNull(TrailerApps.pick(listOf("app.revanced.android.apps.youtube.music", "app.rvx.android.apps.youtube.music", "app.revanced.manager.flutter", "app.morphe.manager")))
        assertEquals("com.google.android.youtube", TrailerApps.pick(listOf("app.revanced.android.apps.youtube.music", "com.google.android.youtube")))
        assertNull(TrailerApps.pick(emptyList()))
    }
}
