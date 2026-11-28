package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Episode
import dev.relay.tv.data.FavItem
import dev.relay.tv.data.Movie
import dev.relay.tv.data.MovieInfo
import dev.relay.tv.data.Series
import dev.relay.tv.data.SeriesInfo
import dev.relay.tv.data.Tmdb
import dev.relay.tv.data.TitleParts
import dev.relay.tv.data.Trailers
import dev.relay.tv.player.Playback
import kotlinx.coroutines.CancellationException

@Composable
fun MovieDetailScreen(m: Movie) {
    val app = RelayApp.instance
    val store = app.store
    val nav = LocalNav.current
    val ctx = LocalContext.current
    store.version.intValue
    store.historyVersion.intValue
    val pid = app.session.profileId
    if (!app.catalog.allowed(m.categoryId, app.session.restrict(store.settings))) {
        LaunchedEffect(Unit) { nav.pop(app.ui.focus) }
        return
    }

    var info by remember { mutableStateOf<MovieInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var tmdb by remember { mutableStateOf<Tmdb.Info?>(null) }
    var score by remember { mutableStateOf(app.ratings.saved("m:${m.id}")) }
    // True once the provider's and TMDB's answers are in, so "Find a trailer" never flashes up before a real trailer.
    var detailsDone by remember { mutableStateOf(false) }
    var trailerFallback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(m.id, attempt) {
        failed = false
        detailsDone = false
        info = try {
            app.api.vodInfo(m.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        failed = info == null
        val key = store.settings.tmdbKey
        if (key.isNotBlank()) tmdb = app.tmdb.lookup(key, false, info?.tmdbId ?: m.tmdbId, m.name, info?.year ?: m.year)
        tmdb?.let { app.catalog.learnLanguage("m:${m.id}", it.language, it.country) }
        detailsDone = true
        val rk = store.settings.ratingsKey
        if (rk.isNotBlank()) {
            score = app.ratings.lookup(rk, "m:${m.id}", false, info?.tmdbId ?: m.tmdbId ?: tmdb?.id, m.name, info?.year ?: m.year)
        }
    }

    val entry = store.entry(pid, "movie:${m.id}")
    val resumable = entry?.inProgress == true
    // Phones that sync can start the film on a TV of the household instead, at the same place.
    val playOnTv = playOnTvAvailable()
    var playOnTvOpen by remember { mutableStateOf(false) }
    val sentToTv = rememberPlayOnTvToast()
    if (playOnTvOpen) {
        PlayOnTvDialog(
            command = {
                dev.relay.tv.sync.PlayCommand(
                    from = store.deviceId, nonce = dev.relay.tv.sync.PlayCommand.newNonce(), sentAt = System.currentTimeMillis(),
                    kind = dev.relay.tv.sync.PlayCommand.MOVIE, id = m.id.toString(),
                    positionMs = if (resumable && entry != null) entry.position else 0, title = m.name.take(300),
                )
            },
            onSent = { playOnTvOpen = false; sentToTv(it) },
            onDismiss = { playOnTvOpen = false },
        )
    }
    val fav = store.isFav(pid, "movie", m.id)
    val ext = info?.ext ?: m.ext
    val playFocus = remember { FocusRequester() }
    InitialFocus(playFocus)
    val trailerId = remember(info, tmdb) { Trailers.pick(info?.trailer, tmdb?.videos.orEmpty(), tmdb?.language) }
    val trailerAllowed = app.session.profile?.kids != true

    val rating = tmdb?.rating ?: info?.rating ?: m.rating.takeIf { it > 0 }
    val tp = remember(m.name) { TitleParts.parse(m.name) }
    val meta = listOfNotNull(
        tmdb?.year ?: info?.year ?: m.year,
        tp.tag, tp.region,
        info?.durationSecs?.takeIf { it > 0 }?.let { fmtDuration(it * 1000L) },
        rating?.let { "%.1f/10".format(it) },
        tmdb?.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ") ?: info?.genre,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Phones in landscape are short: smaller title, everything scrolls.
        val compact = maxHeight < 480.dp
        val side = if (compact) 28.dp else 64.dp
        FullBackdrop(tmdb?.backdrop ?: info?.backdrop ?: m.poster, wide = tmdb?.backdrop != null || info?.backdrop != null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = if (compact) 20.dp else maxHeight * 0.22f, bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = side).widthIn(max = 760.dp)) {
                TitleOrLogo(m.name, tmdb?.logo, compact)
                Spacer(Modifier.height(10.dp))
                if (meta.isNotEmpty()) Text(meta.joinToString("   ·   "), color = Palette.dim, fontSize = Type.body)
                if (store.settings.ratingsKey.isNotBlank() && score?.none == false) {
                    Spacer(Modifier.height(8.dp))
                    RtScores(score)
                }
                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        if (resumable && entry != null) "Resume  ${fmtDuration(entry.position)}" else "Play",
                        { Playback.movie(ctx, m, ext, if (resumable && entry != null) entry.position else 0) },
                        icon = Icons.Filled.PlayArrow, focusRequester = playFocus, primary = true, key = "md:play",
                    )
                    if (resumable) {
                        PillButton("Start over", { Playback.movie(ctx, m, ext, 0) }, icon = Icons.Filled.Refresh, key = "md:restart")
                    }
                    // Last in the row, so it appearing later moves nothing that is already there.
                    TrailerButton(
                        ctx, trailerAllowed, trailerId, detailsDone, m.name, info?.year ?: m.year,
                        "md:trailer", { trailerFallback = it },
                    )
                    PillButton(
                        if (fav) "In my list" else "My list",
                        { store.toggleFav(pid, FavItem("movie", m.id, m.name, m.poster)) },
                        icon = if (fav) Icons.Filled.Check else Icons.Filled.Add, key = "md:fav",
                    )
                    if (playOnTv) {
                        PillButton("Play on TV", { playOnTvOpen = true }, icon = RelayIcons.Tv, key = "md:tv")
                    }
                }
                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                val plot = dev.relay.tv.data.PlotText.clean(tmdb?.overview ?: info?.plot)
                if (failed) {
                    // Play still works from the list's information; only the extra details are missing.
                    LoadFailed("Couldn't load details.", key = "md:retry") { attempt++ }
                } else if (info == null) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Palette.accent, strokeWidth = 2.dp)
                } else if (plot != null) {
                    PlotBlock(
                        plot, m.name, maxLines = if (compact) 3 else 4,
                        fontSize = if (compact) 15.sp else 17.sp, lineHeight = if (compact) 22.sp else 25.sp, key = "md:plot",
                    )
                }
                info?.director?.let { Text("Director: $it", color = Palette.dim, fontSize = Type.small, modifier = Modifier.padding(top = 12.dp)) }
            }
            Spacer(Modifier.height(20.dp))
            CastRow(tmdb?.people.orEmpty(), info?.cast, "md")
            MoreLikeThis(app.suggestions.ref(false, m.id), "md")
        }
    }
    trailerFallback?.let { TrailerQrDialog(it) { trailerFallback = null } }
}

