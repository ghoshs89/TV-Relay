package dev.relay.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.HomeLayout
import dev.relay.tv.data.LanguageDetector
import dev.relay.tv.data.Langs
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.moved

/**
 * Language order with Home rows: one row per language group; OK on a row offers Move up,
 * Move down and Home row on or off.
 */
@Composable
fun LanguageOrderList(
    order: List<String>,
    featured: List<String>,
    onChange: (List<String>, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
) {
    val all = (order + Langs.GROUPS).distinct()
    var acting by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(all.size) { i ->
            val k = all[i]
            ListRow(
                "${i + 1}.  ${Langs.label(k)}",
                onClick = { acting = k },
                key = "lang:$k",
                trailing = if (k in featured) "Home row" else "",
                focusRequester = if (i == 0) first else null,
            )
        }
    }
    acting?.let { k ->
        TvDialog({ acting = null }, width = 420.dp) {
            Text(Langs.label(k), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            val fr = remember { FocusRequester() }
            InitialFocus(fr)
            PillButton("Move up", { onChange(all.moved(k, -1), featured) }, focusRequester = fr)
            PillButton("Move down", { onChange(all.moved(k, 1), featured) })
            PillButton(
                if (k in featured) "Remove its Home row" else "Add a \"New in ${Langs.label(k)}\" row to Home",
                { onChange(all, if (k in featured) featured - k else all.filter { it in featured || it == k }) },
            )
            PillButton("Done", { acting = null }, primary = true)
        }
    }
}

/** Settings → My languages, for the current profile. */
@Composable
fun MyLanguagesDialog(onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    store.version.intValue
    val s = store.settings
    val profile = app.session.profile
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val save: (List<String>, List<String>) -> Unit = { order, featured ->
        if (profile != null) {
            val updated = profile.copy(langOrder = order, langFeatured = featured)
            store.profiles = store.profiles.map { if (it.id == profile.id) updated else it }
            app.session.profile = updated
        } else {
            store.settings = s.copy(langOrder = order, langFeatured = featured)
        }
        AppLog.i("Languages", "Order ${order.joinToString()} · Home rows ${featured.joinToString()}")
    }
    TvDialog(onDismiss, width = 620.dp) {
        Text("My languages", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "${profile?.name ?: "This device"}: lists, search and the guide show these first, in this order. " +
                "Everything else still appears, lower down.",
            fontSize = 14.sp, color = Palette.dim,
        )
        LanguageOrderList(
            app.session.langOrder(s), app.session.langFeatured(s), save,
            Modifier.fillMaxWidth().height(300.dp), first,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", onDismiss, primary = true)
            PillButton("Reset", { save(s.langOrder, s.langFeatured) })
        }
    }
}

/** Settings → Home screen: which rows this profile sees, and their order. */
@Composable
fun HomeLayoutDialog(onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    store.version.intValue
    val s = store.settings
    val profile = app.session.profile
    val featured = app.session.langFeatured(s)
    val visible = HomeLayout.resolve(profile?.homeRows, featured)
    val all = HomeLayout.all(visible, featured)
    var acting by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val save: (List<String>) -> Unit = { rows ->
        if (profile != null) {
            val updated = profile.copy(homeRows = rows)
            store.profiles = store.profiles.map { if (it.id == profile.id) updated else it }
            app.session.profile = updated
        }
    }
    TvDialog(onDismiss, width = 640.dp) {
        Text("Home screen", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text("Rows for ${profile?.name ?: "this profile"}. Empty rows never show.", fontSize = 14.sp, color = Palette.dim)
        LazyColumn(Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(all.size) { i ->
                val id = all[i]
                ListRow(
                    HomeLayout.label(id),
                    onClick = { acting = id },
                    key = "hr:$id",
                    trailing = if (id in visible) "Shown" else "Hidden",
                    textColor = if (id in visible) Palette.text else Palette.dim,
                    focusRequester = if (i == 0) first else null,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", onDismiss, primary = true)
            PillButton("Live TV first", { save(HomeLayout.liveFirst(featured)) })
            PillButton("Default", { save(HomeLayout.defaults(featured)) })
        }
    }
    acting?.let { id ->
        TvDialog({ acting = null }, width = 420.dp) {
            Text(HomeLayout.label(id), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            val fr = remember { FocusRequester() }
            InitialFocus(fr)
            PillButton(if (id in visible) "Hide this row" else "Show this row", { save(HomeLayout.toggle(visible, id)) }, focusRequester = fr)
            if (id in visible) {
                PillButton("Move up", { save(HomeLayout.move(visible, id, -1)) })
                PillButton("Move down", { save(HomeLayout.move(visible, id, 1)) })
            }
            PillButton("Done", { acting = null }, primary = true)
        }
    }
}

/**
 * Settings → Manage categories: show, hide or pin categories (and whole countries in Live TV).
 * Hidden categories can always be shown again here.
 */
@Composable
fun CategoryManagerDialog(onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    val catalog = app.catalog
    store.version.intValue
    val s = store.settings
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<Pair<String, String>?>(null) } // key, label
    val first = remember { FocusRequester() }
    InitialFocus(first)

    val rows: List<CatEntry> = when (tab) {
        0 -> {
            val groups = LiveBrowse.group(catalog.liveCats)
            if (groups == null) {
                catalog.liveCats.map { CatEntry("live:${it.id}", ProviderNames.clean(it.name), catalog.channelsByCat[it.id]?.size ?: 0) }
            } else {
                groups.flatMap { g ->
                    val head = CatEntry("livegroup:${g.key}", g.label, g.cats.size, header = true)
                    if (open == g.key) {
                        listOf(head) + g.cats.map { CatEntry("live:${it.id}", "   " + ProviderNames.clean(it.name), catalog.channelsByCat[it.id]?.size ?: 0, groupKey = g.key) }
                    } else {
                        listOf(head)
                    }
                }
            }
        }
        1 -> catalog.vodCats.map { CatEntry("vod:${it.id}", ProviderNames.clean(it.name), catalog.moviesByCat[it.id]?.size ?: 0) }
        else -> catalog.seriesCats.map { CatEntry("series:${it.id}", ProviderNames.clean(it.name), catalog.seriesByCat[it.id]?.size ?: 0) }
    }

    fun pinned(key: String): Boolean {
        val id = key.substringAfter(':')
        return when {
            key.startsWith("live:") -> id in store.pinnedCats
            key.startsWith("vod:") -> id in s.pinnedVod
            key.startsWith("series:") -> id in s.pinnedSeries
            else -> false
        }
    }

    TvDialog(onDismiss, width = 760.dp) {
        Text("Manage categories", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Hidden categories leave Live TV, Movies and Series lists; search still finds them. Pinned ones go to the top.",
            fontSize = 14.sp, color = Palette.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Live TV", "Movies", "Series").forEachIndexed { i, t ->
                PillButton(t, { tab = i; open = null }, primary = tab == i, focusRequester = if (i == 0) first else null)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(330.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(rows, key = { it.key }) { r ->
                val hiddenNow = r.key in s.hiddenCats || (r.groupKey != null && "livegroup:${r.groupKey}" in s.hiddenCats)
                val status = buildList {
                    add("${r.count}")
                    if (hiddenNow) add("Hidden")
                    if (pinned(r.key)) add("Pinned")
                    if (r.header) add(if (open == r.groupKey ?: r.key.substringAfter(':')) "▾" else "▸")
                }.joinToString("  ·  ")
                ListRow(
                    r.label,
                    onClick = { acting = r.key to r.label.trim() },
                    key = "cm:${r.key}",
                    trailing = status,
                    textColor = if (hiddenNow) Palette.dim else if (r.header) Palette.accent2 else Palette.text,
                )
            }
        }
        PillButton("Done", onDismiss, primary = true)
    }

    acting?.let { (key, label) ->
        val isGroup = key.startsWith("livegroup:")
        val hiddenNow = key in s.hiddenCats
        TvDialog({ acting = null }, width = 460.dp) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            val fr = remember { FocusRequester() }
            InitialFocus(fr)
            PillButton(
                if (hiddenNow) "Show" else if (isGroup) "Hide this whole country" else "Hide",
                {
                    store.settings = s.copy(hiddenCats = if (hiddenNow) s.hiddenCats - key else s.hiddenCats + key)
                    acting = null
                },
                focusRequester = fr,
            )
            if (isGroup) {
                val g = key.substringAfter(':')
                PillButton(if (open == g) "Hide its categories" else "Show its categories", { open = if (open == g) null else g; acting = null })
            } else {
                val id = key.substringAfter(':')
                val isPinned = pinned(key)
                PillButton(if (isPinned) "Unpin" else "Pin to the top", {
                    when {
                        key.startsWith("live:") -> store.pinnedCats = if (isPinned) store.pinnedCats - id else store.pinnedCats + id
                        key.startsWith("vod:") -> store.settings = s.copy(pinnedVod = if (isPinned) s.pinnedVod - id else s.pinnedVod + id)
                        else -> store.settings = s.copy(pinnedSeries = if (isPinned) s.pinnedSeries - id else s.pinnedSeries + id)
                    }
                    acting = null
                })
            }
            PillButton("Cancel", { acting = null })
        }
    }
}

/**
 * First-run library setup, offered once: language order, then which Live TV countries to show.
 * Countries in other languages start unticked; everything stays available in Manage categories.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    val catalog = app.catalog
    val s = store.settings
    var step by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf(s.langOrder) }
    var featured by remember { mutableStateOf(s.langFeatured) }
    val groups = LiveBrowse.group(catalog.liveCats).orEmpty().filter { it.key != "__other" }
    var unticked by remember(groups.size) {
        mutableStateOf(groups.filter { LanguageDetector.ofCode(it.key) == Langs.OTHER }.map { it.key }.toSet())
    }
    val finish: (Boolean) -> Unit = { apply ->
        store.settings = if (apply) {
            s.copy(
                setupDone = true, langOrder = order, langFeatured = featured,
                hiddenCats = s.hiddenCats + unticked.map { "livegroup:$it" },
            )
        } else {
            s.copy(setupDone = true)
        }
        AppLog.i("Setup", if (apply) "Library set up: ${order.joinToString()}; ${unticked.size} countries hidden" else "Setup skipped")
        onDone()
    }
    BackHandler { finish(false) }
    val first = remember(step) { FocusRequester() }
    InitialFocus(first, step)

    Box(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 56.dp, vertical = 32.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up your library · step ${step + 1} of 2", fontSize = 14.sp, color = Palette.dim)
            if (step == 0) {
                Text("Your languages", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text(
                    "Lists, search and the guide put these first, in this order. Other languages still appear, lower down. " +
                        "Languages marked Home row get a \"New in\" row on Home.",
                    fontSize = 15.sp, color = Palette.dim,
                )
                LanguageOrderList(order, featured, { o, f -> order = o; featured = f }, Modifier.width(560.dp).weight(1f), first)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Next", { step = 1 }, primary = true)
                    PillButton("Skip setup", { finish(false) })
                }
            } else {
                Text("Live TV countries", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text(
                    "Untick countries you never watch to shorten Live TV. You can show them again any time in " +
                        "Settings → Manage categories.",
                    fontSize = 15.sp, color = Palette.dim,
                )
                if (groups.isEmpty()) {
                    Text(
                        if (catalog.loading) "Loading your channel list…" else "Your provider doesn't group channels by country, so there's nothing to choose here.",
                        color = Palette.dim, fontSize = 15.sp,
                    )
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.width(560.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(groups.size) { i ->
                            val g = groups[i]
                            val on = g.key !in unticked
                            ListRow(
                                (if (on) "☑  " else "☐  ") + g.label,
                                onClick = { unticked = if (on) unticked + g.key else unticked - g.key },
                                key = "sc:${g.key}",
                                trailing = "${g.cats.size}",
                                textColor = if (on) Palette.text else Palette.dim,
                                focusRequester = if (i == 0) first else null,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Finish", { finish(true) }, primary = true, focusRequester = if (groups.isEmpty()) first else null)
                    PillButton("Back", { step = 0 })
                }
            }
        }
    }
}

private data class CatEntry(val key: String, val label: String, val count: Int, val header: Boolean = false, val groupKey: String? = null)
