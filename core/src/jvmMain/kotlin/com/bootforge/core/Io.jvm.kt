package com.bootforge.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths

actual class FileReader actual constructor(path: String) {

    private val raf: RandomAccessFile? = runCatching { RandomAccessFile(path, "r") }.getOrNull()

    actual fun size(): Long = runCatching { raf?.length() ?: -1L }.getOrDefault(-1L)

    actual fun read(offset: Long, len: Int): ByteArray {
        val f = raf ?: return ByteArray(0)
        return try {
            f.seek(offset)
            val buf = ByteArray(len)
            val n = f.read(buf, 0, len)
            if (n <= 0) ByteArray(0) else if (n == len) buf else buf.copyOf(n)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    actual fun close() = runCatching { raf?.close() }.getOrDefault(Unit)
}

actual class FileWriter actual constructor(path: String) {

    private val out = runCatching { java.io.FileOutputStream(path) }.getOrNull()

    actual fun write(data: ByteArray) {
        runCatching { out?.write(data) }
    }

    actual fun close() = runCatching { out?.close() }.getOrDefault(Unit)
}

actual fun mkdirs(path: String): Boolean = runCatching { File(path).mkdirs() }.getOrDefault(false)
actual fun fileExists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
actual fun fileDelete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)
actual fun fileSize(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)

actual fun createSymlink(linkPath: String, target: String): Boolean = runCatching {
    val link = Paths.get(linkPath)
    runCatching { Files.deleteIfExists(link) }
    Files.createSymbolicLink(link, Paths.get(target))
    true
}.getOrDefault(false)

actual fun stdout(text: String) = print(text)
actual fun stderr(text: String) = System.err.print(text)
