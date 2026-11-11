package dev.relay.tv.ui

/**
 * OK held down on a remote, told apart from OK pressed once. A remote sends one key-down, then (after
 * about half a second) more key-downs with a repeat count above zero, then one key-up. Pure, so the
 * rules can be tested without a remote.
 */
class OkPressTracker {
    enum class Result { Nothing, Click, LongClick }

    private var pressed = false
    private var fired = false

    /** A key-down for OK or Enter. [repeatCount] is 0 for the first one. */
    fun down(repeatCount: Int): Result {
        if (repeatCount == 0) {
            pressed = true
            fired = false
            return Result.Nothing
        }
        // Repeats that arrive without this item having seen the press (focus moved here while the
        // button was already held) are not a long press on this item.
        if (!pressed || fired) return Result.Nothing
        fired = true
        return Result.LongClick
    }

    /** The key-up. A short press clicks; a long press already acted and does not click again. */
    fun up(): Result {
        val result = if (pressed && !fired) Result.Click else Result.Nothing
        pressed = false
        fired = false
        return result
    }
}

/**
 * After a long press acts, the button is usually still held. Its remaining repeats and its key-up
 * must not reach whatever is now on screen (a confirm dialog's first button, say) and click it. The
 * activity asks [swallows] before passing a key to the screen.
 */
object LongPressGuard {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1

    private var armedKey = -1

    /** Called when a long press from [keyCode] has acted. */
    fun arm(keyCode: Int) {
        armedKey = keyCode
    }

    /** True when this key event belongs to a long press that already acted and must be dropped. */
    fun swallows(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (armedKey != keyCode) return false
        if (action == ACTION_DOWN && repeatCount == 0) {
            // A new press: the earlier key-up was lost (a window took focus, say).
            armedKey = -1
            return false
        }
        if (action == ACTION_UP) armedKey = -1
        return true
    }

    fun reset() {
        armedKey = -1
    }
}
