package com.bootforge.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin LZ4: block codec + the two container formats found in Android images.
 *  - legacy frame : magic 02 21 4C 18 (used by mkbootimg / magiskboot for ramdisks)
 *  - standard LZ4 frame : magic 04 22 4D 18
 *
 * Everything can also run in streaming mode so that a 100 MB ramdisk never has
 * to be materialised in memory.
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

    /** Growable byte sink, much cheaper than ByteArrayOutputStream.write(Int). */
    private class Sink(capacity: Int) {
        @JvmField
        var buf = ByteArray(max(64, capacity))
        @JvmField
        var len = 0

        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var cap = buf.size
            while (cap < len + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun b(v: Int) {
            ensure(1)
            buf[len++] = v.toByte()
        }

        fun bytes(src: ByteArray, off: Int, n: Int) {
            ensure(n)
            src.copyInto(buf, len, off, off + n)
            len += n
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }

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
                // 不重叠（System.arraycopy 也能正确处理重叠，这里只是为了少一次判断）
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
        val out = Sink(n / 2 + 64)
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
            out.b((min(litLen, 15) shl 4) or min(matchCode, 15))
            if (litLen >= 15) writeLength(out, litLen - 15)
            if (litLen > 0) out.bytes(src, anchor, litLen)
            val dist = i - cand
            out.b(dist and 0xFF)
            out.b((dist ushr 8) and 0xFF)
            if (matchCode >= 15) writeLength(out, matchCode - 15)
            i += mlen
            anchor = i
        }
        val litLen = n - anchor
        out.b(min(litLen, 15) shl 4)
        if (litLen >= 15) writeLength(out, litLen - 15)
        if (litLen > 0) out.bytes(src, anchor, litLen)
        return out.toByteArray()
    }

    private fun writeLength(out: Sink, value: Int) {
        var v = value
        while (v >= 255) {
            out.b(255)
            v -= 255
        }
        out.b(v)
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

    // ------------------------------------------------ in-memory containers

    fun compressLegacy(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
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
        val out = ByteArrayOutputStream()
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
        val out = ByteArrayOutputStream()
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
        val out = ByteArrayOutputStream()
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

    // ------------------------------------------------------- streaming API

    /** Compresses [input] as legacy-frame blocks straight into [out]. */
    fun compressLegacyStream(input: InputStream, out: OutputStream) {
        writeLE32(out, LEGACY_MAGIC)
        val buf = ByteArray(BLOCK)
        while (true) {
            val n = readChunk(input, buf)
            if (n <= 0) break
            writeLegacyBlock(out, buf, 0, n)
        }
    }

    /** Compresses [input] as a standard LZ4 frame straight into [out]. */
    fun compressFrameStream(input: InputStream, out: OutputStream) {
        writeFrameHeader(out)
        val buf = ByteArray(BLOCK)
        while (true) {
            val n = readChunk(input, buf)
            if (n <= 0) break
            writeFrameBlock(out, buf, 0, n)
        }
        writeLE32(out, 0)
    }

    private fun writeLegacyBlock(out: OutputStream, src: ByteArray, off: Int, len: Int) {
        val block = compressBlock(if (off == 0 && len == src.size) src else src.copyOfRange(off, off + len))
        writeLE32(out, block.size.toLong())
        out.write(block)
    }

    private fun writeFrameBlock(out: OutputStream, src: ByteArray, off: Int, len: Int) {
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

    private fun writeFrameHeader(out: OutputStream) {
        writeLE32(out, FRAME_MAGIC)
        out.write((1 shl 6) or (1 shl 5)) // version 01 + block independent
        out.write(6 shl 4)                // 1 MB max block size
        out.write(0)                      // header checksum (ignored by readers)
    }

    private fun readChunk(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    /** Wraps [input] in a legacy-frame decompressing stream. */
    fun legacyInputStream(input: InputStream): InputStream = object : InputStream() {
        private val src = input
        private var cur = ByteArray(0)
        private var pos = 0
        private var started = false
        private var done = false

        private fun fill(): Boolean {
            if (pos < cur.size) return true
            if (done) return false
            if (!started) {
                started = true
                val magic = ByteArray(4)
                if (readFully(src, magic) < 4) {
                    done = true
                    return false
                }
            }
            val head = ByteArray(4)
            if (readFully(src, head) < 4) {
                done = true
                return false
            }
            val raw = Bytes.get32(head, 0)
            if (raw == 0L) {
                done = true
                return false
            }
            if (raw and 0x80000000L != 0L) {
                val size = (raw and 0x7FFFFFFFL).toInt()
                val buf = ByteArray(size)
                if (readFully(src, buf) < size) {
                    done = true
                    return false
                }
                cur = buf
            } else {
                val size = raw.toInt()
                val buf = ByteArray(size)
                if (readFully(src, buf) < size) {
                    done = true
                    return false
                }
                cur = decompressBlock(buf)
            }
            pos = 0
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return cur[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fill()) return -1
            val n = min(len, cur.size - pos)
            cur.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() = src.close()
    }

    /** Wraps [input] in a standard LZ4 frame decompressing stream. */
    fun frameInputStream(input: InputStream): InputStream = object : InputStream() {
        private val src = input
        private var cur = ByteArray(0)
        private var pos = 0
        private var blockChecksum = false
        private var started = false
        private var done = false

        private fun fill(): Boolean {
            if (pos < cur.size) return true
            if (done) return false
            if (!started) {
                started = true
                val head = ByteArray(7)
                if (readFully(src, head) < 7) {
                    done = true
                    return false
                }
                val flg = head[4].toInt() and 0xFF
                var skip = 1
                if (flg and 0x08 != 0) skip += 8
                if (flg and 0x01 != 0) skip += 4
                if (skip > 1) {
                    val rest = ByteArray(skip - 1)
                    readFully(src, rest)
                }
                blockChecksum = flg and 0x10 != 0
            }
            val head = ByteArray(4)
            if (readFully(src, head) < 4) {
                done = true
                return false
            }
            val bs = Bytes.get32(head, 0)
            if (bs == 0L) {
                done = true
                return false
            }
            val size: Int
            val compressed: Boolean
            if (bs and 0x80000000L != 0L) {
                size = (bs and 0x7FFFFFFFL).toInt()
                compressed = false
            } else {
                size = bs.toInt()
                compressed = true
            }
            val buf = ByteArray(size)
            if (readFully(src, buf) < size) {
                done = true
                return false
            }
            cur = if (compressed) decompressBlock(buf) else buf
            if (blockChecksum) {
                val crc = ByteArray(4)
                readFully(src, crc)
            }
            pos = 0
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return cur[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fill()) return -1
            val n = min(len, cur.size - pos)
            cur.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() = src.close()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun writeLE32(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

}
