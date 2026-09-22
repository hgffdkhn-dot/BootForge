package com.bootforge.core

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin LZ4: block codec + the two container formats found in Android images.
 *  - legacy frame : magic 02 21 4C 18 (used by mkbootimg / magiskboot for ramdisks)
 *  - standard LZ4 frame : magic 04 22 4D 18
 *
 * No java.io dependency: runs unchanged on the JVM and on Kotlin/Native.
 */
object Lz4 {

    private const val MIN_MATCH = 4
    private const val MFLIMIT = 12
    private const val MAX_DISTANCE = 65535
    private const val MAX_MATCH = 65535 + MIN_MATCH
    private const val BLOCK = 1 shl 20
    private const val HASH_BITS = 16
    private const val HASH_SIZE = 1 shl HASH_BITS

    const val LEGACY_MAGIC = 0x184C2102L
    const val FRAME_MAGIC = 0x184D2204L

    // ---------------------------------------------------------------- block

    fun decompressBlock(src: ByteArray): ByteArray {
        var out = ByteArray(max(1024, src.size * 3))
        var len = 0
        var i = 0
        val n = src.size

        fun need(extra: Int) {
            if (len + extra <= out.size) return
            var cap = out.size
            while (cap < len + extra) cap *= 2
            out = out.copyOf(cap)
        }

        while (i < n) {
            val token = src[i++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) {
                while (i < n) {
                    val b = src[i++].toInt() and 0xFF
                    lit += b
                    if (b != 255) break
                }
            }
            val avail = min(lit, n - i)
            need(lit)
            if (avail > 0) src.copyInto(out, len, i, i + avail)
            len += lit
            i += lit
            if (i >= n) break

            if (i + 1 >= n) break
            val offset = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            var mlen = token and 0x0F
            if (mlen == 15) {
                while (i < n) {
                    val b = src[i++].toInt() and 0xFF
                    mlen += b
                    if (b != 255) break
                }
            }
            mlen += MIN_MATCH
            if (offset == 0 || offset > len) break
            need(mlen)
            val start = len - offset
            if (offset >= mlen) {
                // 不重叠：批量拷贝更快
                out.copyInto(out, len, start, start + mlen)
                len += mlen
            } else {
                var p = start
                var w = len
                val end = w + mlen
                while (w < end) out[w++] = out[p++]
                len = end
            }
        }
        return out.copyOf(len)
    }

    /**
     * Hash-table compressor. Emits valid LZ4 block sequences: the last sequence is
     * literal-only and every match keeps the required 12 byte end-of-block margin.
     */
    fun compressBlock(src: ByteArray): ByteArray {
        val n = src.size
        val out = ByteSink(n / 2 + 64)
        val table = IntArray(HASH_SIZE) // stores position + 1, 0 == empty
        val mask = HASH_SIZE - 1
        var anchor = 0
        var i = 0
        val limit = if (n - MFLIMIT < 0) 0 else n - MFLIMIT

        while (i < limit) {
            val key = hash(src, i) and mask
            val cand = table[key] - 1
            table[key] = i + 1
            if (cand < 0 || i - cand > MAX_DISTANCE) {
                i++
                continue
            }
            if (!equals4(src, cand, i)) {
                i++
                continue
            }
            var mlen = MIN_MATCH
            while (i + mlen < n && mlen < MAX_MATCH && src[cand + mlen] == src[i + mlen]) mlen++
            val litLen = i - anchor
            val matchCode = mlen - MIN_MATCH
            out.writeByte((min(litLen, 15) shl 4) or min(matchCode, 15))
            if (litLen >= 15) writeLength(out, litLen - 15)
            if (litLen > 0) out.write(src, anchor, litLen)
            val dist = i - cand
            out.writeByte(dist and 0xFF)
            out.writeByte((dist ushr 8) and 0xFF)
            if (matchCode >= 15) writeLength(out, matchCode - 15)
            i += mlen
            anchor = i
        }
        val litLen = n - anchor
        out.writeByte(min(litLen, 15) shl 4)
        if (litLen >= 15) writeLength(out, litLen - 15)
        if (litLen > 0) out.write(src, anchor, litLen)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteSink, value: Int) {
        var v = value
        while (v >= 255) {
            out.writeByte(255)
            v -= 255
        }
        out.writeByte(v)
    }

