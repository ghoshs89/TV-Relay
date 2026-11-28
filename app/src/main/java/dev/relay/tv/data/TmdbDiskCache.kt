package dev.relay.tv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * TMDB answers kept on disk for [ttlMs] (7 days), one small JSON file per question, at most [maxFiles]
 * (the oldest go first). Lives in the cache folder, so Android may clear it; a missing or damaged
 * file is simply asked again. Never holds a key.
 */
class TmdbDiskCache(
    private val dir: File,
    private val ttlMs: Long = 7L * 86_400_000L,
    private val maxFiles: Int = 800,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private class Entry(val at: Long, val v: JsonElement)

    @Volatile private var writes = 0

    private fun file(key: String): File = File(dir, SyncCodec.sha(key).take(24) + ".json")

    fun <T> read(key: String, ser: KSerializer<T>): T? = try {
        val f = file(key)
        if (!f.isFile) null
        else {
            val e = Jsons.json.decodeFromString(Entry.serializer(), f.readText())
            if (clock() - e.at > ttlMs) {
                f.delete()
                null
            } else {
                Jsons.json.decodeFromJsonElement(ser, e.v)
            }
        }
    } catch (_: Exception) {
        null
    }

    fun <T> write(key: String, value: T, ser: KSerializer<T>) {
        try {
            dir.mkdirs()
            val e = Entry(clock(), Jsons.json.encodeToJsonElement(ser, value))
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(Jsons.json.encodeToString(Entry.serializer(), e))
            if (!tmp.renameTo(f)) tmp.delete()
            if (++writes % 50 == 0) trim()
        } catch (_: Exception) {
        }
    }

    /** Keeps the newest [maxFiles] files. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }
}
