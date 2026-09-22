package com.bootforge.core

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

/**
 * Random access view over an image file.
 *
 * Boot images can be 64 MB and larger; reading them into a single ByteArray
 * exhausts the 256 MB default heap very quickly, so every section is streamed
 * from disk instead.
 */
interface ImageSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun copyTo(offset: Long, length: Long, out: OutputStream)
}

class FileImageSource(file: File) : ImageSource {

    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = length()

    @Synchronized
    private fun length(): Long {
        return runCatching { raf.length() }.getOrDefault(0L)
    }

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val available = (size - offset).coerceAtLeast(0L)
        val n = minOf(length.toLong(), available).toInt()
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        raf.seek(offset)
        raf.readFully(buf, 0, n)
        return buf
    }

    @Synchronized
    override fun copyTo(offset: Long, length: Long, out: OutputStream) {
        if (length <= 0L) return
        raf.seek(offset)
        val buf = ByteArray(1 shl 20)
        var remaining = length
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val got = raf.read(buf, 0, want)
            if (got <= 0) break
            out.write(buf, 0, got)
            remaining -= got
        }
    }

    @Synchronized
    override fun close() {
        runCatching { raf.close() }
    }
}

class ByteArrayImageSource(private val data: ByteArray, private val base: Long = 0) : ImageSource {
    override val size: Long get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val o = (offset - base).toInt()
        if (o < 0 || o >= data.size) return ByteArray(0)
        val end = minOf(o + length, data.size)
        return data.copyOfRange(o, end)
    }

    override fun copyTo(offset: Long, length: Long, out: OutputStream) {
        val o = (offset - base).toInt()
        if (o < 0 || o >= data.size) return
        val end = minOf(o + length.toInt(), data.size)
        out.write(data, o, end - o)
    }

    override fun close() = Unit
}

/** Feeds everything it receives into a MessageDigest without buffering it. */
private class DigestSink(private val md: MessageDigest) : OutputStream() {
    override fun write(b: Int) = md.update(b.toByte())
    override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
}

/**
 * Android boot / vendor_boot image parser & builder.
 *
 * Offsets follow AOSP system/tools/mkbootimg/include/bootimg/bootimg.h
 *  - boot header v0 : 1632 bytes, v1 : 1648, v2 : 1660, v3 : 1580, v4 : 1584
 *  - boot header version field always lives at offset 0x28 (shared with the legacy dt_size field)
 *  - vendor boot header v3 : 2112 bytes, v4 : 2128
 *
 * Only the header is kept in memory; every payload section stays on disk and is
 * streamed on demand.
 */
class BootImage {

    enum class Type { BOOT, VENDOR_BOOT }

    enum class Part { KERNEL, RAMDISK, SECOND, DTBO, DTB, SIGNATURE, VENDOR_TABLE, BOOTCONFIG }

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

        fun parse(data: ByteArray): BootImage = parse(ByteArrayImageSource(data))

        fun parse(src: ImageSource): BootImage {
            val (type, base) = findMagic(src)
            return if (type == Type.VENDOR_BOOT) parseVendor(src, base) else parseBoot(src, base)
        }

