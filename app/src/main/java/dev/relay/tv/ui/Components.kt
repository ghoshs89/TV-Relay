package dev.relay.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

object Palette {
    val bg = Color(0xFF0B1220)
    val surface = Color(0xFF141E30)
    val surface2 = Color(0xFF1E2B45)
    val accent = Color(0xFF3DDC97)
    val accent2 = Color(0xFF6CB4FF)
    val text = Color(0xFFE8EEF7)
    val dim = Color(0xFF8A98B0)
    val danger = Color(0xFFFF6B6B)
    val avatars = listOf(
        Color(0xFF3DDC97), Color(0xFF6CB4FF), Color(0xFFFFB454),
        Color(0xFFFF7AA8), Color(0xFFB78CFF), Color(0xFF5CE1E6),
    )
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.Black,
            secondary = Palette.accent2,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surface2,
            onSurfaceVariant = Palette.dim,
        ),
        typography = relayTypography,
        content = content,
    )
}

private val relayTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(lineHeight = TextUnit.Unspecified),
        bodyMedium = t.bodyMedium.copy(lineHeight = TextUnit.Unspecified),
        bodySmall = t.bodySmall.copy(lineHeight = TextUnit.Unspecified),
    )
}

/** False while a screen is covered by another one, so the D-pad can't wander underneath. */
val LocalFocusEnabled = compositionLocalOf { true }
val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/**
 * The single focus primitive for the whole app: scales and outlines on focus, remembers
 * itself by [key] so focus can return here after a detail page is closed.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    key: String? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    focusRequester: FocusRequester? = null,
    scaleOnFocus: Float = 1.05f,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val enabled = LocalFocusEnabled.current
    val memory = LocalFocusMemory.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val latestOnFocused by rememberUpdatedState(onFocused)

    LaunchedEffect(focused) {
        if (focused) {
            if (key != null) memory.lastKey = key
            latestOnFocused()
        }
    }
    LaunchedEffect(memory.restoreTick, enabled) {
        if (enabled && memory.restoreTick > 0 && key != null && key == memory.lastKey) {
            delay(80)
            runCatching { requester.requestFocus() }
        }
    }

    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "focusScale")
    Box(
        modifier
            .focusRequester(requester)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
    ) {
        content(focused)
    }
}

/** Requests focus once the target is attached; retries briefly while lazy lists lay out. */
@Composable
fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(30) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    key: String? = null,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    Focusable(onClick, modifier, key = key, shape = RoundedCornerShape(50), focusRequester = focusRequester) { f ->
        val bg = when {
            f -> if (danger) Palette.danger else Palette.accent
            primary -> Palette.surface2
            else -> Palette.surface
        }
        val fg = if (f) Color.Black else if (danger) Palette.danger else Palette.text
        Row(
            Modifier.background(bg).padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
fun ListRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    key: String? = null,
    trailing: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    textColor: Color = Palette.text,
) {
    Focusable(
        onClick, modifier.fillMaxWidth(), key = key, shape = RoundedCornerShape(8.dp),
        focusRequester = focusRequester, scaleOnFocus = 1.02f, onFocused = onFocused,
    ) { f ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (f) Palette.surface2 else if (selected) Palette.surface else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.size(width = 3.dp, height = 18.dp).background(Palette.accent))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text, Modifier.weight(1f), color = if (selected) Palette.accent else textColor,
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (trailing != null) Text(trailing, color = Palette.dim, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    key: String? = null,
    width: Dp = 150.dp,
    aspect: Float = 2f / 3f,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    fit: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        Focusable(
            onClick, Modifier.fillMaxWidth().aspectRatio(aspect), key = key,
            focusRequester = focusRequester, onFocused = onFocused,
        ) { _ ->
            Box(Modifier.fillMaxSize().background(Palette.surface2)) {
                Text(
                    initials(title), Modifier.align(Alignment.Center), color = Palette.dim,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
                if (!image.isNullOrBlank()) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().padding(if (fit) 14.dp else 0.dp),
                    )
                }
                if (badge != null) {
                    Text(
                        badge,
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (progress != null) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                            .background(Color(0x88000000)),
                    ) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Palette.text)
        if (subtitle != null) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Palette.dim)
        }
    }
}

