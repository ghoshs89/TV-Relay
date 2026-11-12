package dev.relay.tv.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** The saved copy of the library is damaged, cut short, or not written by this version of Relay. */
class SnapshotFormatException(message: String) : IOException(message)

/**
 * Binary encoding for the saved library copy (see LibrarySnapshot). Pure, so it is unit tested on a
 * computer. Rules:
 *
 * * Integers are varints: unsigned, 7 bits per byte, low bits first. Signed values are zig-zag
 *   encoded first, so small negative numbers stay small.
 * * A string is its UTF-8 byte length plus one as a varint (0 means null, 1 means the empty
 *   string), then the bytes. Not DataOutputStream.writeUTF: that has a 64 KB limit and its own
 *   variant of UTF-8.
 * * A double is its raw 64 bits, 8 bytes, so every value (-0.0 and NaN too) reads back exactly.
 * * Repeated text goes through [SharedWriter] / [SharedReader]: the first use writes the text, later
 *   uses write a small index, and the reader hands out one instance per distinct value.
 */
class SnapshotWriter(private val out: OutputStream, bufferSize: Int = 256 * 1024) {
    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private val crc = CRC32()

    /** Bytes handed to the stream so far (after [finish], the whole section). */
    var bytesWritten = 0L
        private set

    private fun drain() {
        if (pos > 0) {
            out.write(buf, 0, pos)
            crc.update(buf, 0, pos)
            bytesWritten += pos
            pos = 0
        }
    }

    private fun room(n: Int) {
        if (pos + n > buf.size) drain()
    }

    fun writeByte(b: Int) {
        room(1)
        buf[pos++] = b.toByte()
    }

    /** Unsigned: every one of the 64 bits is data. */
    fun writeVarLong(v: Long) {
        room(10)
        var x = v
        while (x and -128L != 0L) {
            buf[pos++] = ((x and 127L) or 128L).toByte()
            x = x ushr 7
        }
        buf[pos++] = x.toByte()
    }

    /** Unsigned 32-bit: pass counts, indexes and string codes. */
    fun writeVarInt(v: Int) = writeVarLong(v.toLong() and 0xFFFFFFFFL)

    fun writeZigZagInt(v: Int) = writeVarInt((v shl 1) xor (v shr 31))

    fun writeZigZagLong(v: Long) = writeVarLong((v shl 1) xor (v shr 63))

    fun writeDouble(d: Double) {
        room(8)
        val bits = d.toRawBits()
        for (i in 0 until 8) buf[pos++] = (bits ushr (8 * i)).toByte()
    }

    /** Null, empty and text all read back as they were written. Text with an unpaired surrogate cannot be encoded and is refused. */
    fun writeString(s: String?) {
        if (s == null) {
            writeVarInt(0)
            return
        }
        writeVarLong(utf8Length(s).toLong() + 1)
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            when {
                c < 0x80 -> put(c)
                c < 0x800 -> {
                    put(0xC0 or (c shr 6))
                    put(0x80 or (c and 0x3F))
                }
                Character.isHighSurrogate(s[i]) -> {
                    val cp = Character.toCodePoint(s[i], s[i + 1])
                    put(0xF0 or (cp shr 18))
                    put(0x80 or ((cp shr 12) and 0x3F))
                    put(0x80 or ((cp shr 6) and 0x3F))
                    put(0x80 or (cp and 0x3F))
                    i++
                }
                else -> {
                    put(0xE0 or (c shr 12))
                    put(0x80 or ((c shr 6) and 0x3F))
                    put(0x80 or (c and 0x3F))
                }
            }
            i++
        }
    }

    private fun put(b: Int) {
        if (pos == buf.size) drain()
        buf[pos++] = b.toByte()
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val c = ch.code
            when {
                c < 0x80 -> n += 1
                c < 0x800 -> n += 2
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        n += 4
                        i++
                    } else {
                        throw SnapshotFormatException("text with an unpaired surrogate")
                    }
                }
                Character.isLowSurrogate(ch) -> throw SnapshotFormatException("text with an unpaired surrogate")
                else -> n += 3
            }
            i++
        }
        return n
    }

    /** Hands the last bytes to the stream (without flushing or closing it) and returns the CRC32 of everything written. */
    fun finish(): Long {
        drain()
        return crc.value
    }
}

