package com.bootforge.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * initramfs (cpio) container with the compression envelope applied by the bootloader.
 */
class Ramdisk(
    val entries: MutableList<Cpio.Entry>,
    var sourceFormat: Format = Format.GZIP
) {

    companion object {
        fun fromImage(bytes: ByteArray): Ramdisk {
            val format = Compress.detect(bytes)
            val raw = Compress.decompress(bytes, format)
            if (!Compress.isCpio(raw)) {
                throw IllegalStateException("ramdisk 解压后不是 cpio 归档（前 6 字节非 070701）")
            }
            return Ramdisk(Cpio.parse(raw), format)
        }
    }

    fun toImage(target: Format = Format.AUTO): ByteArray {
        val fmt = if (target == Format.AUTO) {
            if (sourceFormat == Format.AUTO) Format.GZIP else sourceFormat
        } else target
        return Compress.compress(Cpio.build(entries), fmt)
    }

    val size: Int get() = entries.size

    fun find(path: String): Cpio.Entry? = entries.firstOrNull { it.name == path }

    fun remove(path: String): Boolean {
        val idx = entries.indexOfFirst { it.name == path }
        if (idx < 0) return false
        entries.removeAt(idx)
        return true
    }

    /** Adds or replaces a file, creating missing parent directories. */
    fun add(path: String, data: ByteArray, mode: Int = Cpio.MODE_FILE_EXE, uid: Int = 0, gid: Int = 0) {
        val clean = path.trim().trimStart('/')
        ensureDirs(clean)
        val entry = Cpio.Entry(clean, mode, uid, gid, 1, System.currentTimeMillis() / 1000, data)
        val idx = entries.indexOfFirst { it.name == clean }
        if (idx >= 0) entries[idx] = entry else entries.add(insertIndex(clean), entry)
    }

    fun addDirectory(path: String, mode: Int = Cpio.MODE_DIR) {
        val clean = path.trim().trimStart('/')
        if (clean.isEmpty() || find(clean) != null) return
        entries.add(insertIndex(clean), Cpio.Entry(clean, mode, 0, 0, 2, System.currentTimeMillis() / 1000))
    }

    private fun ensureDirs(path: String) {
        val parts = path.split('/')
        if (parts.size <= 1) return
        var current = ""
        for (i in 0 until parts.size - 1) {
            current = if (current.isEmpty()) parts[i] else "$current/${parts[i]}"
            if (find(current) == null) addDirectory(current)
        }
    }

    private fun insertIndex(path: String): Int {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isEmpty()) return entries.size
        val sibling = entries.indexOfFirst { it.name.startsWith("$parent/") }
        if (sibling >= 0) return sibling
        val parentIndex = entries.indexOfFirst { it.name == parent }
        return if (parentIndex >= 0) parentIndex + 1 else entries.size
    }

    /** Writes every entry to a directory on disk (used by 解包). */
    fun extractTo(dir: File): Int {
        dir.mkdirs()
        var count = 0
        for (e in entries) {
            val target = File(dir, e.name)
            target.parentFile?.mkdirs()
            when {
                e.isDir -> target.mkdirs()
                e.isSymlink -> {
                    val link = String(e.data, Charsets.UTF_8).trim()
                    if (target.exists()) target.delete()
                    runCatching { Files.createSymbolicLink(Paths.get(target.path), Paths.get(link)) }
                }
                else -> target.writeBytes(e.data)
            }
            count++
        }
        return count
    }
}