@Composable
fun <T> Rail(title: String, items: List<T>, content: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(
            title, Modifier.padding(start = 48.dp, bottom = 10.dp),
            fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items) { content(it) }
        }
    }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    number: Boolean = false,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val fm = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fr = focusRequester ?: remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = LocalFocusEnabled.current,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.NumberPassword
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { fm.moveFocus(FocusDirection.Down) },
            onDone = {
                keyboard?.hide()
                onDone()
            },
            onSearch = {
                keyboard?.hide()
                onDone()
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.accent,
            unfocusedBorderColor = Palette.surface2,
            focusedLabelColor = Palette.accent,
            unfocusedLabelColor = Palette.dim,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text,
            cursorColor = Palette.accent,
        ),
        modifier = modifier
            .focusRequester(fr)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> fm.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
                    else -> false
                }
            },
    )
}

@Composable
fun TvDialog(onDismiss: () -> Unit, width: Dp = 560.dp, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalFocusEnabled provides true) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(Palette.surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        }
    }
}

@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    hint: String? = null,
    password: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    TvDialog(onDismiss) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        if (hint != null) Text(hint, fontSize = 14.sp, color = Palette.dim)
        TvTextField(
            text, { text = it }, title, Modifier.fillMaxWidth(), password = password,
            focusRequester = fr, imeAction = ImeAction.Done, onDone = { onSave(text.trim()) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save", { onSave(text.trim()) }, primary = true)
            PillButton("Cancel", onDismiss)
        }
    }
}

@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, check: (String) -> Boolean, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    val submit: () -> Unit = {
        if (check(pin)) {
            onOk()
        } else {
            wrong = true
            pin = ""
        }
    }
    TvDialog(onDismiss, width = 440.dp) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        TvTextField(
            pin, { if (it.length <= 8) pin = it.filter(Char::isDigit) }, "PIN", Modifier.fillMaxWidth(),
            password = true, number = true, focusRequester = fr, imeAction = ImeAction.Done, onDone = submit,
        )
        if (wrong) Text("Wrong PIN, try again", color = Palette.danger, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Unlock", submit, primary = true)
            PillButton("Cancel", onDismiss)
        }
    }
}

@Composable
fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to Palette.bg, 0.45f to Palette.bg.copy(alpha = 0.85f), 1f to Palette.bg.copy(alpha = 0.2f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Palette.bg,
                ),
            ),
        )
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.dim, fontSize = 16.sp)
    }
}

fun initials(s: String): String =
    s.split(' ', '-', ':', '|').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }

fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private val CHANNEL_TAG = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]\\s*")

/** "CA: CBC CALGARY" -> ("CA", "CBC CALGARY"). */
fun splitChannelName(name: String): Pair<String?, String> {
    val m = CHANNEL_TAG.find(name) ?: return null to name
    val rest = name.substring(m.range.last + 1).trim()
    return if (rest.isEmpty()) null to name else m.groupValues[1].uppercase() to rest
}

/**
 * Channel card: a near-square tile so square logos fill it, a soft light plate behind the
 * logo so logos with white or transparent backgrounds both read cleanly, the country tag
 * as a small badge, and what's on now underneath.
 */
@Composable
fun ChannelTile(
    ch: dev.relay.tv.data.Channel,
    onClick: () -> Unit,
    key: String,
    nowPlaying: String? = null,
    width: Dp = 168.dp,
) {
    val (tag, title) = remember(ch.name) { splitChannelName(ch.name) }
    Column(Modifier.width(width)) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(1.25f), key = key, shape = RoundedCornerShape(14.dp)) { f ->
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(if (f) Palette.surface2 else Palette.surface, Palette.bg),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize(0.72f)
                        .background(Color(0xFFF2F5FA), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(title), color = Color(0xFF5B6B85), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (!ch.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().background(Color(0xFFF2F5FA)),
                        )
                    }
                }
                if (tag != null) {
                    Text(
                        tag,
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Palette.bg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = Palette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (ch.hasArchive) {
                    Text(
                        "CATCH-UP",
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Palette.bg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = Palette.accent2, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Palette.text, fontWeight = FontWeight.SemiBold)
        if (nowPlaying != null) {
            Text(nowPlaying, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Palette.dim)
        }
    }
}

/**
 * Fades the top and bottom edges of a scrolling area so rows that scroll under the tab bar
 * dissolve instead of being sliced in half. Lists add matching top padding so nothing is
 * faded at rest.
 */
fun Modifier.fadeEdges(top: Dp = 20.dp, bottom: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        val t = top.toPx()
        val b = bottom.toPx()
        if (t > 0f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = t,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        if (b > 0f && h > b) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black, 1f to Color.Transparent, startY = h - b, endY = h,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
    }