/**
 * Reads what [SnapshotWriter] wrote: exactly [length] bytes from [input], through its own buffer
 * and a CRC32. A section is never read into one big array; the buffer is the only memory used.
 * Anything wrong (a value that does not fit, a string longer than the data left, running out of
 * bytes) throws [SnapshotFormatException]; the CRC is compared by the caller after [finish].
 */
class SnapshotReader(private val input: InputStream, length: Long, bufferSize: Int = 256 * 1024) {
    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private var lim = 0
    private var remaining = length
    private val crc = CRC32()
    private var scratch = ByteArray(256)

    private fun fill(): Boolean {
        if (pos < lim) return true
        if (remaining <= 0) return false
        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw SnapshotFormatException("file ends early")
        crc.update(buf, 0, n)
        remaining -= n
        pos = 0
        lim = n
        return n > 0 || fill()
    }

    /** Bytes of this section not read yet, including those still in the buffer. */
    val left: Long get() = remaining + (lim - pos)

    fun readByte(): Int {
        if (pos >= lim && !fill()) throw SnapshotFormatException("section ends early")
        return buf[pos++].toInt() and 0xFF
    }

    fun readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte()
            if (shift == 63 && b > 1) throw SnapshotFormatException("number too large")
            result = result or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return result
            shift += 7
            if (shift > 63) throw SnapshotFormatException("number too long")
        }
    }

    /** Unsigned 32-bit as written by [SnapshotWriter.writeVarInt]; values above Int.MAX_VALUE come back negative. */
    fun readVarInt(): Int {
        val v = readVarLong()
        if (v < 0 || v > 0xFFFFFFFFL) throw SnapshotFormatException("number out of range")
        return v.toInt()
    }

    /** A count or index that must lie in 0..[max]. */
    fun readBounded(max: Int): Int {
        val v = readVarLong()
        if (v < 0 || v > max) throw SnapshotFormatException("count $v is outside 0..$max")
        return v.toInt()
    }

    fun readZigZagInt(): Int {
        val v = readVarInt()
        return (v ushr 1) xor -(v and 1)
    }

    fun readZigZagLong(): Long {
        val v = readVarLong()
        return (v ushr 1) xor -(v and 1L)
    }

    fun readDouble(): Double {
        var bits = 0L
        for (i in 0 until 8) bits = bits or (readByte().toLong() shl (8 * i))
        return Double.fromBits(bits)
    }

    fun readString(): String? {
        val code = readVarLong()
        if (code == 0L) return null
        val n = code - 1
        if (n < 0 || n > left) throw SnapshotFormatException("text longer than the data left")
        if (n == 0L) return ""
        val len = n.toInt()
        if (lim - pos >= len) {
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
        if (scratch.size < len) scratch = ByteArray(maxOf(len, scratch.size * 2))
        var got = 0
        while (got < len) {
            if (pos >= lim && !fill()) throw SnapshotFormatException("section ends early")
            val take = minOf(len - got, lim - pos)
            System.arraycopy(buf, pos, scratch, got, take)
            pos += take
            got += take
        }
        return String(scratch, 0, len, Charsets.UTF_8)
    }

    /** Text that must not be null. */
    fun readText(): String = readString() ?: throw SnapshotFormatException("missing text")

    /**
     * Checks that every byte of the section was used and returns the CRC32 of all of them. A
     * section that decodes to less than its length is as suspicious as one that runs out.
     */
    fun finish(): Long {
        if (pos < lim || remaining > 0) throw SnapshotFormatException("section is longer than its contents")
        return crc.value
    }
}

/** Writes repeated text once and then as an index. Codes: 0 null, 1 new text follows, n+2 text number n. */
class SharedWriter {
    private val ids = HashMap<String, Int>()

    fun write(w: SnapshotWriter, s: String?) {
        if (s == null) {
            w.writeVarInt(0)
            return
        }
        val id = ids[s]
        if (id != null) {
            w.writeVarInt(id + 2)
        } else {
            ids[s] = ids.size
            w.writeVarInt(1)
            w.writeString(s)
        }
    }
}

/** Reads what [SharedWriter] wrote, creating each distinct text once so every item that has it shares the same String. */
class SharedReader {
    private val table = ArrayList<String>()

    fun read(r: SnapshotReader): String? {
        val code = r.readVarInt()
        return when {
            code == 0 -> null
            code == 1 -> r.readText().also { table.add(it) }
            code < 0 || code - 2 >= table.size -> throw SnapshotFormatException("unknown shared text")
            else -> table[code - 2]
        }
    }

    fun readText(r: SnapshotReader): String = read(r) ?: throw SnapshotFormatException("missing text")
}
