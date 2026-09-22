package com.bootforge.core

/**
 * Ramdisk payload container formats.
 */
enum class Format(val label: String) {
    AUTO("跟随原镜像"),
    GZIP("gzip"),
    LZ4("lz4 (legacy)"),
    LZ4_FRAME("lz4 (frame)"),
    NONE("不压缩")
}

object Compress {

    fun detect(data: ByteArray): Format {
        if (data.size < 8) return Format.NONE
        return when {
            data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte() -> Format.GZIP
            data[0] == 0x02.toByte() && data[1] == 0x21.toByte() &&
                data[2] == 0x4C.toByte() && data[3] == 0x18.toByte() -> Format.LZ4
            data[0] == 0x04.toByte() && data[1] == 0x22.toByte() &&
                data[2] == 0x4D.toByte() && data[3] == 0x18.toByte() -> Format.LZ4_FRAME
            data[0] == 0xFD.toByte() && data[1] == 0x37.toByte() ->
                throw UnsupportedOperationException("xz 压缩暂不支持重新打包")
            data[0] == 0x42.toByte() && data[1] == 0x5A.toByte() && data[2] == 0x68.toByte() ->
                throw UnsupportedOperationException("bzip2 压缩暂不支持重新打包")
            isCpio(data) -> Format.NONE
            else -> Format.NONE
        }
    }

    fun isCpio(data: ByteArray): Boolean {
        if (data.size < 6) return false
        val head = String(data, 0, minOf(6, data.size), Charsets.UTF_8)
        return head == "070701" || head == "070702"
    }

    fun decompress(data: ByteArray, format: Format): ByteArray = when (format) {
        Format.GZIP -> Gzip.decompress(data)
        Format.LZ4 -> Lz4.decompressLegacy(data)
        Format.LZ4_FRAME -> Lz4.decompressFrame(data)
        Format.NONE -> data
        Format.AUTO -> decompress(data, detect(data))
    }

    fun compress(data: ByteArray, format: Format): ByteArray = when (format) {
        Format.GZIP -> Gzip.compress(data)
        Format.LZ4 -> Lz4.compressLegacy(data)
        Format.LZ4_FRAME -> Lz4.compressFrame(data)
        Format.NONE -> data
        Format.AUTO -> compress(data, Format.GZIP)
    }
}
