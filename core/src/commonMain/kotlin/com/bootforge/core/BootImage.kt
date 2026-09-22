package com.bootforge.core

import kotlin.math.min

/**
 * Android boot / vendor_boot image parser & builder.
 *
 * Offsets follow AOSP system/tools/mkbootimg/include/bootimg/bootimg.h
 *  - boot header v0 : 1632 bytes, v1 : 1648, v2 : 1660, v3 : 1580, v4 : 1584
 *  - boot header version field always lives at offset 0x28 (shared with the legacy dt_size field)
 *  - vendor boot header v3 : 2112 bytes, v4 : 2128
 *
 * Sections are addressed by offset+size and read lazily from disk, so a 90 MB
 * image never has to be materialised in memory.
 */
class BootImage {

    enum class Type { BOOT, VENDOR_BOOT }

    enum class Part(val fileName: String) {
        KERNEL("kernel"),
        RAMDISK("ramdisk"),
        SECOND("second.img"),
        DTBO("recovery_dtbo.img"),
        DTB("dtb.img"),
        SIGNATURE("boot_signature"),
        VENDOR_TABLE("vendor_ramdisk_table.bin"),
        BOOTCONFIG("bootconfig")
    }

    companion object {
        val BOOT_MAGIC = byteArrayOf(0x41, 0x4E, 0x44, 0x52, 0x4F, 0x49, 0x44, 0x21) // ANDROID!
        val VENDOR_MAGIC = "VNDRBOOT".toByteArray(Charsets.UTF_8)

        const val HDR_V0 = 1632
        const val HDR_V1 = 1648
        const val HDR_V2 = 1660
        const val HDR_V3 = 1580
        const val HDR_V4 = 1584
        const val VENDOR_HDR_V3 = 2112
        const val VENDOR_HDR_V4 = 2128
        const val TABLE_ENTRY_SIZE = 108
        const val SEEK_LIMIT = 131072

        /** Parses an in-memory image (used by tests and small files). */
        fun parse(data: ByteArray): BootImage = parse(ArraySource(data))

        fun parse(src: Source): BootImage {
            val (type, base) = findMagic(src)
            return if (type == Type.VENDOR_BOOT) parseVendor(src, base) else parseBoot(src, base)
        }

        private fun findMagic(src: Source): Pair<Type, Long> {
            val limit = min(src.size() - 8, SEEK_LIMIT.toLong())
            var i = 0L
            var buf = ByteArray(0)
            var bufOff = 0L
            while (i <= limit) {
                if (i < bufOff || i + 8 > bufOff + buf.size) {
                    bufOff = i
                    buf = src.read(i, 65536)
                    if (buf.isEmpty()) break
                }
                val rel = (i - bufOff).toInt()
                if (match(buf, rel, BOOT_MAGIC)) return Pair(Type.BOOT, i)
                if (match(buf, rel, VENDOR_MAGIC)) return Pair(Type.VENDOR_BOOT, i)
                i++
            }
            throw IllegalArgumentException("未找到 ANDROID! / VNDRBOOT 魔数，不是有效的启动镜像")
        }

        private fun match(data: ByteArray, off: Int, magic: ByteArray): Boolean {
            if (off < 0 || off + magic.size > data.size) return false
            for (i in magic.indices) if (data[off + i] != magic[i]) return false
            return true
        }

        private fun parseBoot(src: Source, base: Long): BootImage {
            val img = BootImage()
            img.source = src
            img.type = Type.BOOT
            img.magicOffset = base

            val hdr = src.read(base, 4096)
            if (!match(hdr, 0, BOOT_MAGIC)) throw IllegalStateException("镜像头损坏")

            var ver = Bytes.get32(hdr, 0x28).toInt()
            if (ver > 8) ver = 0   // legacy images store dt_size in the same slot
            img.headerVersion = ver

            if (ver >= 3) {
                img.pageSize = 4096
                img.osVersion = Bytes.get32(hdr, 0x10)
                img.headerSizeField = Bytes.get32(hdr, 0x14)
                img.cmdline = Bytes.cstr(hdr, 0x2C, 1536)
                var pos = base + img.pageSize
                img.set(Part.KERNEL, pos, Bytes.get32(hdr, 0x08))
                pos += Bytes.align(img.sizeOf(Part.KERNEL), img.pageSize)
                img.set(Part.RAMDISK, pos, Bytes.get32(hdr, 0x0C))
                pos += Bytes.align(img.sizeOf(Part.RAMDISK), img.pageSize)
                if (ver >= 4) img.set(Part.SIGNATURE, pos, Bytes.get32(hdr, 1580))
            } else {
                val ps = Bytes.get32(hdr, 0x24).toInt()
                img.pageSize = if (ps >= 512 && ps <= 65536 && (ps and (ps - 1)) == 0) ps else 2048
                img.kernelAddr = Bytes.get32(hdr, 0x0C)
                img.ramdiskAddr = Bytes.get32(hdr, 0x14)
                img.secondAddr = Bytes.get32(hdr, 0x1C)
                img.tagsAddr = Bytes.get32(hdr, 0x20)
                img.osVersion = Bytes.get32(hdr, 0x2C)
                img.boardName = Bytes.cstr(hdr, 0x30, 16)
                img.cmdline = Bytes.cstr(hdr, 0x40, 512) + Bytes.cstr(hdr, 0x260, 1024)
                var pos = base + img.pageSize.toLong()
                img.set(Part.KERNEL, pos, Bytes.get32(hdr, 0x08))
                pos += Bytes.align(img.sizeOf(Part.KERNEL), img.pageSize)
                img.set(Part.RAMDISK, pos, Bytes.get32(hdr, 0x10))
                pos += Bytes.align(img.sizeOf(Part.RAMDISK), img.pageSize)
                img.set(Part.SECOND, pos, Bytes.get32(hdr, 0x18))
                pos += Bytes.align(img.sizeOf(Part.SECOND), img.pageSize)
                if (ver >= 1) {
                    img.recoveryDtboOffset = Bytes.get64(hdr, 0x664)
                    img.headerSizeField = Bytes.get32(hdr, 0x66C)
                    img.set(Part.DTBO, pos, Bytes.get32(hdr, 0x660))
                    pos += Bytes.align(img.sizeOf(Part.DTBO), img.pageSize)
                }
                if (ver >= 2) {
                    img.dtbAddr = Bytes.get64(hdr, 0x674)
                    img.set(Part.DTB, pos, Bytes.get32(hdr, 0x670))
                }
            }
            return img
        }

        private fun parseVendor(src: Source, base: Long): BootImage {
            val img = BootImage()
            img.source = src
            img.type = Type.VENDOR_BOOT
            img.magicOffset = base

            val hdr = src.read(base, 4096)
            if (!match(hdr, 0, VENDOR_MAGIC)) throw IllegalStateException("vendor_boot 头损坏")

            img.headerVersion = Bytes.get32(hdr, 0x08).toInt()
            val ps = Bytes.get32(hdr, 0x0C).toInt()
            img.pageSize = if (ps >= 512 && ps <= 65536 && (ps and (ps - 1)) == 0) ps else 2048
            img.kernelAddr = Bytes.get32(hdr, 0x10)
            img.ramdiskAddr = Bytes.get32(hdr, 0x14)
            img.vendorRamdiskSize = Bytes.get32(hdr, 0x18)
            img.cmdline = Bytes.cstr(hdr, 0x1C, 2048)
            img.tagsAddr = Bytes.get32(hdr, 0x81C)
            img.boardName = Bytes.cstr(hdr, 0x820, 16)
            img.headerSizeField = Bytes.get32(hdr, 0x830)
            img.dtbAddr = Bytes.get64(hdr, 0x838)

            val hdrSize = if (img.headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
            var pos = base + Bytes.align(hdrSize.toLong(), img.pageSize)
            img.set(Part.RAMDISK, pos, img.vendorRamdiskSize)
            pos += Bytes.align(img.vendorRamdiskSize, img.pageSize)
            img.set(Part.DTB, pos, Bytes.get32(hdr, 0x834))
            pos += Bytes.align(img.sizeOf(Part.DTB), img.pageSize)

            if (img.headerVersion >= 4) {
                img.vendorRamdiskTableEntryNum = Bytes.get32(hdr, 2116).toInt()
                img.vendorRamdiskTableEntrySize = Bytes.get32(hdr, 2120).toInt()
                val tableSize = Bytes.get32(hdr, 2112)
                img.set(Part.VENDOR_TABLE, pos, tableSize)
                pos += Bytes.align(tableSize, img.pageSize)
                img.set(Part.BOOTCONFIG, pos, Bytes.get32(hdr, 2124))
            }
            return img
        }

        private fun splitVendorRamdisks(
            section: ByteArray,
            table: ByteArray,
            entrySize: Int
        ): List<VendorFragment> {
            val out = ArrayList<VendorFragment>()
            if (table.isEmpty()) {
                if (section.isNotEmpty()) out.add(VendorFragment("vendor_ramdisk", 1, section))
                return out
            }
            val es = if (entrySize > 0) entrySize else TABLE_ENTRY_SIZE
            var off = 0
            while (off + es <= table.size) {
                val size = Bytes.get32(table, off).toInt()
                val rel = Bytes.get32(table, off + 4).toInt()
                val type = Bytes.get32(table, off + 8).toInt()
                val name = Bytes.cstr(table, off + 12, 32)
                val board = IntArray(16)
                for (i in 0..15) board[i] = Bytes.get32(table, off + 44 + i * 4).toInt()
                val data = if (rel >= 0 && size > 0 && rel + size <= section.size) {
                    section.copyOfRange(rel, rel + size)
                } else ByteArray(0)
                out.add(VendorFragment(name.ifEmpty { "ramdisk_${out.size}" }, type, data, board))
                off += es
            }
            if (out.isEmpty() && section.isNotEmpty()) {
                out.add(VendorFragment("vendor_ramdisk", 1, section))
            }
            return out
        }
    }

    data class VendorFragment(
        var name: String,
        var type: Int,
        var data: ByteArray,
        var boardId: IntArray = IntArray(16)
    )

    var source: Source? = null
    var type: Type = Type.BOOT
    var headerVersion: Int = 0
    var pageSize: Int = 4096
    var magicOffset: Long = 0
    var headerSizeField: Long = 0

    // boot header (v0 - v2)
    var kernelAddr: Long = 0
    var ramdiskAddr: Long = 0
    var secondAddr: Long = 0
    var tagsAddr: Long = 0
    var osVersion: Long = 0
    var boardName: String = ""
    var cmdline: String = ""
    var recoveryDtboOffset: Long = 0
    var dtbAddr: Long = 0

    // vendor boot header
    var vendorRamdiskSize: Long = 0
    var vendorRamdiskTableEntryNum: Int = 0
    var vendorRamdiskTableEntrySize: Int = 0

    val fragments: ArrayList<VendorFragment> = ArrayList()

    private val offsets = HashMap<Part, Long>()
    private val sizes = HashMap<Part, Long>()
    private val overrides = HashMap<Part, ByteArray>()
    private val fileOverrides = HashMap<Part, String>()

    private fun set(part: Part, offset: Long, size: Long) {
        offsets[part] = offset
        sizes[part] = size
    }

    fun sizeOf(part: Part): Long {
        overrides[part]?.let { return it.size.toLong() }
        fileOverrides[part]?.let { return fileSize(it) }
        return sizes[part] ?: 0L
    }

    fun has(part: Part): Boolean = sizeOf(part) > 0L

    fun setPart(part: Part, data: ByteArray) {
        fileOverrides.remove(part)
        overrides[part] = data
    }

    /** Replaces a section with a file on disk (keeps big ramdisks out of the heap). */
    fun setFilePart(part: Part, path: String) {
        overrides.remove(part)
        fileOverrides[part] = path
    }

    fun setRamdisk(data: ByteArray) = setPart(Part.RAMDISK, data)
    fun setRamdiskFile(path: String) = setFilePart(Part.RAMDISK, path)

    /** Loads a section into memory. Only sensible for small sections. */
    fun readPart(part: Part): ByteArray? {
        overrides[part]?.let { return it }
        fileOverrides[part]?.let { path ->
            val r = FileReader(path)
            val data = r.read(0, r.size().toInt())
            r.close()
            return data
        }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return null
        return source?.read(offsets[part] ?: 0L, size.toInt())
    }

    fun readRamdisk(): ByteArray? = readPart(Part.RAMDISK)

    /** Reads only the first [n] bytes, used for compression format sniffing. */
    fun peekPart(part: Part, n: Int): ByteArray {
        overrides[part]?.let { return it.copyOf(minOf(n, it.size)) }
        fileOverrides[part]?.let { path ->
            val r = FileReader(path)
            val d = r.read(0, n)
            r.close()
            return d
        }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return ByteArray(0)
        return source?.read(offsets[part] ?: 0L, minOf(n, size.toInt())) ?: ByteArray(0)
    }

    /** Loads the vendor ramdisk fragments described by the ramdisk table. */
    fun loadFragments() {
        if (!isVendorBoot()) return
        fragments.clear()
        val section = readPart(Part.RAMDISK) ?: ByteArray(0)
        val table = readPart(Part.VENDOR_TABLE) ?: ByteArray(0)
        fragments.addAll(splitVendorRamdisks(section, table, vendorRamdiskTableEntrySize))
        if (fragments.isEmpty() && section.isNotEmpty()) {
            fragments.add(VendorFragment("vendor_ramdisk", 1, section))
        }
    }

    /** Writes every non-ramdisk section into [dir]. Returns the written paths. */
    fun extractParts(dir: String): List<String> {
        mkdirs(dir)
        val written = ArrayList<String>()
        for (part in Part.values()) {
            if (part == Part.RAMDISK) continue
            if (!has(part)) continue
            val path = joinPath(dir, part.fileName)
            val data = readPart(part) ?: continue
            val w = FileWriter(path)
            w.write(data)
            w.close()
            written.add(path)
        }
        return written
    }

    // ------------------------------------------------------------ packing

    /** Rebuilds the image and writes it to [outPath]. */
    fun packTo(outPath: String) {
        val w = FileWriter(outPath)
        val sink = ByteSink()
        when {
            isVendorBoot() -> packVendor(sink)
            headerVersion >= 3 -> packBootV3(sink)
            else -> packBootLegacy(sink)
        }
        w.write(sink.toByteArray())
        w.close()
    }

    private fun writePart(out: ByteSink, part: Part, ps: Int) {
        overrides[part]?.let { data ->
            out.write(data)
            pad(out, data.size.toLong(), ps)
            return
        }
        fileOverrides[part]?.let { path ->
            val r = FileReader(path)
            val size = r.size()
            var off = 0L
            val total = size.toInt()
            while (off < size) {
                val chunk = r.read(off, minOf(1 shl 20, total - off.toInt()))
                if (chunk.isEmpty()) break
                out.write(chunk)
                off += chunk.size
            }
            r.close()
            pad(out, size, ps)
            return
        }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return
        var off = offsets[part] ?: 0L
        var remaining = size
        while (remaining > 0) {
            val chunk = source?.read(off, minOf(1 shl 20, remaining.toInt())) ?: break
            if (chunk.isEmpty()) break
            out.write(chunk)
            off += chunk.size
            remaining -= chunk.size
        }
        pad(out, size, ps)
    }

    private fun pad(out: ByteSink, size: Long, ps: Int) {
        if (size <= 0L) return
        val rem = Bytes.align(size, ps) - size
        if (rem > 0) out.write(ByteArray(rem.toInt()))
    }

    private fun packBootLegacy(out: ByteSink) {
        if (headerVersion >= 1 && pageSize < HDR_V1) pageSize = 2048
        if (headerVersion >= 2 && pageSize < HDR_V2) pageSize = 2048
        val hdr = ByteArray(pageSize)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, sizeOf(Part.KERNEL))
        Bytes.put32(hdr, 0x0C, kernelAddr)
        Bytes.put32(hdr, 0x10, sizeOf(Part.RAMDISK))
        Bytes.put32(hdr, 0x14, ramdiskAddr)
        Bytes.put32(hdr, 0x18, sizeOf(Part.SECOND))
        Bytes.put32(hdr, 0x1C, secondAddr)
        Bytes.put32(hdr, 0x20, tagsAddr)
        Bytes.put32(hdr, 0x24, pageSize.toLong())
        Bytes.put32(hdr, 0x28, if (headerVersion >= 1) headerVersion.toLong() else 0L)
        Bytes.put32(hdr, 0x2C, osVersion)
        Bytes.putCstr(hdr, 0x30, boardName, 16)
        writeCmdlineSplit(hdr)
        val digest = computeId()
        val n = minOf(digest.size, 32)
        for (i in 0 until n) hdr[0x240 + i] = digest[i]
        if (headerVersion >= 1) {
            Bytes.put32(hdr, 0x660, sizeOf(Part.DTBO))
            Bytes.put64(hdr, 0x664, recoveryDtboOffset)
            Bytes.put32(hdr, 0x66C, (if (headerVersion >= 2) HDR_V2 else HDR_V1).toLong())
        }
        if (headerVersion >= 2) {
            Bytes.put32(hdr, 0x670, sizeOf(Part.DTB))
            Bytes.put64(hdr, 0x674, dtbAddr)
        }
        out.write(hdr)
        writePart(out, Part.KERNEL, pageSize)
        writePart(out, Part.RAMDISK, pageSize)
        writePart(out, Part.SECOND, pageSize)
        if (headerVersion >= 1) writePart(out, Part.DTBO, pageSize)
        if (headerVersion >= 2) writePart(out, Part.DTB, pageSize)
    }

