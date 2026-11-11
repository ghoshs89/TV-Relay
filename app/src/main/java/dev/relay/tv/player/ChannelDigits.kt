package dev.relay.tv.player

/** Number keys typed on the remote to pick a channel. A sixth digit starts a new number. */
class ChannelDigits(private val maxLength: Int = 5) {
    private val typed = StringBuilder()

    /** Adds [digit] and returns what has been typed so far. */
    fun press(digit: Int): String {
        if (typed.length >= maxLength) typed.clear()
        typed.append(digit)
        return typed.toString()
    }

    /** The number typed, or null if there is none. Clears the buffer either way. */
    fun take(): Int? {
        val number = typed.toString().toIntOrNull()
        typed.clear()
        return number
    }
}