    private fun hash(src: ByteArray, i: Int): Int {
        val a = src[i].toInt() and 0xFF
        val b = src[i + 1].toInt() and 0xFF
        val c = src[i + 2].toInt() and 0xFF
        val d = src[i + 3].toInt() and 0xFF
        return (((a shl 8) or b) * 2654435761.toInt() xor ((c shl 4) or d)) ushr (32 - HASH_BITS) and (HASH_SIZE - 1)
    }

    private fun equals4(src: ByteArray, a: Int, b: Int): Boolean =
        src[a] == src[b] && src[a + 1] == src[b + 1] && src[a + 2] == src[b + 2] && src[a + 3] == src[b + 3]

    // ---------------------------------------------------------- containers

    fun compressLegacy(data: ByteArray): ByteArray {
        val out = ByteSink()
        writeLE32(out, LEGACY_MAGIC)
        var pos = 0
        while (pos < data.size) {
            val len = min(BLOCK, data.size - pos)
            writeLegacyBlock(out, data, pos, len)
            pos += len
        }
        return out.toByteArray()
    }

    fun compressFrame(data: ByteArray): ByteArray {
        val out = ByteSink()
        writeFrameHeader(out)
        var pos = 0
        while (pos < data.size) {
            val len = min(BLOCK, data.size - pos)
            writeFrameBlock(out, data, pos, len)
            pos += len
        }
        writeLE32(out, 0)
        return out.toByteArray()
    }

    fun decompressLegacy(data: ByteArray): ByteArray {
        val out = ByteSink()
        var i = 4
        while (i + 4 <= data.size) {
            var size = Bytes.get32(data, i)
            i += 4
            if (size == 0L) break
            if (size and 0x80000000L != 0L) {
                size = size and 0x7FFFFFFFL
                val end = min(i + size.toInt(), data.size)
                out.write(data, i, end - i)
                i = end
            } else {
                val end = i + size.toInt()
                if (end > data.size) break
                out.write(decompressBlock(data.copyOfRange(i, end)))
                i = end
            }
        }
        return out.toByteArray()
    }

    fun decompressFrame(data: ByteArray): ByteArray {
        val flg = data[4].toInt() and 0xFF
        var i = 6
        if (flg and 0x08 != 0) i += 8 // content size
        if (flg and 0x01 != 0) i += 4 // dictionary id
        i += 1                        // header checksum
        val blockChecksum = flg and 0x10 != 0
        val out = ByteSink()
        while (i + 4 <= data.size) {
            val bs = Bytes.get32(data, i)
            i += 4
            if (bs == 0L) break
            val size: Int
            val compressed: Boolean
            if (bs and 0x80000000L != 0L) {
                size = (bs and 0x7FFFFFFFL).toInt()
                compressed = false
            } else {
                size = bs.toInt()
                compressed = true
            }
            val end = min(i + size, data.size)
            if (compressed) {
                out.write(decompressBlock(data.copyOfRange(i, end)))
            } else {
                out.write(data, i, end - i)
            }
            i = end
            if (blockChecksum) i += 4
        }
        return out.toByteArray()
    }

    private fun writeLegacyBlock(out: ByteSink, src: ByteArray, off: Int, len: Int) {
        val chunk = if (off == 0 && len == src.size) src else src.copyOfRange(off, off + len)
        val block = compressBlock(chunk)
        writeLE32(out, block.size.toLong())
        out.write(block)
    }

    private fun writeFrameBlock(out: ByteSink, src: ByteArray, off: Int, len: Int) {
        val chunk = if (off == 0 && len == src.size) src else src.copyOfRange(off, off + len)
        val block = compressBlock(chunk)
        if (block.size >= chunk.size) {
            writeLE32(out, 0x80000000L or chunk.size.toLong())
            out.write(chunk)
        } else {
            writeLE32(out, block.size.toLong())
            out.write(block)
        }
    }

    private fun writeFrameHeader(out: ByteSink) {
        writeLE32(out, FRAME_MAGIC)
        out.writeByte((1 shl 6) or (1 shl 5)) // version 01 + block independent
        out.writeByte(6 shl 4)                // 1 MB max block size
        out.writeByte(0)                      // header checksum (ignored by readers)
    }

    private fun writeLE32(out: ByteSink, v: Long) {
        out.writeByte((v and 0xFF).toInt())
        out.writeByte(((v ushr 8) and 0xFF).toInt())
        out.writeByte(((v ushr 16) and 0xFF).toInt())
        out.writeByte(((v ushr 24) and 0xFF).toInt())
    }
}
