package dev.relay.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.relay.tv.RelayApp
import dev.relay.tv.update.Updater

/** Tab numbers kept in [UiState.tab]. Guide is Live TV with the grid shown. */
object Tabs {
    const val HOME = 0
    const val LIVE = 1
    const val MOVIES = 2
    const val SERIES = 3
    const val SEARCH = 4
    const val SETTINGS = 5
    const val MY_LIST = 6
}

/** Where the sidebar (TV) and the bottom bar (phone) can take you. */
enum class Dest(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Filled.Search),
    HOME("Home", Icons.Filled.Home),
    LIVE("Live TV", RelayIcons.Tv),
    MOVIES("Movies", Icons.Filled.PlayArrow),
    SERIES("Series", Icons.Filled.List),
    MY_LIST("My list", Icons.Filled.Favorite),
    SETTINGS("Settings", Icons.Filled.Settings),
    ;

    fun isActive(ui: UiState): Boolean = when (this) {
        HOME -> ui.tab == Tabs.HOME
        LIVE -> ui.tab == Tabs.LIVE
        MOVIES -> ui.tab == Tabs.MOVIES
        SERIES -> ui.tab == Tabs.SERIES
        SEARCH -> ui.tab == Tabs.SEARCH
        SETTINGS -> ui.tab == Tabs.SETTINGS
        MY_LIST -> ui.tab == Tabs.MY_LIST
    }

    fun open(ui: UiState) {
        when (this) {
            HOME -> ui.tab = Tabs.HOME
            LIVE -> { ui.tab = Tabs.LIVE; ui.liveGrid = false }
            MOVIES -> ui.tab = Tabs.MOVIES
            SERIES -> ui.tab = Tabs.SERIES
            SEARCH -> ui.tab = Tabs.SEARCH
            SETTINGS -> ui.tab = Tabs.SETTINGS
            MY_LIST -> ui.tab = Tabs.MY_LIST
        }
    }

    companion object {
        /** The bottom bar on phones; the rest are under More. */
        val PHONE_BAR = listOf(HOME, LIVE, MOVIES, SERIES, SEARCH)
        val PHONE_MORE = listOf(MY_LIST, SETTINGS)

        fun active(ui: UiState): Dest = entries.firstOrNull { it.isActive(ui) } ?: HOME
    }
}

/** Sizes of the TV sidebar. Content leaves [COLLAPSED] free on its left. */
object Sidebar {
    val COLLAPSED = 64.dp
    val EXPANDED = 220.dp
}

/**
 * The TV sidebar: icons only until it has focus, then it widens over the content to show labels.
 * [requesters] let the caller put focus on the current destination (Back from the content).
 * Right goes back to where focus was in the content. Choosing an item switches to it; focus stays here.
 */
