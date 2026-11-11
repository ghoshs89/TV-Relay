package dev.relay.tv.data

/**
 * Saves values in the background, newest only. Screens write to the store from the main thread, and
 * turning a 400-entry watch history into text and handing it to the disk is slow on a TV processor.
 * Callers update their in-memory copy at once; this takes the saving off that thread and, when the
 * same key is written again before it has been saved, saves just the latest value.
 *
 * [post] runs a task a little later on a background thread. [save] receives the keys to write: text
 * to store, or null to remove the key. [flush] saves everything waiting right now, on the calling
 * thread (before the app is stopped, before a backup is read).
 */
class WriteBehind(
    private val post: (Runnable) -> Unit,
    private val save: (Map<String, String?>) -> Unit,
) {
    private val lock = Any()
    private val drainLock = Any()
    private var pending = LinkedHashMap<String, (() -> String)?>()
    private var queued = false

    /** [encode] produces the text to store, later and off the calling thread; null removes the key. */
    fun put(key: String, encode: (() -> String)?) {
        synchronized(lock) {
            pending[key] = encode
            if (queued) return
            queued = true
        }
        post(Runnable { drain() })
    }

    fun flush() = drain()

    val waiting: Int get() = synchronized(lock) { pending.size }

    private fun drain() {
        synchronized(drainLock) {
            val batch = synchronized(lock) {
                queued = false
                pending.also { pending = LinkedHashMap() }
            }
            if (batch.isEmpty()) return
            val text = LinkedHashMap<String, String?>()
            for ((key, encode) in batch) {
                // One value that can't be encoded must not stop the others from being saved.
                text[key] = try { encode?.invoke() } catch (e: Exception) { AppLog.w("Store", "Couldn't save $key", e); continue }
            }
            save(text)
        }
    }
}