        private fun findMagic(src: ImageSource): Pair<Type, Long> {
            val limit = min(src.size - 8, SEEK_LIMIT.toLong())
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

        private fun parseBoot(src: ImageSource, base: Long): BootImage {
            val img = BootImage()
            img.source = src
            img.type = Type.BOOT
            img.magicOffset = base

            val hdr = src.read(base, 4096)
            if (!match(hdr, 0, BOOT_MAGIC)) error("镜像头损坏")

            var ver = Bytes.get32(hdr, 0x28).toInt()
            // legacy images store the dt_size in the same slot
            if (ver > 8) ver = 0
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
                img.pageSize = if (ps in 512..65536 && (ps and (ps - 1)) == 0) ps else 2048
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

        private fun parseVendor(src: ImageSource, base: Long): BootImage {
            val img = BootImage()
            img.source = src
            img.type = Type.VENDOR_BOOT
            img.magicOffset = base

            val hdr = src.read(base, 4096)
            if (!match(hdr, 0, VENDOR_MAGIC)) error("vendor_boot 头损坏")

            img.headerVersion = Bytes.get32(hdr, 0x08).toInt()
            val ps = Bytes.get32(hdr, 0x0C).toInt()
            img.pageSize = if (ps in 512..65536 && (ps and (ps - 1)) == 0) ps else 2048
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

    var source: ImageSource? = null
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

    private fun set(part: Part, offset: Long, size: Long) {
        offsets[part] = offset
        sizes[part] = size
    }

    fun sizeOf(part: Part): Long = overrides[part]?.size?.toLong() ?: (sizes[part] ?: 0L)
    fun has(part: Part): Boolean = sizeOf(part) > 0L
    fun offsetOf(part: Part): Long = offsets[part] ?: 0L

    fun setPart(part: Part, data: ByteArray) {
        overrides[part] = data
    }

    /** Loads a section into memory. Only call this for small sections. */
    fun readPart(part: Part): ByteArray? {
        overrides[part]?.let { return it }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return null
        val off = offsets[part] ?: return null
        return source?.read(off, size.toInt())
    }

    /** Streams a section (plus page padding) into [out]. */
    private fun writePart(out: OutputStream, part: Part, ps: Int) {
        val override = overrides[part]
        if (override != null) {
            out.write(override)
            pad(out, override.size.toLong(), ps)
            return
        }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return
        source?.copyTo(offsets[part] ?: 0L, size, out)
        pad(out, size, ps)
    }

    /** Streams a section without padding (used by 解包). */
    private fun writeRaw(out: OutputStream, part: Part) {
        val override = overrides[part]
        if (override != null) {
            out.write(override)
            return
        }
        val size = sizes[part] ?: 0L
        if (size <= 0L) return
        source?.copyTo(offsets[part] ?: 0L, size, out)
    }

    private fun pad(out: OutputStream, size: Long, ps: Int) {
        if (size <= 0L) return
        val rem = Bytes.align(size, ps) - size
        if (rem > 0) out.write(ByteArray(rem.toInt()))
    }

    // ------------------------------------------------------------ accessors

    val kernelSize: Long get() = sizeOf(Part.KERNEL)
    val ramdiskSize: Long get() = sizeOf(Part.RAMDISK)
    val secondSize: Long get() = sizeOf(Part.SECOND)
    val dtboSize: Long get() = sizeOf(Part.DTBO)
    val dtbSize: Long get() = sizeOf(Part.DTB)
    val signatureSize: Long get() = sizeOf(Part.SIGNATURE)

    fun readRamdisk(): ByteArray? = readPart(Part.RAMDISK)
    fun setRamdisk(data: ByteArray) = setPart(Part.RAMDISK, data)

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

    /** Writes every non ramdisk section to [dir]; returns the created files. */
    fun extractParts(dir: File): List<File> {
        dir.mkdirs()
        val written = ArrayList<File>()
        fun dump(part: Part, name: String) {
            if (!has(part)) return
            val f = File(dir, name)
            f.outputStream().buffered().use { writeRaw(it, part) }
            written.add(f)
        }
        dump(Part.KERNEL, "kernel")
        dump(Part.SECOND, "second.img")
        dump(Part.DTBO, "recovery_dtbo.img")
        dump(Part.DTB, "dtb.img")
        dump(Part.SIGNATURE, "boot_signature")
        dump(Part.BOOTCONFIG, "bootconfig")
        dump(Part.VENDOR_TABLE, "vendor_ramdisk_table.bin")
        return written
    }

    // ------------------------------------------------------------ packing

    /** Streams a rebuilt image; nothing is buffered in full. */
    fun packTo(out: OutputStream) {
        val sink = out.buffered(1 shl 20)
        when {
            isVendorBoot() -> packVendor(sink)
            headerVersion >= 3 -> packBootV3(sink)
            else -> packBootLegacy(sink)
        }
        sink.flush()
    }

    fun packTo(file: File) {
        file.outputStream().use { packTo(it) }
    }

    private fun packBootLegacy(out: OutputStream) {
        if (headerVersion >= 1 && pageSize < HDR_V1) pageSize = 2048
        if (headerVersion >= 2 && pageSize < HDR_V2) pageSize = 2048
        val hdr = ByteArray(pageSize)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, kernelSize)
        Bytes.put32(hdr, 0x0C, kernelAddr)
        Bytes.put32(hdr, 0x10, ramdiskSize)
        Bytes.put32(hdr, 0x14, ramdiskAddr)
        Bytes.put32(hdr, 0x18, secondSize)
        Bytes.put32(hdr, 0x1C, secondAddr)
        Bytes.put32(hdr, 0x20, tagsAddr)
        Bytes.put32(hdr, 0x24, pageSize.toLong())
        Bytes.put32(hdr, 0x28, if (headerVersion >= 1) headerVersion.toLong() else 0L)
        Bytes.put32(hdr, 0x2C, osVersion)
        Bytes.putCstr(hdr, 0x30, boardName, 16)
        writeCmdlineSplit(hdr)
        val digest = computeId()
        System.arraycopy(digest, 0, hdr, 0x240, minOf(digest.size, 32))
        if (headerVersion >= 1) {
            Bytes.put32(hdr, 0x660, dtboSize)
            Bytes.put64(hdr, 0x664, recoveryDtboOffset)
            Bytes.put32(hdr, 0x66C, (if (headerVersion >= 2) HDR_V2 else HDR_V1).toLong())
        }
        if (headerVersion >= 2) {
            Bytes.put32(hdr, 0x670, dtbSize)
            Bytes.put64(hdr, 0x674, dtbAddr)
        }
        out.write(hdr)
        writePart(out, Part.KERNEL, pageSize)
        writePart(out, Part.RAMDISK, pageSize)
        writePart(out, Part.SECOND, pageSize)
        if (headerVersion >= 1) writePart(out, Part.DTBO, pageSize)
        if (headerVersion >= 2) writePart(out, Part.DTB, pageSize)
    }

    private fun packBootV3(out: OutputStream) {
        val ps = 4096
        val hdr = ByteArray(ps)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, kernelSize)
        Bytes.put32(hdr, 0x0C, ramdiskSize)
        Bytes.put32(hdr, 0x10, osVersion)
        Bytes.put32(hdr, 0x14, (if (headerVersion >= 4) HDR_V4 else HDR_V3).toLong())
        Bytes.put32(hdr, 0x28, headerVersion.toLong())
        Bytes.putCstr(hdr, 0x2C, cmdline, 1536)
        if (headerVersion >= 4) Bytes.put32(hdr, 1580, signatureSize)
        out.write(hdr)
        writePart(out, Part.KERNEL, ps)
        writePart(out, Part.RAMDISK, ps)
        if (headerVersion >= 4) writePart(out, Part.SIGNATURE, ps)
    }

    private fun packVendor(out: OutputStream) {
        val hdrSize = if (headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
        val ramdiskSection = concatFragments()
        val tableBlob = buildVendorTable(ramdiskSection)
        val hdr = ByteArray(Bytes.align(hdrSize.toLong(), pageSize).toInt())
        VENDOR_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, headerVersion.toLong())
        Bytes.put32(hdr, 0x0C, pageSize.toLong())
        Bytes.put32(hdr, 0x10, kernelAddr)
        Bytes.put32(hdr, 0x14, ramdiskAddr)
        Bytes.put32(hdr, 0x18, ramdiskSection.size.toLong())
        Bytes.putCstr(hdr, 0x1C, cmdline, 2048)
        Bytes.put32(hdr, 0x81C, tagsAddr)
        Bytes.putCstr(hdr, 0x820, boardName, 16)
        Bytes.put32(hdr, 0x830, hdrSize.toLong())
        Bytes.put32(hdr, 0x834, dtbSize)
        Bytes.put64(hdr, 0x838, dtbAddr)
        if (headerVersion >= 4) {
            Bytes.put32(hdr, 2112, tableBlob.size.toLong())
            Bytes.put32(hdr, 2116, fragments.size.toLong())
            Bytes.put32(hdr, 2120, TABLE_ENTRY_SIZE.toLong())
            Bytes.put32(hdr, 2124, sizeOf(Part.BOOTCONFIG))
        }
        out.write(hdr)
        out.write(ramdiskSection)
        pad(out, ramdiskSection.size.toLong(), pageSize)
        writePart(out, Part.DTB, pageSize)
        if (headerVersion >= 4) {
            out.write(tableBlob)
            pad(out, tableBlob.size.toLong(), pageSize)
            writePart(out, Part.BOOTCONFIG, pageSize)
        }
    }

    private fun concatFragments(): ByteArray {
        if (fragments.isEmpty()) {
            return readPart(Part.RAMDISK) ?: ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        for (f in fragments) out.write(f.data, 0, f.data.size)
        return out.toByteArray()
    }

    private fun buildVendorTable(section: ByteArray): ByteArray {
        if (headerVersion < 4 || fragments.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
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
        if (first > 0) System.arraycopy(b, 0, hdr, 0x40, first)
        if (n > first) System.arraycopy(b, first, hdr, 0x260, n - first)
    }

    /** Classic mkbootimg id, hashed straight from disk without buffering. */
    private fun computeId(): ByteArray {
        val md = runCatching { MessageDigest.getInstance("SHA-1") }.getOrNull()
            ?: return ByteArray(0)
        val sink = DigestSink(md)
        for (part in arrayOf(Part.KERNEL, Part.RAMDISK, Part.SECOND, Part.DTBO, Part.DTB)) {
            writeRaw(sink, part)
        }
        return md.digest()
    }

    fun isVendorBoot(): Boolean = type == Type.VENDOR_BOOT

    /** Total size the rebuilt image will occupy, without building it. */
    fun packedSize(): Long {
        var total = Bytes.align(
            (if (isVendorBoot()) {
                if (headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
            } else if (headerVersion >= 3) {
                4096
            } else pageSize).toLong(),
            if (headerVersion >= 3) 4096 else pageSize
        )
        val ps = if (headerVersion >= 3 && !isVendorBoot()) 4096 else pageSize
        if (isVendorBoot()) {
            total += Bytes.align(sizeOf(Part.RAMDISK), ps)
            total += Bytes.align(dtbSize, ps)
            total += Bytes.align(sizeOf(Part.VENDOR_TABLE), ps)
            total += Bytes.align(sizeOf(Part.BOOTCONFIG), ps)
        } else if (headerVersion >= 3) {
            total += Bytes.align(kernelSize, ps)
            total += Bytes.align(ramdiskSize, ps)
            total += Bytes.align(signatureSize, ps)
        } else {
            total += Bytes.align(kernelSize, ps)
            total += Bytes.align(ramdiskSize, ps)
            total += Bytes.align(secondSize, ps)
            if (headerVersion >= 1) total += Bytes.align(dtboSize, ps)
            if (headerVersion >= 2) total += Bytes.align(dtbSize, ps)
        }
        return total
    }

    fun close() {
        runCatching { source?.close() }
    }
}