@Composable
fun TvSidebar(
    requesters: Map<Dest, FocusRequester>,
    profileRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onProfile: () -> Unit,
) {
    val app = RelayApp.instance
    val ui = app.ui
    val memory = LocalFocusMemory.current
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) Sidebar.EXPANDED else Sidebar.COLLAPSED, tween(180), label = "sidebar")
    Column(
        Modifier
            .zIndex(2f)
            .fillMaxHeight()
            .width(width)
            .background(
                if (open) Brush.horizontalGradient(0f to Palette.bg, 0.8f to Palette.bg.copy(alpha = 0.97f), 1f to Palette.bg.copy(alpha = 0.9f))
                else Brush.horizontalGradient(0f to Palette.bg, 1f to Palette.bg.copy(alpha = 0.92f)),
            )
            .onFocusChanged {
                open = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .onPreviewKeyEvent { e ->
                // Right leaves the sidebar for the item that had focus in the content, or its first item
                // so the expanded sidebar never makes Compose's geometric search skip the first column.
                e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && memory.restoreOrFirst()
            }
            .padding(horizontal = 10.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        app.session.profile?.let { p ->
            SidebarItem(p.name, null, false, open, profileRequester, onProfile) {
                Box(
                    Modifier.size(28.dp).background(Palette.avatars[p.color.coerceAtLeast(0) % Palette.avatars.size], CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(initials(p.name).take(1), color = Color.Black, fontSize = Type.small, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Dest.entries.forEach { d ->
            SidebarItem(d.label, d.icon, d.isActive(ui), open, requesters.getValue(d), { d.open(ui) })
        }
        Spacer(Modifier.weight(1f))
        if (app.catalog.loading) {
            Row(Modifier.padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
                if (open) {
                    Spacer(Modifier.width(10.dp))
                    Text(app.catalog.progress?.percent?.let { "Loading $it%" } ?: "Loading", color = Palette.dim, fontSize = Type.small, maxLines = 1)
                }
            }
        }
        if (Updater.pending != null) {
            SidebarItem("Update", Icons.Filled.Refresh, false, open, remember { FocusRequester() }, { Updater.dialogOpen = true }, accent = true)
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    open: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
    accent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    // No focus-memory key: the content's last focused item stays the one Right returns to.
    Focusable(
        onClick,
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        shape = RoundedCornerShape(10.dp), focusRequester = requester, scaleOnFocus = 1f,
    ) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        f -> Palette.accent
                        active -> Palette.surface2
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = when {
                f -> Color.Black
                accent -> Palette.accent
                active -> Palette.accent
                else -> Palette.dim
            }
            if (leading != null) leading()
            else if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            if (open) {
                Spacer(Modifier.width(14.dp))
                Text(
                    label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (f) Color.Black else if (active) Palette.text else Palette.dim,
                    fontSize = Type.bodyLarge, fontWeight = if (active || f) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** The phone's bottom bar: five destinations and More (Guide, My list, Settings, switch profile). */
@Composable
fun PhoneBar(onProfile: () -> Unit) {
    val app = RelayApp.instance
    val ui = app.ui
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Palette.surface).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Dest.PHONE_BAR.forEach { d -> PhoneBarItem(d.label, d.icon, d.isActive(ui)) { d.open(ui) } }
        PhoneBarItem("More", Icons.Filled.MoreVert, Dest.PHONE_MORE.any { it.isActive(ui) }) { more = true }
        if (app.catalog.loading) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
        if (Updater.pending != null) PillButton("Update", { Updater.dialogOpen = true }, key = "bar:update", primary = true, compact = true)
    }
    if (more) {
        val first = remember { FocusRequester() }
        InitialFocus(first)
        TvDialog({ more = false }) {
            Text("More", fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text)
            Dest.PHONE_MORE.forEachIndexed { i, d ->
                PillButton(d.label, { d.open(ui); more = false }, icon = d.icon, key = "more:${d.name}", focusRequester = if (i == 0) first else null)
            }
            app.session.profile?.let { p ->
                PillButton("Switch profile (${p.name})", { more = false; onProfile() }, key = "more:profile")
            }
        }
    }
}

@Composable
private fun PhoneBarItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Focusable(onClick, Modifier.semantics { contentDescription = label }, shape = RoundedCornerShape(12.dp), scaleOnFocus = 1f) { f ->
        Column(
            Modifier
                .background(if (f) Palette.accent else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = if (f) Color.Black else if (active) Palette.accent else Palette.dim
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, color = if (f) Color.Black else if (active) Palette.text else Palette.dim, fontSize = Type.label, maxLines = 1)
        }
    }
}

/** The content area beside the sidebar: leaves room for it when collapsed. */
fun Modifier.besideSidebar(tv: Boolean): Modifier = if (tv) this.padding(start = Sidebar.COLLAPSED) else this

/** Fills the screen with [content] under the TV sidebar or above the phone bar. */
@Composable
fun NavFrame(tv: Boolean, sidebar: @Composable () -> Unit, bar: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (tv) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().besideSidebar(true)) { content() }
            sidebar()
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            bar()
        }
    }
}
