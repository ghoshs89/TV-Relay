package dev.relay.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Channel
import dev.relay.tv.data.Langs
import dev.relay.tv.data.PlotText
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.TitleParts
import dev.relay.tv.data.TitleRef
import dev.relay.tv.data.Tmdb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the hero at the top of Home describes: the card that has focus. */
sealed interface HeroFocus {
    /** A film or series ([ref] when it is in the library), with the card's own picture and line. */
    data class Title(
        val name: String,
        val image: String?,
        val ref: TitleRef? = null,
        val subtitle: String? = null,
        val reason: String? = null,
        /** A wide picture of its own (an episode still), used before the poster. */
        val wide: String? = null,
    ) : HeroFocus

    data class Live(val channel: Channel) : HeroFocus
}

/**
 * The top of Home: a still picture of the focused card with its name (or TMDB logo), a line of details
 * and a short summary. Waits until focus has settled before loading anything, so holding a direction
 * key across a row costs nothing; one picture at a time, sized for the screen.
 */
@Composable
fun HomeHero(focus: HeroFocus?, height: Dp, compact: Boolean) {
    val settled = rememberDebounced(focus, 280)
    val app = RelayApp.instance
    val key = app.store.settings.tmdbKey
    var info by remember(settled) {
        mutableStateOf(
            (settled as? HeroFocus.Title)?.ref?.let { r -> app.tmdb.cached(r.tv, r.tmdbId, r.name) },
        )
    }
    LaunchedEffect(settled, key) {
        val t = settled as? HeroFocus.Title ?: return@LaunchedEffect
        val r = t.ref ?: return@LaunchedEffect
        if (info == null) info = app.tmdb.cachedOrDisk(r.tv, r.tmdbId, r.name)
        if (info == null && key.isNotBlank()) info = app.tmdb.lookup(key, r.tv, r.tmdbId, r.name, r.year)
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        val picture = when (val f = settled) {
            is HeroFocus.Title -> info?.backdrop ?: f.wide ?: f.image
            is HeroFocus.Live -> null
            null -> null
        }
        val wide = settled is HeroFocus.Title && (info?.backdrop != null || settled.wide != null)
        Crossfade(picture, animationSpec = tween(300), label = "hero", modifier = Modifier.fillMaxSize()) { url ->
            if (url != null) {
                val ctx = LocalContext.current
                val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(1280, 720).build() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    AsyncImage(
                        req, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.72f).graphicsLayer { alpha = if (wide) 0.9f else 0.4f },
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.bg, 0.3f to Palette.bg, 0.55f to Palette.bg.copy(alpha = 0.55f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.65f to Color.Transparent, 1f to Palette.bg)))
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 48.dp, end = 24.dp, top = 12.dp).widthIn(max = if (compact) 420.dp else 520.dp),
        ) {
            when (val f = settled) {
                is HeroFocus.Title -> TitleText(f, info, compact)
                is HeroFocus.Live -> LiveText(f.channel)
                null -> Text("Relay", color = Palette.accent, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun TitleText(f: HeroFocus.Title, info: Tmdb.Info?, compact: Boolean) {
    val parts = remember(f.name) { TitleParts.parse(f.name) }
    val logo = info?.logo
    if (logo != null && !compact) {
        val ctx = LocalContext.current
        val req = remember(logo) { ImageRequest.Builder(ctx).data(logo).size(500, 200).build() }
        AsyncImage(req, contentDescription = parts.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 90.dp).widthIn(max = 360.dp))
    } else {
        Text(
            parts.title, color = Palette.text, fontSize = if (compact) 22.sp else 30.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 26.sp else 34.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    val r = f.ref
    val meta = listOfNotNull(
        (info?.year ?: r?.year)?.takeIf { it.isNotBlank() },
        info?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
        (info?.rating ?: r?.rating?.takeIf { it > 0 })?.let { "★ %.1f".format(it) },
        r?.lang?.let { Langs.label(it) },
        parts.tag,
    ).joinToString("  ·  ")
    if (f.subtitle != null) Text(f.subtitle, color = Palette.text, fontSize = Type.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (meta.isNotEmpty()) Text(meta, color = Palette.dim, fontSize = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    f.reason?.let { Text(it, color = Palette.accent, fontSize = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val plot = info?.overview?.let { PlotText.clean(it) }?.takeIf { it.isNotBlank() }
    if (plot != null && !compact) {
        Spacer(Modifier.height(6.dp))
        Text(plot, color = Palette.text.copy(alpha = 0.85f), fontSize = Type.small, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
    }
}

@Composable
private fun LiveText(ch: Channel) {
    val app = RelayApp.instance
    val name = remember(ch.name) { ProviderNames.clean(splitChannelName(ch.name).second) }
    var epgLoaded by remember(ch.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(ch.id) {
        if (app.catalog.shortEpgOrNull(ch.id) != null) epgLoaded++
    }
    epgLoaded
    val nn = app.catalog.nowNext(ch.id)
    val fmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    Text(name, color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
    val now = nn?.first
    val next = nn?.second
    if (now != null) {
        Text("Now  ${now.title}", color = Palette.text, fontSize = Type.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${fmt.format(Date(now.start * 1000))} – ${fmt.format(Date(now.end * 1000))}", color = Palette.dim, fontSize = Type.small)
    } else {
        Text(app.catalog.liveCats.firstOrNull { it.id == ch.categoryId }?.name ?: "Live TV", color = Palette.dim, fontSize = Type.body, maxLines = 1)
    }
    if (next != null) Text("Next  ${next.title} at ${fmt.format(Date(next.start * 1000))}", color = Palette.dim, fontSize = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
