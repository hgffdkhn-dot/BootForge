package com.bootforge.core

/**
 * Platform glue. The JVM build (Android app) backs this with java.io, the
 * Kotlin/Native build (CLI) with POSIX calls.
 */

expect class FileReader(path: String) {
    /** Total file size, or -1 when the file cannot be opened. */
    fun size(): Long
    /** Reads up to [len] bytes at [offset]; shorter when the file ends first. */
    fun read(offset: Long, len: Int): ByteArray
    fun close()
}

expect class FileWriter(path: String) {
    fun write(data: ByteArray)
    fun close()
}

expect fun mkdirs(path: String): Boolean
expect fun fileExists(path: String): Boolean
expect fun fileDelete(path: String): Boolean
expect fun fileSize(path: String): Long
expect fun createSymlink(linkPath: String, target: String): Boolean
expect fun stdout(text: String)
expect fun stderr(text: String)
