package com.bootforge.core

/**
 * SHA-1, implemented in pure Kotlin so the same code runs on the JVM (Android)
 * and on Kotlin/Native (where java.security is unavailable).
 */
object Sha1 {

    fun digest(data: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = -0x10325477   // 0xEFCDAB89
        var h2 = -0x67452302   // 0x98BADCFE
        var h3 = 0x10325476
        var h4 = -0x3c2d1e10   // 0xC3D2E1F0

        val bitLen = data.size.toLong() * 8
        var full = data.size + 1
        while (full % 64 != 56) full++
        val msg = ByteArray(full + 8)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        for (i in 0..7) msg[full + i] = ((bitLen ushr (8 * (7 - i))) and 0xFF).toByte()

        val w = IntArray(80)
        var off = 0
        while (off < msg.size) {
            for (i in 0..15) {
                val b = off + i * 4
                w[i] = (msg[b].toInt() and 0xFF shl 24) or
                    (msg[b + 1].toInt() and 0xFF shl 16) or
                    (msg[b + 2].toInt() and 0xFF shl 8) or
                    (msg[b + 3].toInt() and 0xFF)
            }
            for (i in 16..79) {
                val v = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (v shl 1) or (v ushr 31)
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (i in 0..79) {
                val f: Int
                val k: Int
                when (i / 20) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    1 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    2 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = -0x70e44324 // 0x8F1BBCDC
                    }
                    else -> {
                        f = b xor c xor d
                        k = -0x359d3e2a // 0xCA62C1D6
                    }
                }
                val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                e = d
                d = c
                c = (b shl 30) or (b ushr 2)
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            off += 64
        }

        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0..4) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
        return sb.toString()
    }
}
