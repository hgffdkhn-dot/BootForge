package com.bootforge.core

import kotlinx.cinterop.*
import platform.posix.*

actual class FileReader actual constructor(path: String) {

    private val fp = fopen(path, "rb")

    actual fun size(): Long {
        val f = fp ?: return -1L
        fseek(f, 0, SEEK_END)
        val n = ftell(f)
        fseek(f, 0, SEEK_SET)
        return n
    }

    actual fun read(offset: Long, len: Int): ByteArray {
        val f = fp ?: return ByteArray(0)
        if (len <= 0) return ByteArray(0)
        fseek(f, offset, SEEK_SET)
        val buf = ByteArray(len)
        val got = buf.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, len.toULong(), f)
        }
        val n = got.toLong().toInt()
        if (n <= 0) return ByteArray(0)
        return if (n == len) buf else buf.copyOf(n)
    }

    actual fun close() {
        fp?.let { fclose(it) }
    }
}

actual class FileWriter actual constructor(path: String) {

    private val fp = fopen(path, "wb")

    actual fun write(data: ByteArray) {
        val f = fp ?: return
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1u, data.size.toULong(), f)
        }
    }

    actual fun close() {
        fp?.let { fclose(it) }
    }
}

actual fun mkdirs(path: String): Boolean {
    // 逐级创建，等价于 mkdir -p
    val parts = path.split('/')
    var current = ""
    for (part in parts) {
        if (part.isEmpty()) {
            current += "/"
            continue
        }
        current = if (current.isEmpty() || current.endsWith("/")) current + part else "$current/$part"
        if (access(current, F_OK) != 0) {
            if (mkdir(current, 0b111101101u.convert()) != 0 && access(current, F_OK) != 0) return false
        }
    }
    return true
}

actual fun fileExists(path: String): Boolean = access(path, F_OK) == 0

actual fun fileDelete(path: String): Boolean = remove(path) == 0

actual fun fileSize(path: String): Long {
    val fp = fopen(path, "rb") ?: return 0L
    fseek(fp, 0, SEEK_END)
    val n = ftell(fp)
    fclose(fp)
    return n
}

actual fun createSymlink(linkPath: String, target: String): Boolean {
    remove(linkPath)
    return symlink(target, linkPath) == 0
}

actual fun nowSeconds(): Long {
    memScoped {
        val t = alloc<time_tVar>()
        time(t.ptr)
        return t.value
    }
}

actual fun stdout(text: String) = print(text)
actual fun stderr(text: String) {
    // fputs 到 stderr
    fputs(text, platform.posix.stderr)
}
