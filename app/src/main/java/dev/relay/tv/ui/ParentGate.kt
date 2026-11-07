package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.ParentPin

/**
 * Asks for the parent PIN before an action that changes who can watch what. With no parent PIN
 * set, actions run straight away.
 */
class ParentGate {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var title by mutableStateOf("")

    fun run(title: String, action: () -> Unit) {
        if (!RelayApp.instance.store.hasParentPin) {
            action()
        } else {
            this.title = title
            pending = action
        }
    }
}

@Composable
fun rememberParentGate(): ParentGate {
    val gate = remember { ParentGate() }
    gate.pending?.let { action ->
        PinDialog(
            title = gate.title,
            onDismiss = { gate.pending = null },
            check = { ParentPin.verify(it, RelayApp.instance.store.parentPin) },
            onOk = {
                gate.pending = null
                action()
            },
        )
    }
    return gate
}

/** Sets, changes or removes the parent PIN. Callers check the current PIN first. */
@Composable
fun ParentPinDialog(onDismiss: () -> Unit, intro: String? = null) {
    val store = RelayApp.instance.store
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    val save: () -> Unit = {
        when {
            !ParentPin.isValid(pin) -> err = "Use 4 to 8 digits."
            pin != again -> err = "The two PINs don't match."
            else -> {
                store.parentPin = ParentPin.hash(pin)
                AppLog.i("Access", "Parent PIN set")
                onDismiss()
            }
        }
    }
    TvDialog(onDismiss, width = 480.dp) {
        Text("Parent PIN", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            intro ?: "Needed to add or remove profiles, leave a kids profile, show adult categories, " +
                "restore a backup, change servers or sign out.",
            fontSize = 14.sp, color = Palette.dim,
        )
        TvTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, "New PIN (4 to 8 digits)", Modifier.fillMaxWidth(),
            password = true, number = true, focusRequester = fr,
        )
        TvTextField(
            again, { again = it.filter(Char::isDigit).take(8) }, "Same PIN again", Modifier.fillMaxWidth(),
            password = true, number = true, imeAction = ImeAction.Done, onDone = save,
        )
        err?.let { Text(it, color = Palette.danger, fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save", save, primary = true)
            if (store.hasParentPin) {
                PillButton("Remove PIN", {
                    store.parentPin = null
                    AppLog.i("Access", "Parent PIN removed")
                    onDismiss()
                }, danger = true)
            }
            PillButton(if (intro != null) "Not now" else "Cancel", onDismiss)
        }
    }
}
