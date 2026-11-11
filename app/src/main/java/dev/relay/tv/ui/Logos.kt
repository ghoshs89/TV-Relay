package dev.relay.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.FailureMemory

/** Logo URLs that failed to load this session; lists show the initials for them at once. */
object LogoFailures {
    private val memory = FailureMemory(onGrew = { n ->
        // Providers often have hundreds of dead logo links; say so once or twice, not per logo.
        if (n == 25 || n == 250) AppLog.i("Images", "$n channel logos failed to load so far; showing initials for them")
    })

    fun has(url: String?): Boolean = !url.isNullOrBlank() && memory.has(url)
    fun add(url: String) = memory.add(url)
}

/**
 * A channel logo for list rows: no fade-in (a fade per row costs frames while scrolling fast),
 * and [fallback] (usually the channel's initials) when there is no logo or it won't load.
 */
@Composable
fun LogoImage(url: String?, modifier: Modifier, fallback: @Composable () -> Unit) {
    if (url.isNullOrBlank()) {
        fallback()
        return
    }
    var failed by remember(url) { mutableStateOf(LogoFailures.has(url)) }
    if (failed) {
        fallback()
        return
    }
    val ctx = LocalContext.current
    val request = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(false).build() }
    AsyncImage(
        model = request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit,
        onError = {
            LogoFailures.add(url)
            failed = true
        },
    )
}
