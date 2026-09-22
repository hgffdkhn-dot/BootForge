package com.bootforge.core

/**
 * Pure Kotlin GZIP (DEFLATE) compress/decompress, so the CLI does not depend on
 * zlib and the JVM build does not depend on java.util.zip.
 *
 * Compressor  : LZ77 + static (fixed) Huffman, stored blocks for tiny payloads.
 * Decompressor: stored / fixed Huffman / dynamic Huffman blocks.
 */
object Gzip {

    private val LEN_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    )
    private val LEN_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
    )
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    private val DYN_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    // ---- fixed Huffman tables (built once, canonical codes bit-reversed) ----
    private val LIT_CODE: IntArray
    private val LIT_LEN: IntArray
    private val DIST_CODE: IntArray
    private val DIST_LEN: IntArray
    private val FIXED_LIT: Huffman
    private val FIXED_DIST: Huffman

    init {
        val litLengths = IntArray(288)
        for (i in 0..143) litLengths[i] = 8
        for (i in 144..255) litLengths[i] = 9
        for (i in 256..279) litLengths[i] = 7
        for (i in 280..287) litLengths[i] = 8
        val (lc, ll) = canonical(litLengths)
        LIT_CODE = lc
        LIT_LEN = ll
        val distLengths = IntArray(32) { 5 }
        val (dc, dl) = canonical(distLengths)
        DIST_CODE = dc
        DIST_LEN = dl
        FIXED_LIT = Huffman(litLengths)
        FIXED_DIST = Huffman(distLengths)
    }

    private fun canonical(lengths: IntArray): Pair<IntArray, IntArray> {
        val counts = IntArray(16)
        for (l in lengths) if (l > 0) counts[l]++
        val next = IntArray(16)
        var code = 0
        for (b in 1..15) {
            code = (code + counts[b - 1]) shl 1
            next[b] = code
        }
        val codes = IntArray(lengths.size)
        val lens = IntArray(lengths.size)
        for (s in lengths.indices) {
            val l = lengths[s]
            if (l > 0) {
                val c = next[l]++
                var r = 0
                for (i in 0 until l) r = (r shl 1) or ((c ushr i) and 1)
                codes[s] = r
                lens[s] = l
            }
        }
        return Pair(codes, lens)
    }

    private class Huffman(lengths: IntArray) {
        private val counts = IntArray(16)
        private val symbols: IntArray

        init {
            var total = 0
            for (l in lengths) {
                if (l > 0) {
                    counts[l]++
                    total++
                }
            }
            symbols = IntArray(total)
            val offsets = IntArray(16)
            for (l in 1..15) offsets[l] = offsets[l - 1] + counts[l - 1]
            val cursor = offsets.copyOf()
            for (s in lengths.indices) {
                val l = lengths[s]
                if (l > 0) symbols[cursor[l]++] = s
            }
        }

        fun decode(br: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or br.bit()
                val count = counts[len]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw IllegalStateException("损坏的 Huffman 编码")
        }
    }

    // ------------------------------------------------------------ deflate

    /** Returns a raw DEFLATE stream (no zlib/gzip header). */
    fun deflate(data: ByteArray): ByteArray {
        val n = data.size
        if (n < 64) {
            val w = BitWriter(64)
            w.bits(1, 1)   // BFINAL
            w.bits(0, 2)   // stored
            w.align()
            w.raw(u16le(n))
            w.raw(u16le(n.inv() and 0xFFFF))
            w.raw(data)
            return w.toByteArray()
        }

        val out = BitWriter(n / 4 + 64)
        out.bits(1, 1)  // BFINAL
        out.bits(1, 2)  // BTYPE = fixed Huffman

        val hashBits = 15
        val hSize = 1 shl hashBits
        val mask = hSize - 1
        val table = IntArray(hSize) { -1 }
        var anchor = 0
        var i = 0
        val limit = if (n - 12 < 0) 0 else n - 12

        while (i < limit) {
            if (i + 4 > n) break
            val key = hash4(data, i) and mask
            val cand = table[key]
            table[key] = i
            if (cand < 0 || i - cand > 32768 || !match4(data, cand, i)) {
                i++
                continue
            }
            var mlen = 4
            while (mlen < 258 && i + mlen < n && data[cand + mlen] == data[i + mlen]) mlen++
            for (p in anchor until i) {
                out.bits(LIT_CODE[data[p].toInt() and 0xFF], LIT_LEN[data[p].toInt() and 0xFF])
            }
            val lc = lenCode(mlen)
            out.bits(LIT_CODE[lc.first], LIT_LEN[lc.first])
            out.bits(lc.second, lc.third)
            val dc = distCode(i - cand)
            out.bits(DIST_CODE[dc.first], DIST_LEN[dc.first])
            out.bits(dc.second, dc.third)
            i += mlen
            anchor = i
        }
        for (p in anchor until n) {
            out.bits(LIT_CODE[data[p].toInt() and 0xFF], LIT_LEN[data[p].toInt() and 0xFF])
        }
        out.bits(LIT_CODE[256], LIT_LEN[256])
        out.align()
        return out.toByteArray()
    }

    private fun hash4(d: ByteArray, i: Int): Int {
        val a = d[i].toInt() and 0xFF
        val b = d[i + 1].toInt() and 0xFF
        val c = d[i + 2].toInt() and 0xFF
        val e = d[i + 3].toInt() and 0xFF
        return ((a shl 8 or b) * 2654435761) xor ((c shl 4) or e)
    }

    private fun match4(d: ByteArray, a: Int, b: Int): Boolean =
        d[a] == d[b] && d[a + 1] == d[b + 1] && d[a + 2] == d[b + 2] && d[a + 3] == d[b + 3]

    private fun lenCode(length: Int): Triple<Int, Int, Int> {
        for (i in 28 downTo 0) {
            if (length >= LEN_BASE[i]) {
                val idx = 257 + i
                if (idx > 285) break
                return Triple(idx, length - LEN_BASE[i], LEN_EXTRA[i])
            }
        }
        return Triple(257, 0, 0)
    }

    private fun distCode(distance: Int): Triple<Int, Int, Int> {
        for (i in 29 downTo 0) {
            if (distance >= DIST_BASE[i]) return Triple(i, distance - DIST_BASE[i], DIST_EXTRA[i])
        }
        return Triple(0, 0, 0)
    }

    // ------------------------------------------------------------ inflate

    fun inflate(data: ByteArray): ByteArray {
        val br = BitReader(data)
        val out = ByteSink(data.size * 3 + 64)
        while (true) {
            val final = br.bit()
            val btype = br.bits(2)
            when (btype) {
                0 -> inflateStored(br, out)
                1 -> inflateBlock(br, out, FIXED_LIT, FIXED_DIST)
                2 -> {
                    val tables = readDynamic(br)
                    inflateBlock(br, out, tables.first, tables.second)
                }
                else -> throw IllegalStateException("无效的 deflate 块类型 $btype")
            }
            if (final != 0) break
        }
        return out.toByteArray()
    }

    private fun inflateStored(br: BitReader, out: ByteSink) {
        br.align()
        val pos = br.bytePos
        val src = br.data
        if (pos + 4 > src.size) return
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        val start = pos + 4
        val end = minOf(start + len, src.size)
        out.write(src, start, end - start)
        br.skipBytes(4 + len)
    }

    private fun inflateBlock(br: BitReader, out: ByteSink, litH: Huffman, distH: Huffman) {
        val buf = out.buf
        while (true) {
            val sym = litH.decode(br)
            if (sym == 256) return
            if (sym < 256) {
                out.writeByte(sym)
            } else {
                val idx = sym - 257
                if (idx < 0 || idx >= LEN_BASE.size) throw IllegalStateException("无效长度码 $sym")
                val length = LEN_BASE[idx] + br.bits(LEN_EXTRA[idx])
                val ds = distH.decode(br)
                if (ds < 0 || ds >= DIST_BASE.size) throw IllegalStateException("无效距离码 $ds")
                val distance = DIST_BASE[ds] + br.bits(DIST_EXTRA[ds])
                val start = out.size - distance
                if (start < 0) throw IllegalStateException("距离越界: $distance > ${out.size}")
                // 逐字节复制以正确处理重叠的匹配
                val target = out.size + length
                out.growTo(target)
                val arr = out.buf
                var w = out.size
                var r = start
                while (w < target) {
                    arr[w++] = arr[r++]
                }
                out.size = target
            }
        }
    }

    private fun readDynamic(br: BitReader): Pair<Huffman, Huffman> {
        val hlit = br.bits(5) + 257
        val hdist = br.bits(5) + 1
        val hclen = br.bits(4) + 4
        val cl = IntArray(19)
        for (i in 0 until hclen) cl[DYN_ORDER[i]] = br.bits(3)
        val clH = Huffman(cl)
        val lengths = IntArray(hlit + hdist)
        var idx = 0
        while (idx < lengths.size) {
            val sym = clH.decode(br)
            when {
                sym < 16 -> lengths[idx++] = sym
                sym == 16 -> {
                    val prev = lengths[idx - 1]
                    val rep = 3 + br.bits(2)
                    repeat(rep) {
                        if (idx < lengths.size) lengths[idx++] = prev
                    }
                }
                sym == 17 -> {
                    val rep = 3 + br.bits(3)
                    repeat(rep) {
                        if (idx < lengths.size) lengths[idx++] = 0
                    }
                }
                else -> {
                    val rep = 11 + br.bits(7)
                    repeat(rep) {
                        if (idx < lengths.size) lengths[idx++] = 0
                    }
                }
            }
        }
        return Pair(Huffman(lengths.copyOfRange(0, hlit)), Huffman(lengths.copyOfRange(hlit, hlit + hdist)))
    }

    // ------------------------------------------------------------ gzip

    fun compress(data: ByteArray): ByteArray {
        val raw = deflate(data)
        val out = ByteSink(raw.size + 18)
        out.writeByte(0x1F)
        out.writeByte(0x8B)
        out.writeByte(8)
        out.writeByte(0)
        out.writeByte(0)   // mtime
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(255) // XFL / OS
        out.write(raw)
        out.write(le32(crc32(data)))
        out.write(le32(data.size))
        return out.toByteArray()
    }

    fun isGzip(data: ByteArray): Boolean =
        data.size >= 2 && (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B

    /** Skips the gzip header and returns the offset of the deflate payload. */
    private fun headerEnd(data: ByteArray): Int {
        var pos = 10
        val flg = data[3].toInt() and 0xFF
        if (flg and 0x04 != 0) {
            val xlen = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2 + xlen
        }
        if (flg and 0x08 != 0) {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos++
        }
        if (flg and 0x10 != 0) {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos++
        }
        if (flg and 0x02 != 0) pos += 2
        return pos
    }

    fun decompress(data: ByteArray): ByteArray {
        val start = headerEnd(data)
        val end = (data.size - 8).coerceAtLeast(start)
        return inflate(data.copyOfRange(start, end))
    }

    // ------------------------------------------------------------ crc32

    private val CRC_TABLE = IntArray(256).also { table ->
        for (n in 0..255) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            table[n] = c
        }
    }

    fun crc32(data: ByteArray): Int {
        var c = 0.inv()
        for (b in data) {
            c = CRC_TABLE[(c xor (b.toInt() and 0xFF)) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }

    private fun u16le(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )
}