@Composable
fun SeriesDetailScreen(s: Series) {
    val app = RelayApp.instance
    val store = app.store
    val nav = LocalNav.current
    val ctx = LocalContext.current
    store.version.intValue
    store.historyVersion.intValue
    val pid = app.session.profileId
    if (!app.catalog.allowed(s.categoryId, app.session.restrict(store.settings))) {
        LaunchedEffect(Unit) { nav.pop(app.ui.focus) }
        return
    }

    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var tmdb by remember { mutableStateOf<Tmdb.Info?>(null) }
    var score by remember { mutableStateOf(app.ratings.saved("s:${s.id}")) }
    var season by remember { mutableStateOf<Int?>(null) }
    var detailsDone by remember { mutableStateOf(false) }
    var trailerFallback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id, attempt) {
        failed = false
        detailsDone = false
        info = try {
            app.api.seriesInfo(s.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        failed = info == null
        val key = store.settings.tmdbKey
        if (key.isNotBlank()) tmdb = app.tmdb.lookup(key, true, info?.tmdbId ?: s.tmdbId, s.name, info?.year ?: s.year)
        tmdb?.let { app.catalog.learnLanguage("s:${s.id}", it.language, it.country) }
        detailsDone = true
        val rk = store.settings.ratingsKey
        if (rk.isNotBlank()) {
            score = app.ratings.lookup(rk, "s:${s.id}", true, info?.tmdbId ?: s.tmdbId ?: tmdb?.id, s.name, info?.year ?: s.year)
        }
    }

    val seasons = info?.seasons.orEmpty()
    val seasonKeys = seasons.keys.sortedWith(compareBy { if (it <= 0) Int.MAX_VALUE else it })
    val all: List<Episode> = dev.relay.tv.data.UpNext.ordered(seasons.values.flatten())
    LaunchedEffect(all) { app.suggestions.rememberEpisodes(s.id, all) }
    val history = store.history(pid)

    // Next up: the most recent episode in progress, or the one after the last finished episode.
    val last = history.firstOrNull { it.seriesId == s.id && it.kind == "episode" }
    val lastIdx = last?.let { l -> all.indexOfFirst { "ep:${it.id}" == l.key } } ?: -1
    val nextIdx = when {
        lastIdx < 0 -> 0
        last?.finished == true -> (lastIdx + 1).coerceAtMost(all.lastIndex)
        else -> lastIdx
    }
    val nextEp = all.getOrNull(nextIdx)
    val nextResume = if (lastIdx == nextIdx && last?.inProgress == true) (last?.position ?: 0L) else 0L
    LaunchedEffect(seasonKeys) {
        if (season == null && seasonKeys.isNotEmpty()) season = nextEp?.season ?: seasonKeys.first()
    }

    // Phones that sync can send an episode to a TV of the household: the button for the next one, or hold a card.
    val playOnTv = playOnTvAvailable()
    var sendEpisode by remember { mutableStateOf<Pair<Episode, Long>?>(null) }
    val sentToTv = rememberPlayOnTvToast()
    sendEpisode?.let { (ep, at) ->
        PlayOnTvDialog(
            command = {
                dev.relay.tv.sync.PlayOnTvClient.episodeCommand(
                    ep.id, s.id, ep.ext, "${s.name} S${ep.season} E${ep.number}", at, store.deviceId,
                )
            },
            onSent = { sendEpisode = null; sentToTv(it) },
            onDismiss = { sendEpisode = null },
        )
    }

    val fav = store.isFav(pid, "series", s.id)
    val playFocus = remember { FocusRequester() }
    InitialFocus(playFocus, info != null)
    val trailerId = remember(info, tmdb) { Trailers.pick(info?.trailer, tmdb?.videos.orEmpty(), tmdb?.language) }
    val trailerAllowed = app.session.profile?.kids != true

    val rating = tmdb?.rating ?: info?.rating ?: s.rating.takeIf { it > 0 }
    val tp = remember(s.name) { TitleParts.parse(s.name) }
    val meta = listOfNotNull(
        tmdb?.year ?: info?.year ?: s.year,
        tp.tag, tp.region,
        if (seasonKeys.isNotEmpty()) "${seasonKeys.size} season${if (seasonKeys.size == 1) "" else "s"}" else null,
        rating?.let { "%.1f/10".format(it) },
        tmdb?.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ") ?: info?.genre,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        val side = if (compact) 28.dp else 64.dp
        FullBackdrop(tmdb?.backdrop ?: info?.backdrop ?: s.cover, wide = tmdb?.backdrop != null || info?.backdrop != null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = if (compact) 20.dp else maxHeight * 0.18f, bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = side).widthIn(max = 760.dp)) {
                TitleOrLogo(s.name, tmdb?.logo, compact)
                Spacer(Modifier.height(10.dp))
                if (meta.isNotEmpty()) Text(meta.joinToString("   ·   "), color = Palette.dim, fontSize = Type.body)
                if (store.settings.ratingsKey.isNotBlank() && score?.none == false) {
                    Spacer(Modifier.height(8.dp))
                    RtScores(score)
                }
                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (nextEp != null) {
                        val label = if (lastIdx >= 0) (if (nextResume > 0) "Resume S${nextEp.season} E${nextEp.number}" else "Continue S${nextEp.season} E${nextEp.number}")
                        else "Play S${nextEp.season} E${nextEp.number}"
                        PillButton(
                            label, { Playback.episodes(ctx, s, all, nextIdx, nextResume) },
                            icon = Icons.Filled.PlayArrow, focusRequester = playFocus, primary = true, key = "sd:play",
                        )
                        if (nextResume > 0) {
                            PillButton("Start over", { Playback.episodes(ctx, s, all, nextIdx, 0) }, icon = Icons.Filled.Refresh, key = "sd:restart")
                        }
                    } else if (info == null && !failed) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Palette.accent, strokeWidth = 2.dp)
                    }
                    TrailerButton(
                        ctx, trailerAllowed, trailerId, detailsDone, s.name, info?.year ?: s.year,
                        "sd:trailer", { trailerFallback = it },
                    )
                    PillButton(
                        if (fav) "In my list" else "My list",
                        { store.toggleFav(pid, FavItem("series", s.id, s.name, s.cover)) },
                        icon = if (fav) Icons.Filled.Check else Icons.Filled.Add, key = "sd:fav",
                        focusRequester = if (nextEp == null) playFocus else null,
                    )
                    if (nextEp != null && playOnTv) {
                        PillButton("Play on TV", { sendEpisode = nextEp to nextResume }, icon = RelayIcons.Tv, key = "sd:tv")
                    }
                }
                if (failed) {
                    Spacer(Modifier.height(10.dp))
                    LoadFailed("Couldn't load episodes.", key = "sd:retry") { attempt++ }
                }
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                val plot = dev.relay.tv.data.PlotText.clean(tmdb?.overview ?: info?.plot)
                if (plot != null) {
                    PlotBlock(
                        plot, s.name, maxLines = if (compact) 3 else 4,
                        fontSize = if (compact) 14.sp else 16.sp, lineHeight = if (compact) 20.sp else 23.sp, key = "sd:plot",
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 16.dp else 22.dp))
            if (seasonKeys.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = side),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(seasonKeys) { k ->
                        ListRow(
                            "Season $k", { season = k }, Modifier.width(130.dp),
                            selected = season == k, key = "season:${s.id}:$k",
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val eps = (season ?: seasonKeys.firstOrNull())?.let { seasons[it] }.orEmpty()
            LazyRow(
                contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(eps) { e ->
                    val h = history.firstOrNull { it.key == "ep:${e.id}" }
                    val idx = all.indexOf(e)
                    PosterCard(
                        title = "E${e.number}  ${e.title}",
                        image = e.image ?: s.cover,
                        onClick = { Playback.episodes(ctx, s, all, idx, if (h?.inProgress == true) (h?.position ?: 0L) else 0L) },
                        onLongClick = if (playOnTv) ({ sendEpisode = e to (if (h?.inProgress == true) (h?.position ?: 0L) else 0L) }) else null,
                        key = "ep:${e.id}",
                        parseTitle = false,
                        width = if (compact) 210.dp else 250.dp,
                        aspect = 16f / 9f,
                        subtitle = when {
                            h?.finished == true -> "Watched"
                            h?.inProgress == true -> "${fmtDuration((h?.duration ?: 0L) - (h?.position ?: 0L))} left"
                            e.durationSecs != null && e.durationSecs > 0 -> fmtDuration(e.durationSecs * 1000L)
                            else -> null
                        },
                        progress = h?.takeIf { it.duration > 0 }?.let { it.position.toFloat() / it.duration },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            CastRow(tmdb?.people.orEmpty(), info?.cast, "sd")
            MoreLikeThis(app.suggestions.ref(true, s.id), "sd")
        }
    }
    trailerFallback?.let { TrailerQrDialog(it) { trailerFallback = null } }
}

/**
 * **Trailer** when one is known, otherwise a quieter **Find a trailer** once the details have
 * loaded. Hidden on kids profiles: YouTube is outside Relay's kids limits and parent PIN.
 * Both open the best installed YouTube app ([TrailerLauncher]); [showQr] is used only when there is none.
 */
@Composable
private fun TrailerButton(
    ctx: android.content.Context,
    allowed: Boolean,
    trailerId: String?,
    detailsDone: Boolean,
    name: String,
    year: String?,
    key: String,
    showQr: (String) -> Unit,
) {
    if (!allowed) return
    val go: (String) -> Unit = { url ->
        if (!TrailerLauncher.open(ctx, url, displayTitle(name))) showQr(url)
    }
    if (trailerId != null) {
        val url = Trailers.watchUrl(trailerId)
        PillButton("Trailer", { go(url) }, icon = Icons.Filled.PlayArrow, key = key)
    } else if (detailsDone && Trailers.SEARCH_ENABLED) {
        val url = remember(name, year) { Trailers.searchUrl(Tmdb.cleanTitle(name), year) }
        PillButton("Find a trailer", { go(url) }, icon = Icons.Filled.Search, key = key)
    }
}

/** Failure text with a Retry button, used instead of a spinner that never ends. */
@Composable
fun LoadFailed(text: String, key: String? = null, onRetry: () -> Unit) {
    Column {
        Text(text, color = Palette.danger, fontSize = Type.body)
        Spacer(Modifier.height(8.dp))
        PillButton("Retry", onRetry, icon = Icons.Filled.Refresh, key = key)
    }
}



/** Provider names often carry a language tag, year and region, e.g. "IN - Title (2026) (US)". */
fun displayTitle(raw: String): String = TitleParts.parse(raw).title

/** The page's backdrop across the whole screen, fading into the background on the left and at the bottom. */
@Composable
private fun FullBackdrop(url: String?, wide: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (!url.isNullOrBlank()) {
            val ctx = LocalContext.current
            val request = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(1280, 720).crossfade(true).build() }
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (wide) 0.7f else 0.3f },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.bg, 0.4f to Palette.bg.copy(alpha = 0.8f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Palette.bg.copy(alpha = 0.4f), 0.75f to Palette.bg)))
    }
}

