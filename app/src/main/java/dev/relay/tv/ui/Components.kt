package dev.relay.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.relay.tv.data.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Palette {
    /** Neutral near-black theme; navy looks very blue on TVs in vivid picture modes. */
    var neutral by mutableStateOf(false)

    val bg: Color get() = if (neutral) Color(0xFF0D0E11) else Color(0xFF0B1220)
    val surface: Color get() = if (neutral) Color(0xFF17191D) else Color(0xFF141E30)
    val surface2: Color get() = if (neutral) Color(0xFF262A31) else Color(0xFF1E2B45)
    val accent = Color(0xFF3DDC97)
    val accent2 = Color(0xFF6CB4FF)
    val text = Color(0xFFE8EEF7)
    val dim: Color get() = if (neutral) Color(0xFF9AA1AC) else Color(0xFF8A98B0)
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
 *
 * Kept cheap because a list can have dozens on screen: no coroutine is started per item (focus is
 * read from onFocusChanged, the scale is animated only when focus changes, and restoring focus is
 * done once per screen by [FocusRestorer]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    key: String? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    focusRequester: FocusRequester? = null,
    scaleOnFocus: Float = 1.05f,
    onFocused: () -> Unit = {},
    /** Long tap, or OK held down on a remote (and the Menu key, where the remote has one). */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val enabled = LocalFocusEnabled.current
    val memory = LocalFocusMemory.current
    val source = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val press = if (onLongClick != null) remember { OkPressTracker() } else null

    if (key != null) {
        DisposableEffect(memory, key, requester) {
            memory.register(key, requester)
            onDispose { memory.unregister(key, requester) }
        }
    }

    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged { state ->
                val now = state.isFocused
                if (now != focused) {
                    focused = now
                    scope.launch { scale.animateTo(if (now) scaleOnFocus else 1f) }
                    if (now) {
                        if (key != null) memory.lastKey = key
                        latestOnFocused()
                    }
                }
            }
            .graphicsLayer {
                val v = scale.value
                scaleX = v
                scaleY = v
            }
            .clip(shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Color.Transparent, shape)
            .then(
                if (press == null) {
                    Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                        .onPreviewKeyEvent { e ->
                            if (!enabled) return@onPreviewKeyEvent false
                            val code = e.nativeKeyEvent.keyCode
                            when {
                                e.key == Key.Menu -> {
                                    if (e.type == KeyEventType.KeyDown) { latestOnLongClick?.invoke(); true } else false
                                }
                                // OK is handled here, not by combinedClickable, which doesn't treat a held OK as a long press.
                                e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                                    val result = if (e.type == KeyEventType.KeyDown) press.down(e.nativeKeyEvent.repeatCount) else press.up()
                                    when (result) {
                                        OkPressTracker.Result.Click -> latestOnClick()
                                        OkPressTracker.Result.LongClick -> {
                                            LongPressGuard.arm(code)
                                            latestOnLongClick?.invoke()
                                        }
                                        OkPressTracker.Result.Nothing -> Unit
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .combinedClickable(
                            interactionSource = source, indication = null, enabled = enabled,
                            onClick = onClick, onLongClick = onLongClick,
                        )
                },
            ),
    ) {
        content(focused)
    }
}

/**
 * One per screen. When focus should return to where it was (back from the player or a detail page,
 * or a dialog closing) it asks the remembered item to take focus, trying for a few seconds in case
 * the list is still laying out.
 */
@Composable
fun FocusRestorer() {
    val enabled = LocalFocusEnabled.current
    val memory = LocalFocusMemory.current
    val tick = memory.restoreTick
    LaunchedEffect(memory, tick, enabled) {
        if (!enabled || tick == 0 || memory.lastKey == null) return@LaunchedEffect
        delay(80)
        repeat(40) {
            if (memory.restore()) {
                AppLog.d("Focus", "Focus returned to ${memory.lastKey}")
                return@LaunchedEffect
            }
            delay(80)
        }
        AppLog.d("Focus", "Focus could not return to ${memory.lastKey}")
    }
}

/** [value], but a change only shows up after it has stayed the same for [ms] (at once when [immediate] says so). */
@Composable
fun <T> rememberDebounced(value: T, ms: Long, immediate: (T) -> Boolean = { false }): T {
    var out by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != out) {
            if (!immediate(value)) delay(ms)
            out = value
        }
    }
    return out
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
    /** Tighter padding and a smaller label, for bars and side panels. */
    compact: Boolean = false,
    /** Fill the width it is given and centre the label (two buttons side by side). */
    fill: Boolean = false,
) {
    Focusable(onClick, modifier, key = key, shape = RoundedCornerShape(50), focusRequester = focusRequester) { f ->
        val bg = when {
            f -> if (danger) Palette.danger else Palette.accent
            primary -> Palette.surface2
            else -> Palette.surface
        }
        val fg = if (f) Color.Black else if (danger) Palette.danger else Palette.text
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .background(bg)
                .padding(horizontal = if (compact) 12.dp else 22.dp, vertical = if (compact) 8.dp else 11.dp),
            horizontalArrangement = if (fill) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            }
            Text(
                text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = if (compact) Type.caption else Type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    /** Drawn before the text (a pin, a star, a language mark). */
    icon: ImageVector? = null,
    /** Drawn after the count (open or closed section arrow). */
    trailingIcon: ImageVector? = null,
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
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (selected) Palette.accent else textColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            // A long name scrolls while focused so the whole of it can be read.
            Text(
                text, Modifier.weight(1f).then(if (f) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                color = if (selected) Palette.accent else textColor,
                fontSize = Type.body, maxLines = 1, overflow = if (f) TextOverflow.Clip else TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (trailing != null) Text(trailing, color = Palette.dim, fontSize = Type.caption, maxLines = 1)
            if (trailingIcon != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailingIcon, contentDescription = null, tint = Palette.dim, modifier = Modifier.size(18.dp))
            }
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
    badgeColor: Color = Palette.accent,
    /** Draw a star before the badge text (a rating). */
    badgeStar: Boolean = false,
    fit: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    parseTitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    /** The title already split into its parts by the caller, so it isn't parsed twice per card. */
    titleParts: dev.relay.tv.data.TitleParts? = null,
) {
    // Provider names like "ES - Nocturno (2026) (US)" show as "Nocturno" with an "ES" tag on the art.
    val parts = titleParts ?: remember(title, parseTitle) { if (parseTitle) dev.relay.tv.data.TitleParts.parse(title) else null }
    val shown = parts?.title ?: title
    val tag = parts?.tag
    Column(modifier.width(width)) {
        Focusable(
            onClick, Modifier.fillMaxWidth().aspectRatio(aspect), key = key,
            focusRequester = focusRequester, onFocused = onFocused, onLongClick = onLongClick,
        ) { _ ->
            Box(Modifier.fillMaxSize().background(Palette.surface2)) {
                Text(
                    initials(shown), Modifier.align(Alignment.Center), color = Palette.dim,
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
                if (tag != null) {
                    Text(
                        tag,
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = Color.White, fontSize = Type.label, fontWeight = FontWeight.Bold,
                    )
                }
                if (badge != null) {
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (badgeStar) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = badgeColor, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(badge, color = badgeColor, fontSize = Type.label, fontWeight = FontWeight.Bold)
                    }
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
        Text(shown, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = Type.caption, color = Palette.text)
        if (subtitle != null) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Type.label, color = Palette.dim)
        }
    }
}

@Composable
fun <T> Rail(
    title: String,
    items: List<T>,
    key: ((T) -> Any)? = null,
    hint: String? = null,
    /** Shown after the last item (a "Clear all" button, say). */
    footer: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 20.dp)) {
        Row(Modifier.padding(start = 48.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            if (hint != null) {
                Text(hint, Modifier.padding(start = 14.dp, bottom = 2.dp), fontSize = 13.sp, color = Palette.dim)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items, key = key) { content(it) }
            if (footer != null) item(key = "__footer") { footer() }
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
    autoOpen: Boolean = false,
) {
    if (useRelayKeyboard()) {
        RelayTextField(value, onValueChange, label, modifier, password = password, focusRequester = focusRequester, onDone = onDone)
        return
    }
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
            .onFocusChanged { if (it.isFocused && autoOpen) keyboard?.show() }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> fm.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
                    // TVs give the field focus without asking Android for the keyboard: OK does that.
                    Key.DirectionCenter -> {
                        keyboard?.show()
                        true
                    }
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
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        if (hint != null) Text(hint, fontSize = Type.small, color = Palette.dim)
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
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        TvTextField(
            pin, { if (it.length <= 8) pin = it.filter(Char::isDigit) }, "PIN", Modifier.fillMaxWidth(),
            password = true, number = true, focusRequester = fr, imeAction = ImeAction.Done, onDone = submit,
        )
        if (wrong) Text("Wrong PIN, try again", color = Palette.danger, fontSize = Type.small)
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
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val request = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).crossfade(true).build() }
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
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
    // "Loading…" gets a small spinner so a list that is still being built never reads as empty.
    val loading = text.startsWith("Loading")
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Palette.dim, fontSize = Type.bodyLarge)
        }
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

/** "CA: CBC CALGARY" -> ("CA", "CBC CALGARY"). */
fun splitChannelName(name: String): Pair<String?, String> = dev.relay.tv.data.ChannelKeys.split(name)

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
    onLongClick: (() -> Unit)? = null,
) {
    val (tag, rawTitle) = remember(ch.name) { splitChannelName(ch.name) }
    val title = remember(rawTitle) { dev.relay.tv.data.ProviderNames.clean(rawTitle) }
    var logoOk by remember(ch.logo) { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(1.25f), key = key, shape = RoundedCornerShape(14.dp), onLongClick = onLongClick) { f ->
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
                    // Initials stay visible until the logo actually loads, so a broken logo never leaves a blank tile.
                    if (!logoOk) Text(initials(title), color = Color(0xFF5B6B85), fontSize = Type.heading, fontWeight = FontWeight.Bold)
                    if (!ch.logo.isNullOrBlank() && !LogoFailures.has(ch.logo)) {
                        AsyncImage(
                            model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            onSuccess = { logoOk = true },
                            onError = {
                                logoOk = false
                                LogoFailures.add(ch.logo)
                            },
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
                        color = Palette.accent, fontSize = Type.label, fontWeight = FontWeight.Bold,
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
                        color = Palette.accent2, fontSize = Type.label, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Type.caption, color = Palette.text, fontWeight = FontWeight.SemiBold)
        if (nowPlaying != null) {
            Text(nowPlaying, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Type.label, color = Palette.dim)
        }
    }
}

/**
 * Fades the top and bottom edges of a scrolling area so rows that scroll under the tab bar
 * dissolve instead of being sliced in half. Lists add matching top padding so nothing is
 * faded at rest. The fade is a gradient of the background colour drawn over the content
 * ([over], read when drawing), not an offscreen layer: an extra full-size buffer per frame is
 * what made lists stutter on TV graphics chips.
 */
fun Modifier.fadeEdges(top: Dp = 20.dp, bottom: Dp = 28.dp, over: () -> Color = { Palette.bg }): Modifier = this
    .drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        val t = top.toPx()
        val b = bottom.toPx()
        val c = over()
        if (t > 0f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(0f to c, 1f to Color.Transparent, startY = 0f, endY = t),
                size = androidx.compose.ui.geometry.Size(w, t),
            )
        }
        if (b > 0f && h > b) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 1f to c, startY = h - b, endY = h),
                topLeft = androidx.compose.ui.geometry.Offset(0f, h - b),
                size = androidx.compose.ui.geometry.Size(w, b),
            )
        }
    }
