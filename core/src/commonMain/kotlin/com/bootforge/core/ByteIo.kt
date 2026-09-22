package com.bootforge.core

/**
 * Growable byte sink. Replaces java.io.ByteArrayOutputStream so the same code
 * compiles for the JVM and for Kotlin/Native.
 */
class ByteSink(capacity: Int = 256) {

    @JvmField
    var buf: ByteArray = ByteArray(if (capacity < 16) 16 else capacity)
        private set

    @JvmField
    var size: Int = 0
        internal set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    /** Pre-reserves room for [target] total bytes (used by the inflate match copy). */
    fun growTo(target: Int) {
        if (target <= size) return
        ensure(target - size)
    }

    fun writeByte(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun write(src: ByteArray, off: Int = 0, len: Int = src.size) {
        if (len <= 0) return
        ensure(len)
        src.copyInto(buf, size, off, off + len)
        size += len
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Minimal byte source over an in-memory array. */
class ByteSource(private val data: ByteArray) {
    private var pos = 0

    fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF

    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len, data.size - pos)
        data.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    fun exhausted(): Boolean = pos >= data.size
}

/**
 * Little endian bit writer, used by the DEFLATE implementation.
 */
internal class BitWriter(capacity: Int = 1024) {
    var buf = ByteArray(capacity)
        private set
    private var bytePos = 0
    private var acc = 0
    private var bits = 0

    private fun ensure(extra: Int) {
        if (bytePos + extra <= buf.size) return
        var cap = buf.size
        while (cap < bytePos + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    /** Writes [count] bits of [value], LSB first (deflate stream order). */
    fun bits(value: Int, count: Int) {
        for (i in 0 until count) {
            acc = acc or (((value ushr i) and 1) shl bits)
            bits++
            if (bits == 8) {
                ensure(1)
                buf[bytePos++] = acc.toByte()
                acc = 0
                bits = 0
            }
        }
    }

    fun align() {
        if (bits > 0) {
            ensure(1)
            buf[bytePos++] = acc.toByte()
            acc = 0
            bits = 0
        }
    }

    fun raw(data: ByteArray) {
        align()
        ensure(data.size)
        data.copyInto(buf, bytePos)
        bytePos += data.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(bytePos)
}

internal class BitReader(internal val data: ByteArray) {
    private var pos = 0
    private var acc = 0
    private var bits = 0

    fun bit(): Int {
        if (bits == 0) {
            if (pos >= data.size) return 0
            acc = data[pos++].toInt() and 0xFF
            bits = 8
        }
        val b = acc and 1
        acc = acc ushr 1
        bits--
        return b
    }

    fun bits(count: Int): Int {
        var v = 0
        for (i in 0 until count) v = v or (bit() shl i)
        return v
    }

    fun align() {
        acc = 0
        bits = 0
    }

    val bytePos: Int get() = pos

    fun skipBytes(n: Int) {
        align()
        pos += n
    }

    fun bytesLeft(): Int = data.size - pos
}
