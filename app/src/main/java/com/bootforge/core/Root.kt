package com.bootforge.core

import java.io.File

/**
 * Root helpers: everything here is best effort and only used when the user
 * explicitly asks for flashing / backup.
 */
object Root {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        fun text(): String = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun exec(cmd: String, asRoot: Boolean = true): Result {
        return try {
            val command = if (asRoot) arrayOf("su", "-c", cmd) else arrayOf("sh", "-c", cmd)
            val process = Runtime.getRuntime().exec(command)
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            Result(code, out, err)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "执行失败")
        }
    }

    fun available(): Boolean {
        val r = exec("id")
        return r.code == 0 && r.out.contains("uid=0")
    }

    fun partitionPath(name: String): String {
        val candidates = listOf(
            "/dev/block/by-name/$name",
            "/dev/block/bootdevice/by-name/$name",
            "/dev/block/platform/*/by-name/$name"
        )
        for (c in candidates) if (!c.contains("*") && File(c).exists()) return c
        val found = exec("ls /dev/block/by-name/$name /dev/block/bootdevice/by-name/$name 2>/dev/null")
        val first = found.out.lineSequence().firstOrNull { it.startsWith("/") }
        return first ?: candidates[0]
    }

    fun knownPartitions(): List<String> {
        val r = exec("ls /dev/block/by-name 2>/dev/null; ls /dev/block/bootdevice/by-name 2>/dev/null")
        return r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    fun flash(imagePath: String, partition: String): Result {
        val path = partitionPath(partition)
        return exec("dd if=\"$imagePath\" of=\"$path\" bs=1048576 && sync")
    }

    fun backup(partition: String, destPath: String): Result {
        val path = partitionPath(partition)
        return exec("dd if=\"$path\" of=\"$destPath\" bs=1048576")
    }
}
