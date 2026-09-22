package com.bootforge.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.SequenceInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Ramdisk payload container formats.
 *
 * Every operation has a streaming counterpart so that large ramdisks can be
 * rewritten without ever holding them in memory.
 */
enum class Format(val label: String) {
    AUTO("跟随原镜像"),
    GZIP("gzip"),
    LZ4("lz4 (legacy)"),
    LZ4_FRAME("lz4 (frame)"),
    NONE("不压缩")
}

object Compress {

    private const val BUF = 1 shl 20

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

    /** Detects the format from the first bytes of a stream without consuming it. */
    fun detectStream(input: InputStream): Pair<PushbackInputStream, Format> {
        val push = PushbackInputStream(input, 8)
        val head = ByteArray(8)
        var read = 0
        while (read < 8) {
            val r = push.read(head, read, 8 - read)
            if (r < 0) break
            read += r
        }
        push.unread(head, 0, read)
        return Pair(push, detect(head.copyOf(read)))
    }

    fun isCpio(data: ByteArray): Boolean {
        if (data.size < 6) return false
        val head = String(data, 0, minOf(6, data.size), Charsets.US_ASCII)
        return head == "070701" || head == "070702"
    }

    fun decompress(data: ByteArray, format: Format): ByteArray = when (format) {
        Format.GZIP -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        Format.LZ4 -> Lz4.decompressLegacy(data)
        Format.LZ4_FRAME -> Lz4.decompressFrame(data)
        Format.NONE -> data
        Format.AUTO -> decompress(data, detect(data))
    }

    fun compress(data: ByteArray, format: Format): ByteArray = when (format) {
        Format.GZIP -> {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(data) }
            out.toByteArray()
        }
        Format.LZ4 -> Lz4.compressLegacy(data)
        Format.LZ4_FRAME -> Lz4.compressFrame(data)
        Format.NONE -> data
        Format.AUTO -> compress(data, Format.GZIP)
    }

    /** Streams a decompressed view of [input], never buffering the whole payload. */
    fun decompressStream(input: InputStream, format: Format): InputStream {
        if (format == Format.AUTO) {
            val (push, detected) = detectStream(input)
            return decompressStream(push, detected)
        }
        return when (format) {
            Format.GZIP -> GZIPInputStream(input)
            Format.LZ4 -> Lz4.legacyInputStream(input)
            Format.LZ4_FRAME -> Lz4.frameInputStream(input)
            Format.NONE -> input
            Format.AUTO -> input
        }
    }

    fun compressStream(input: InputStream, format: Format, out: OutputStream) {
        when (format) {
            Format.GZIP -> GZIPOutputStream(out).use { copy(input, it) }
            Format.LZ4 -> Lz4.compressLegacyStream(input, out)
            Format.LZ4_FRAME -> Lz4.compressFrameStream(input, out)
            Format.NONE -> copy(input, out)
            Format.AUTO -> GZIPOutputStream(out).use { copy(input, it) }
        }
    }

    fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(BUF)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) out.write(buf, 0, n)
        }
    }

    /** Concatenates two streams, used to append new cpio entries to an existing ramdisk. */
    fun concat(a: InputStream, b: InputStream): InputStream = SequenceInputStream(a, b)
}
