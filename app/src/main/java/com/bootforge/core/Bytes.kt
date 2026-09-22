package com.bootforge.core

/**
 * Minimal little-endian byte helpers used by the image parsers.
 */
object Bytes {

    fun get32(buf: ByteArray, off: Int): Long {
        if (off + 4 > buf.size) return 0
        return (buf[off].toLong() and 0xFF) or
            ((buf[off + 1].toLong() and 0xFF) shl 8) or
            ((buf[off + 2].toLong() and 0xFF) shl 16) or
            ((buf[off + 3].toLong() and 0xFF) shl 24)
    }

    fun get64(buf: ByteArray, off: Int): Long {
        if (off + 8 > buf.size) return 0
        var v = 0L
        for (i in 0..7) v = v or ((buf[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun put32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun put64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0..7) buf[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /** Reads a NUL terminated ASCII string. */
    fun cstr(buf: ByteArray, off: Int, max: Int): String {
        var end = off
        val limit = minOf(off + max, buf.size)
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    fun putCstr(buf: ByteArray, off: Int, value: String, max: Int) {
        val b = value.toByteArray(Charsets.UTF_8)
        val n = minOf(b.size, max - 1)
        System.arraycopy(b, 0, buf, off, n)
    }

    fun align(v: Long, pageSize: Int): Long {
        val ps = pageSize.toLong()
        return (v + ps - 1) / ps * ps
    }

    fun hex(v: Long, width: Int = 8): String = v.toString(16).uppercase().padStart(width, '0')
}
