package dev.relay.tv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import dev.relay.tv.ui.HomeScreen
import dev.relay.tv.ui.LocalFocusEnabled
import dev.relay.tv.ui.LocalFocusMemory
import dev.relay.tv.ui.LocalNav
import dev.relay.tv.ui.LoginScreen
import dev.relay.tv.ui.MovieDetailScreen
import dev.relay.tv.ui.Nav
import dev.relay.tv.ui.Palette
import dev.relay.tv.ui.ProfilesScreen
import dev.relay.tv.ui.ReminderCenter
import dev.relay.tv.ui.ReminderDialog
import dev.relay.tv.ui.RelayTheme
import dev.relay.tv.ui.Route
import dev.relay.tv.ui.SeriesDetailScreen
import dev.relay.tv.ui.SetupScreen
import dev.relay.tv.ui.SplashScreen
import dev.relay.tv.ui.UiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RelayTheme { AppRoot() }
        }
    }
}

@Composable
fun AppRoot() {
    val app = RelayApp.instance
    val nav = remember { Nav(if (app.store.account == null) Route.Login else Route.Profiles) }
    val ui = app.ui
    var splash by rememberSaveable { mutableStateOf(true) }

    // Start loading the library during the splash, on a scope navigation can't cancel.
    LaunchedEffect(Unit) {
        if (app.store.account != null) app.appScope.launch { app.catalog.load() }
    }

    // Phones: let the login screen rotate to portrait for easier typing; everything
    // else is a landscape TV layout. TVs ignore orientation requests.
    val activity = LocalContext.current as? Activity
    val topRoute = nav.stack.last()
    LaunchedEffect(topRoute, splash) {
        activity?.requestedOrientation =
            if (topRoute == Route.Login && !splash) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // Coming back from the player: put focus back on the channel or poster that was selected.
    val owner = LocalContext.current as? ComponentActivity
    DisposableEffect(owner) {
        var first = true
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else nav.memory(nav.stack.lastIndex, app.ui.focus).restoreTick++
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    if (splash) {
        SplashScreen(onDone = { splash = false })
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop(ui.focus) }

    CompositionLocalProvider(LocalNav provides nav) {
        // Phones draw edge to edge on Android 15: keep content clear of the status and gesture bars.
        // TVs report no insets, so nothing moves there.
        Box(Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val top = nav.stack.lastIndex
            nav.stack.forEachIndexed { i, route ->
                // Home stays composed underneath detail pages so its scroll and focus survive "back".
                if (i == top || route == Route.Home) {
                    key(i) {
                        CompositionLocalProvider(
                            LocalFocusEnabled provides (i == top),
                            LocalFocusMemory provides nav.memory(i, ui.focus),
                        ) {
                            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                                when (route) {
                                    Route.Login -> LoginScreen(
                                        onDone = { nav.reset(Route.Profiles) },
                                        onCancel = if (nav.stack.size > 1) ({ nav.pop(ui.focus) }) else null,
                                    )
                                    Route.Profiles -> ProfilesScreen(
                                        onPick = { p ->
                                            app.session.profile = p
                                            app.store.lastProfileId = p.id
                                            app.ui = UiState()
                                            nav.reset(Route.Home)
                                        },
                                    )
                                    Route.Home -> HomeScreen()
                                    is Route.MovieDetail -> MovieDetailScreen(route.movie)
                                    is Route.SeriesDetail -> SeriesDetailScreen(route.series)
                                    Route.Setup -> SetupScreen(onDone = { nav.pop(ui.focus) })
                                }
                            }
                        }
                    }
                }
            }
            ReminderCenter.due?.let { ReminderDialog(it) }
        }
    }
}
