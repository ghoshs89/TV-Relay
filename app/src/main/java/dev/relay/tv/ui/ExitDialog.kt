package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Asked when Back is pressed on a screen with nothing underneath it (Home, the profile picker, sign-in).
 * Closing needs a deliberate OK on [Close Relay]; the dialog's own dismissal (Back again, or a tap outside)
 * means stay, so a second press of Back by mistake can never close the app.
 */
@Composable
fun ExitDialog(onStay: () -> Unit, onClose: () -> Unit) {
    val stay = remember { FocusRequester() }
    InitialFocus(stay)
    TvDialog(onStay, width = 480.dp) {
        Text("Close Relay?", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text("Relay will close. Choose Stay to keep using it.", fontSize = Type.bodyLarge, color = Palette.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Stay in Relay", onStay, focusRequester = stay, primary = true, key = "exit:stay")
            PillButton("Close Relay", onClose, danger = true, key = "exit:close")
        }
    }
}
