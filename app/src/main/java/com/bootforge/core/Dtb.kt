package com.bootforge.core

/**
 * Very small flattened device tree reader (enough for the "分析" screen:
 * how many dtb/dtbo blobs, their size, model and compatible strings).
 */
object Dtb {

    private const val FDT_MAGIC = 0xd00dfeedL
    private const val DT_TABLE_MAGIC = 0xd7b7ab1eL

    private const val FDT_BEGIN_NODE = 1
    private const val FDT_END_NODE = 2
    private const val FDT_PROP = 3
    private const val FDT_NOP = 4
    private const val FDT_END = 9

    data class Info(
        val index: Int,
        val offset: Int,
        val size: Int,
        val model: String,
        val compatible: String,
        val nodes: Int
    )

    fun parse(data: ByteArray): List<Info> {
        if (data.isEmpty()) return emptyList()
        if (be32(data, 0) == DT_TABLE_MAGIC) return parseTable(data)
        val out = ArrayList<Info>()
        var off = 0
        var index = 0
        while (off + 8 <= data.size) {
            if (be32(data, off) != FDT_MAGIC) break
            val size = be32(data, off + 4).toInt()
            if (size <= 0 || off + size > data.size) break
            out.add(readFdt(data, off, size, index))
            index++
            off += size
        }
        return out
    }

    private fun parseTable(data: ByteArray): List<Info> {
        val out = ArrayList<Info>()
        val headerSize = be32(data, 8).toInt()
        val entrySize = be32(data, 12).toInt()
        val count = be32(data, 16).toInt()
        val entriesOffset = be32(data, 20).toInt()
        for (i in 0 until count) {
            val base = entriesOffset + i * entrySize
            if (base + 8 > data.size) break
            val dtSize = be32(data, base).toInt()
            val dtOffset = be32(data, base + 4).toInt()
            if (dtOffset + dtSize > data.size || dtSize <= 0) continue
            if (be32(data, dtOffset) != FDT_MAGIC) continue
            out.add(readFdt(data, dtOffset, dtSize, i))
        }
        return out
    }

    private fun readFdt(data: ByteArray, off: Int, size: Int, index: Int): Info {
        var model = ""
        var compatible = ""
        var nodes = 0
        runCatching {
            val structOff = off + be32(data, off + 8).toInt()
            val stringsOff = off + be32(data, off + 12).toInt()
            var p = structOff
            var depth = 0
            while (p + 4 <= off + size) {
                val token = be32(data, p).toInt()
                p += 4
                when (token) {
                    FDT_BEGIN_NODE -> {
                        var end = p
                        while (end < off + size && data[end] != 0.toByte()) end++
                        val name = String(data, p, end - p, Charsets.US_ASCII)
                        p = end + 1
                        p += ((4 - (p and 3)) and 3)
                        depth++
                        if (depth == 1) nodes++
                        if (depth == 1 && name.isEmpty()) {
                            // root, keep reading properties
                        }
                    }
                    FDT_END_NODE -> {
                        depth--
                        if (depth <= 0) break
                    }
                    FDT_PROP -> {
                        val len = be32(data, p).toInt()
                        val nameOff = be32(data, p + 4).toInt()
                        p += 8
                        val propName = cstring(data, stringsOff + nameOff)
                        val value = data.copyOfRange(p, p + len)
                        if (depth == 1) {
                            when (propName) {
                                "model" -> model = String(value).trimEnd('\u0000')
                                "compatible" -> compatible = value.split('\u0000')
                                    .filter { it.isNotEmpty() }.joinToString(", ")
                            }
                        }
                        p += len
                        p += ((4 - (p and 3)) and 3)
                    }
                    FDT_NOP -> Unit
                    FDT_END -> break
                    else -> break
                }
            }
        }
        return Info(index, off, size, model, compatible, nodes)
    }

    private fun cstring(data: ByteArray, off: Int): String {
        var end = off
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, off, end - off, Charsets.US_ASCII)
    }

    private fun be32(data: ByteArray, off: Int): Long {
        if (off + 4 > data.size) return 0
        return ((data[off].toLong() and 0xFF) shl 24) or
            ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or
            (data[off + 3].toLong() and 0xFF)
    }
}
