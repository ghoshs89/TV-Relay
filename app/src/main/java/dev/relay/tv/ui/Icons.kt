package dev.relay.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small shapes the built-in icon set doesn't have, so every TV draws the same thing instead of its own emoji font. */
object RelayIcons {
    /** A speech bubble, for lists grouped by language. */
    val Language: ImageVector by lazy {
        ImageVector.Builder("Language", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(16f)
            horizontalLineTo(8f)
            lineTo(4f, 20f)
            close()
        }.build()
    }
}
