package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.tv.RelayApp
import dev.relay.tv.data.PlotText
import dev.relay.tv.data.TitleRef
import dev.relay.tv.data.Tmdb

/** Actor/crew filmography page: bio and every matching movie & series in the user's library. */
@Composable
fun PersonDetailScreen(person: Tmdb.Person) {
    val app = RelayApp.instance
    val nav = LocalNav.current
    var matches by remember(person.id, person.name) { mutableStateOf<Suggestions.PersonMatches?>(null) }

    LaunchedEffect(person.id, person.name) {
        matches = app.suggestions.titlesForPerson(person)
    }

    val backFocus = remember { FocusRequester() }
    InitialFocus(backFocus)
    val info = matches?.credits
    val photo = info?.photo ?: person.photo
    val bio = PlotText.clean(info?.bio)
    val movies = matches?.movies.orEmpty()
    val series = matches?.series.orEmpty()
    val totalMatches = movies.size + series.size

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 48.dp).widthIn(max = 880.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(116.dp).background(Palette.surface2, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(person.name), color = Palette.dim, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    if (photo != null) {
                        AsyncImage(photo, person.name, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(info?.name?.ifBlank { person.name } ?: person.name, color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    val subtitle = buildList {
                        info?.knownFor?.let { add(it) }
                        person.character?.takeIf { it.isNotBlank() }?.let { add("as $it") }
                        if (matches != null) {
                            add(if (totalMatches == 1) "1 title in your library" else "$totalMatches titles in your library")
                        }
                    }.joinToString("   ·   ")
                    if (subtitle.isNotEmpty()) Text(subtitle, color = Palette.dim, fontSize = Type.body)
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        "Back",
                        { nav.pop(app.ui.focus) },
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        focusRequester = backFocus,
                        key = "person:back",
                    )
                }
            }

            if (bio != null) {
                Spacer(Modifier.height(18.dp))
                PlotBlock(
                    bio,
                    person.name,
                    maxLines = 4,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    key = "person:bio",
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        val loaded = matches
        when {
            loaded == null -> {
                Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Palette.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding movies and series in your library…", color = Palette.dim, fontSize = Type.body)
                }
            }
            totalMatches == 0 -> {
                val hint = if (loaded.hasTmdbKey) {
                    "No movies or series featuring ${person.name} were found in this profile's library."
                } else {
                    "No cached titles featuring ${person.name}. Add a free TMDB API key in Settings → Library & guide for full cast filmographies."
                }
                Text(hint, color = Palette.dim, fontSize = Type.body, modifier = Modifier.padding(horizontal = 48.dp))
            }
            else -> {
                if (movies.isNotEmpty()) {
                    PersonTitleRail("Movies in your library", movies, "pm")
                }
                if (series.isNotEmpty()) {
                    PersonTitleRail("Series in your library", series, "ps")
                }
            }
        }
    }
}

@Composable
private fun PersonTitleRail(title: String, items: List<TitleRef>, keyPrefix: String) {
    val app = RelayApp.instance
    val nav = LocalNav.current
    Rail(title, items, key = { it.key }) { t ->
        val img = if (t.tv) app.catalog.seriesById[t.id]?.cover else app.catalog.movieById[t.id]?.poster
        val rt = posterScore(if (t.tv) "s" else "m", t.id)
        PosterCard(
            t.name,
            img,
            {
                if (t.tv) app.catalog.seriesById[t.id]?.let { nav.push(Route.SeriesDetail(it)) }
                else app.catalog.movieById[t.id]?.let { nav.push(Route.MovieDetail(it)) }
            },
            key = "$keyPrefix:${t.key}",
            subtitle = t.year,
            badge = rt?.first,
            badgeColor = rt?.second ?: Palette.accent,
        )
    }
}