/** TMDB's title logo when there is one, otherwise the title in large type. */
@Composable
private fun TitleOrLogo(name: String, logo: String?, compact: Boolean) {
    if (logo != null) {
        val ctx = LocalContext.current
        val req = remember(logo) { coil.request.ImageRequest.Builder(ctx).data(logo).size(500, 200).build() }
        AsyncImage(
            req, contentDescription = displayTitle(name), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = if (compact) 64.dp else 110.dp).widthIn(max = if (compact) 280.dp else 420.dp),
        )
    } else {
        Text(
            displayTitle(name), fontSize = if (compact) 26.sp else 40.sp, fontWeight = FontWeight.ExtraBold,
            color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 30.sp else 46.sp,
        )
    }
}

/** Cast with photos from TMDB; the provider's names alone when that is all there is. */
@Composable
private fun CastRow(people: List<Tmdb.Person>, providerCast: String?, keyPrefix: String) {
    val list = people.ifEmpty {
        providerCast?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(10)?.map { Tmdb.Person(it) }.orEmpty()
    }
    Rail("Cast", list, key = { it.name }) { p ->
        Focusable({}, Modifier.width(112.dp), key = "$keyPrefix:cast:${p.name}", scaleOnFocus = 1.04f) { f ->
            Column(Modifier.fillMaxWidth().background(if (f) Palette.surface2 else Color.Transparent).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).background(Palette.surface2, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(p.name), color = Palette.dim, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (p.photo != null) {
                        AsyncImage(p.photo, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(p.name, color = Palette.text, fontSize = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                p.character?.let { Text(it, color = Palette.dim, fontSize = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** "More like this": TMDB's recommendations found in the library, or the library's own look-alikes. */
@Composable
private fun MoreLikeThis(ref: dev.relay.tv.data.TitleRef?, keyPrefix: String) {
    if (ref == null) return
    val app = RelayApp.instance
    val nav = LocalNav.current
    app.store.version.intValue
    val dismissed = app.store.dismissed(app.session.profileId.orEmpty())
    var list by remember(ref.key) { mutableStateOf<List<dev.relay.tv.data.TitleRef>>(emptyList()) }
    LaunchedEffect(ref.key, dismissed) { list = app.suggestions.moreLikeThis(ref) }
    var asking by remember { mutableStateOf<dev.relay.tv.data.TitleRef?>(null) }
    Rail("More like this", list, key = { it.key }, hint = "Hold OK: not interested") { t ->
        val image = if (t.tv) app.catalog.seriesById[t.id]?.cover else app.catalog.movieById[t.id]?.poster
        PosterCard(
            t.name, image,
            {
                if (t.tv) app.catalog.seriesById[t.id]?.let { nav.push(Route.SeriesDetail(it)) }
                else app.catalog.movieById[t.id]?.let { nav.push(Route.MovieDetail(it)) }
            },
            key = "$keyPrefix:more:${t.key}", subtitle = t.year, onLongClick = { asking = t },
        )
    }
    asking?.let { t ->
        val first = remember { FocusRequester() }
        InitialFocus(first)
        TvDialog({ asking = null }) {
            Text("Not interested", fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text)
            Text("${displayTitle(t.name)} won't be recommended to this profile again.", fontSize = Type.bodyLarge, color = Palette.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Not interested", { app.suggestions.notInterested(t); asking = null }, focusRequester = first, primary = true, key = "$keyPrefix:notint")
                PillButton("Cancel", { asking = null }, key = "$keyPrefix:notint:cancel")
            }
        }
    }
}