    private fun packBootV3(out: ByteSink) {
        val ps = 4096
        val hdr = ByteArray(ps)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, sizeOf(Part.KERNEL))
        Bytes.put32(hdr, 0x0C, sizeOf(Part.RAMDISK))
        Bytes.put32(hdr, 0x10, osVersion)
        Bytes.put32(hdr, 0x14, (if (headerVersion >= 4) HDR_V4 else HDR_V3).toLong())
        Bytes.put32(hdr, 0x28, headerVersion.toLong())
        Bytes.putCstr(hdr, 0x2C, cmdline, 1536)
        if (headerVersion >= 4) Bytes.put32(hdr, 1580, sizeOf(Part.SIGNATURE))
        out.write(hdr)
        writePart(out, Part.KERNEL, ps)
        writePart(out, Part.RAMDISK, ps)
        if (headerVersion >= 4) writePart(out, Part.SIGNATURE, ps)
    }

    private fun packVendor(out: ByteSink) {
        val hdrSize = if (headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
        val ramdiskSection = concatFragments()
        val tableBlob = buildVendorTable(ramdiskSection)
        val hdr = ByteArray(Bytes.align(hdrSize.toLong(), pageSize).toInt())
        VENDOR_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, headerVersion.toLong())
        Bytes.put32(hdr, 0x0C, pageSize.toLong())
        Bytes.put32(hdr, 0x10, kernelAddr)
        Bytes.put32(hdr, 0x14, ramdiskAddr)
        Bytes.put32(hdr, 0x18, if (fragments.isEmpty()) sizeOf(Part.RAMDISK) else ramdiskSection.size.toLong())
        Bytes.putCstr(hdr, 0x1C, cmdline, 2048)
        Bytes.put32(hdr, 0x81C, tagsAddr)
        Bytes.putCstr(hdr, 0x820, boardName, 16)
        Bytes.put32(hdr, 0x830, hdrSize.toLong())
        Bytes.put32(hdr, 0x834, sizeOf(Part.DTB))
        Bytes.put64(hdr, 0x838, dtbAddr)
        if (headerVersion >= 4) {
            Bytes.put32(hdr, 2112, tableBlob.size.toLong())
            Bytes.put32(hdr, 2116, fragments.size.toLong())
            Bytes.put32(hdr, 2120, TABLE_ENTRY_SIZE.toLong())
            Bytes.put32(hdr, 2124, sizeOf(Part.BOOTCONFIG))
        }
        out.write(hdr)
        if (fragments.isEmpty()) {
            writePart(out, Part.RAMDISK, pageSize)
        } else {
            out.write(ramdiskSection)
            pad(out, ramdiskSection.size.toLong(), pageSize)
        }
        writePart(out, Part.DTB, pageSize)
        if (headerVersion >= 4) {
            out.write(tableBlob)
            pad(out, tableBlob.size.toLong(), pageSize)
            writePart(out, Part.BOOTCONFIG, pageSize)
        }
    }

    private fun concatFragments(): ByteArray {
        if (fragments.isEmpty()) return readPart(Part.RAMDISK) ?: ByteArray(0)
        val out = ByteSink()
        for (f in fragments) out.write(f.data)
        return out.toByteArray()
    }

    private fun buildVendorTable(section: ByteArray): ByteArray {
        if (headerVersion < 4 || fragments.isEmpty()) return ByteArray(0)
        val out = ByteSink()
        var rel = 0
        for (f in fragments) {
            val e = ByteArray(TABLE_ENTRY_SIZE)
            Bytes.put32(e, 0, f.data.size.toLong())
            Bytes.put32(e, 4, rel.toLong())
            Bytes.put32(e, 8, f.type.toLong())
            Bytes.putCstr(e, 12, f.name, 32)
            for (i in 0..15) Bytes.put32(e, 44 + i * 4, f.boardId[i].toLong())
            out.write(e)
            rel += f.data.size
        }
        return out.toByteArray()
    }

    private fun writeCmdlineSplit(hdr: ByteArray) {
        val b = cmdline.toByteArray(Charsets.UTF_8)
        val n = minOf(b.size, 512 + 1024 - 1)
        val first = minOf(n, 511)
        if (first > 0) b.copyInto(hdr, 0x40, 0, first)
        if (n > first) b.copyInto(hdr, 0x260, first, n)
    }

    /** Classic mkbootimg id: SHA-1 over the payload sections. */
    private fun computeId(): ByteArray {
        val md = Sha1Writer()
        for (part in arrayOf(Part.KERNEL, Part.RAMDISK, Part.SECOND, Part.DTBO, Part.DTB)) {
            overrides[part]?.let { md.write(it); continue }
            val size = sizes[part] ?: 0L
            if (size <= 0L) continue
            var off = offsets[part] ?: 0L
            var remaining = size
            while (remaining > 0) {
                val chunk = source?.read(off, minOf(1 shl 20, remaining.toInt())) ?: break
                if (chunk.isEmpty()) break
                md.write(chunk)
                off += chunk.size
                remaining -= chunk.size
            }
        }
        return md.digest()
    }

    fun isVendorBoot(): Boolean = type == Type.VENDOR_BOOT

    /** Total size the rebuilt image will occupy. */
    fun packedSize(): Long {
        val ps = if (headerVersion >= 3 && !isVendorBoot()) 4096 else pageSize
        var total = Bytes.align(
            (if (isVendorBoot()) {
                if (headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
            } else if (headerVersion >= 3) 4096 else pageSize).toLong(),
            ps
        )
        if (isVendorBoot()) {
            total += Bytes.align(sizeOf(Part.RAMDISK), ps)
            total += Bytes.align(sizeOf(Part.DTB), ps)
            total += Bytes.align(sizeOf(Part.VENDOR_TABLE), ps)
            total += Bytes.align(sizeOf(Part.BOOTCONFIG), ps)
        } else if (headerVersion >= 3) {
            total += Bytes.align(sizeOf(Part.KERNEL), ps)
            total += Bytes.align(sizeOf(Part.RAMDISK), ps)
            total += Bytes.align(sizeOf(Part.SIGNATURE), ps)
        } else {
            total += Bytes.align(sizeOf(Part.KERNEL), ps)
            total += Bytes.align(sizeOf(Part.RAMDISK), ps)
            total += Bytes.align(sizeOf(Part.SECOND), ps)
            if (headerVersion >= 1) total += Bytes.align(sizeOf(Part.DTBO), ps)
            if (headerVersion >= 2) total += Bytes.align(sizeOf(Part.DTB), ps)
        }
        return total
    }

    fun close() {
        runCatching { source?.close() }
    }
}

/** Random/sequential byte source abstraction (file or memory). */
interface Source {
    fun size(): Long
    fun read(offset: Long, len: Int): ByteArray
    fun close()
}

/** In-memory [Source]. */
class ArraySource(private val data: ByteArray) : Source {
    override fun size(): Long = data.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray {
        val o = offset.toInt()
        if (o < 0 || o >= data.size || len <= 0) return ByteArray(0)
        val end = minOf(o + len, data.size)
        return data.copyOfRange(o, end)
    }
    override fun close() = Unit
}

/** File backed [Source]. */
class FileSource(private val path: String) : Source {
    private val reader = FileReader(path)
    private val len = reader.size()
    override fun size(): Long = len
    override fun read(offset: Long, len2: Int): ByteArray = reader.read(offset, len2)
    override fun close() = reader.close()
}

/** Streams bytes into SHA-1 without buffering the whole payload. */
private class Sha1Writer {
    private val sink = ByteSink()
    fun write(data: ByteArray) = sink.write(data)
    fun digest(): ByteArray = Sha1.digest(sink.toByteArray())
}
