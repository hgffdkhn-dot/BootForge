package com.bootforge.core

import java.io.ByteArrayOutputStream

/**
 * cpio "newc" (070701) archive reader / writer used by Android initramfs.
 */
class Cpio {

    companion object {
        const val HEADER_SIZE = 110
        const val TRAILER = "TRAILER!!!"
        val MAGICS = listOf("070701", "070702")

        const val MODE_DIR = 0x41ED       // 040755
        const val MODE_FILE_EXE = 0x81ED  // 100755
        const val MODE_FILE = 0x81A4      // 100644
        const val MODE_SYMLINK = 0xA1FF   // 120777

        fun parse(data: ByteArray): MutableList<Entry> {
            val entries = ArrayList<Entry>()
            var pos = 0
            while (pos + HEADER_SIZE <= data.size) {
                val magic = String(data, pos, 6, Charsets.US_ASCII)
                if (magic !in MAGICS) break
                var o = pos + 6
                val fields = IntArray(13)
                for (i in 0..12) {
                    fields[i] = parseHex(data, o)
                    o += 8
                }
                val ino = fields[0]
                val mode = fields[1]
                val uid = fields[2]
                val gid = fields[3]
                val nlink = fields[4]
                val mtime = fields[5]
                val filesize = fields[6].toLong()
                val devMajor = fields[7]
                val devMinor = fields[8]
                val rdevMajor = fields[9]
                val rdevMinor = fields[10]
                val namesize = fields[11]
                if (namesize <= 1) break
                val nameStart = pos + HEADER_SIZE
                if (nameStart + namesize > data.size) break
                val name = String(data, nameStart, namesize - 1, Charsets.UTF_8)
                if (name == TRAILER) break
                val dataStart = nameStart + namesize + pad4(HEADER_SIZE + namesize)
                val payload = copySafe(data, dataStart.toLong(), filesize)
                entries.add(
                    Entry(
                        name, mode, uid, gid, nlink, mtime.toLong(), payload,
                        ino, devMajor, devMinor, rdevMajor, rdevMinor
                    )
                )
                pos = (dataStart + filesize + pad4(filesize)).toInt()
            }
            return entries
        }

        fun build(entries: List<Entry>): ByteArray {
            val out = ByteArrayOutputStream()
            entries.forEachIndexed { index, e ->
                val name = (e.name + "\u0000").toByteArray(Charsets.UTF_8)
                val hdr = ByteArray(HEADER_SIZE)
                "070701".toByteArray(Charsets.US_ASCII).copyInto(hdr, 0)
                writeFields(
                    hdr,
                    intArrayOf(
                        if (e.ino != 0) e.ino else index + 1, e.mode, e.uid, e.gid, e.nlink,
                        e.mtime.toInt(), e.data.size, e.devMajor, e.devMinor,
                        e.rdevMajor, e.rdevMinor, name.size, 0
                    )
                )
                out.write(hdr)
                out.write(name)
                out.write(ByteArray(pad4(HEADER_SIZE + name.size)))
                out.write(e.data)
                out.write(ByteArray(pad4(e.data.size)))
            }
            val trailer = (TRAILER + "\u0000").toByteArray(Charsets.UTF_8)
            val th = ByteArray(HEADER_SIZE)
            "070701".toByteArray(Charsets.US_ASCII).copyInto(th, 0)
            writeFields(th, intArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, trailer.size, 0))
            out.write(th)
            out.write(trailer)
            out.write(ByteArray(pad4(HEADER_SIZE + trailer.size)))
            return out.toByteArray()
        }

        private fun writeFields(hdr: ByteArray, values: IntArray) {
            var o = 6
            for (v in values) {
                val s = (v.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
                s.toByteArray(Charsets.US_ASCII).copyInto(hdr, o)
                o += 8
            }
        }

        private fun parseHex(data: ByteArray, off: Int): Int {
            var v = 0L
            for (i in 0..7) {
                val c = data[off + i].toInt().toChar()
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> 0
                }
                v = (v shl 4) or d.toLong()
            }
            return v.toInt()
        }

        private fun pad4(size: Int) = (4 - (size and 3)) and 3
        private fun pad4(size: Long) = ((4 - (size and 3)) and 3).toInt()

        private fun copySafe(data: ByteArray, off: Long, size: Long): ByteArray {
            val s = size.toInt()
            if (s <= 0) return ByteArray(0)
            val o = off.toInt()
            if (o < 0 || o >= data.size) return ByteArray(0)
            val end = minOf(o + s, data.size)
            return data.copyOfRange(o, end)
        }
    }

    data class Entry(
        var name: String,
        var mode: Int,
        var uid: Int = 0,
        var gid: Int = 0,
        var nlink: Int = 1,
        var mtime: Long = 0,
        var data: ByteArray = ByteArray(0),
        var ino: Int = 0,
        var devMajor: Int = 0,
        var devMinor: Int = 0,
        var rdevMajor: Int = 0,
        var rdevMinor: Int = 0
    ) {
        val isDir: Boolean get() = (mode and 0xF000) == 0x4000
        val isSymlink: Boolean get() = (mode and 0xF000) == 0xA000
        val permissions: String get() = (mode and 0xFFF).toString(8).padStart(4, '0')
    }
}
