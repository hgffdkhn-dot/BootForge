package com.bootforge.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * Android boot / vendor_boot image parser & builder.
 *
 * Offsets follow AOSP system/tools/mkbootimg/include/bootimg/bootimg.h
 *  - boot header v0 : 1632 bytes, v1 : 1648, v2 : 1660, v3 : 1580, v4 : 1584
 *  - boot header version field always lives at offset 0x28 (shared with the legacy dt_size field)
 *  - vendor boot header v3 : 2112 bytes, v4 : 2128
 */
class BootImage {

    enum class Type { BOOT, VENDOR_BOOT }

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

        /** Locates the magic inside a container file (payload images, sparse wrappers ...). */
        fun findMagic(data: ByteArray): Pair<Type, Int> {
            val limit = minOf(data.size - 8, SEEK_LIMIT)
            for (i in 0..limit) {
                if (match(data, i, BOOT_MAGIC)) return Pair(Type.BOOT, i)
                if (match(data, i, VENDOR_MAGIC)) return Pair(Type.VENDOR_BOOT, i)
            }
            throw IllegalArgumentException("未找到 ANDROID! / VNDRBOOT 魔数，不是有效的启动镜像")
        }

        private fun match(data: ByteArray, off: Int, magic: ByteArray): Boolean {
            if (off + magic.size > data.size) return false
            for (i in magic.indices) if (data[off + i] != magic[i]) return false
            return true
        }

        fun parse(data: ByteArray): BootImage {
            val (type, base) = findMagic(data)
            return if (type == Type.VENDOR_BOOT) parseVendor(data, base) else parseBoot(data, base)
        }

        private fun parseBoot(data: ByteArray, base: Int): BootImage {
            val img = BootImage()
            img.type = Type.BOOT
            img.magicOffset = base.toLong()

            var ver = Bytes.get32(data, base + 0x28).toInt()
            // legacy images store the dt_size in the same slot
            if (ver > 8) ver = 0
            img.headerVersion = ver

            if (ver >= 3) {
                img.pageSize = 4096
                val kernelSize = Bytes.get32(data, base + 0x08)
                val ramdiskSize = Bytes.get32(data, base + 0x0C)
                img.osVersion = Bytes.get32(data, base + 0x10)
                img.headerSizeField = Bytes.get32(data, base + 0x14)
                img.cmdline = Bytes.cstr(data, base + 0x2C, 1536)
                val sigSize = if (ver >= 4) Bytes.get32(data, base + 1580) else 0L
                var pos = base.toLong() + img.pageSize
                img.kernel = slice(data, pos, kernelSize)
                pos += Bytes.align(kernelSize, img.pageSize)
                img.ramdisk = slice(data, pos, ramdiskSize)
                pos += Bytes.align(ramdiskSize, img.pageSize)
                if (sigSize > 0) img.signature = slice(data, pos, sigSize)
            } else {
                val ps = Bytes.get32(data, base + 0x24).toInt()
                img.pageSize = if (ps in 512..65536 && (ps and (ps - 1)) == 0) ps else 2048
                val kernelSize = Bytes.get32(data, base + 0x08)
                val ramdiskSize = Bytes.get32(data, base + 0x10)
                val secondSize = Bytes.get32(data, base + 0x18)
                img.kernelAddr = Bytes.get32(data, base + 0x0C)
                img.ramdiskAddr = Bytes.get32(data, base + 0x14)
                img.secondAddr = Bytes.get32(data, base + 0x1C)
                img.tagsAddr = Bytes.get32(data, base + 0x20)
                img.osVersion = Bytes.get32(data, base + 0x2C)
                img.boardName = Bytes.cstr(data, base + 0x30, 16)
                val main = Bytes.cstr(data, base + 0x40, 512)
                val extra = Bytes.cstr(data, base + 0x260, 1024)
                img.cmdline = main + extra
                if (base + 0x240 < data.size) {
                    img.id = data.copyOfRange(base + 0x240, minOf(base + 0x260, data.size))
                }
                var pos = base.toLong() + img.pageSize
                img.kernel = slice(data, pos, kernelSize)
                pos += Bytes.align(kernelSize, img.pageSize)
                img.ramdisk = slice(data, pos, ramdiskSize)
                pos += Bytes.align(ramdiskSize, img.pageSize)
                img.second = slice(data, pos, secondSize)
                pos += Bytes.align(secondSize, img.pageSize)
                if (ver >= 1) {
                    val dtboSize = Bytes.get32(data, base + 0x660)
                    img.recoveryDtboOffset = Bytes.get64(data, base + 0x664)
                    img.headerSizeField = Bytes.get32(data, base + 0x66C)
                    img.recoveryDtbo = slice(data, pos, dtboSize)
                    pos += Bytes.align(dtboSize, img.pageSize)
                }
                if (ver >= 2) {
                    val dtbSize = Bytes.get32(data, base + 0x670)
                    img.dtbAddr = Bytes.get64(data, base + 0x674)
                    img.dtb = slice(data, pos, dtbSize)
                }
            }
            return img
        }

        private fun parseVendor(data: ByteArray, base: Int): BootImage {
            val img = BootImage()
            img.type = Type.VENDOR_BOOT
            img.magicOffset = base.toLong()
            img.headerVersion = Bytes.get32(data, base + 0x08).toInt()
            val ps = Bytes.get32(data, base + 0x0C).toInt()
            img.pageSize = if (ps in 512..65536 && (ps and (ps - 1)) == 0) ps else 2048
            img.kernelAddr = Bytes.get32(data, base + 0x10)
            img.ramdiskAddr = Bytes.get32(data, base + 0x14)
            img.vendorRamdiskSize = Bytes.get32(data, base + 0x18)
            img.cmdline = Bytes.cstr(data, base + 0x1C, 2048)
            img.tagsAddr = Bytes.get32(data, base + 0x81C)
            img.boardName = Bytes.cstr(data, base + 0x820, 16)
            img.headerSizeField = Bytes.get32(data, base + 0x830)
            val dtbSize = Bytes.get32(data, base + 0x834)
            img.dtbAddr = Bytes.get64(data, base + 0x838)

            val hdrSize = if (img.headerVersion >= 4) VENDOR_HDR_V4 else VENDOR_HDR_V3
            var pos = base.toLong() + Bytes.align(hdrSize.toLong(), img.pageSize)
            val section = slice(data, pos, img.vendorRamdiskSize)
            pos += Bytes.align(img.vendorRamdiskSize, img.pageSize)
            img.dtb = slice(data, pos, dtbSize)
            pos += Bytes.align(dtbSize, img.pageSize)

            if (img.headerVersion >= 4) {
                val tableSize = Bytes.get32(data, base + 2112)
                img.vendorRamdiskTableEntryNum = Bytes.get32(data, base + 2116).toInt()
                img.vendorRamdiskTableEntrySize = Bytes.get32(data, base + 2120).toInt()
                img.bootconfigSize = Bytes.get32(data, base + 2124)
                val table = slice(data, pos, tableSize)
                img.vendorRamdiskTable = table
                pos += Bytes.align(tableSize, img.pageSize)
                img.bootconfig = slice(data, pos, img.bootconfigSize)
                img.fragments.addAll(splitVendorRamdisks(section, table, img.vendorRamdiskTableEntrySize))
            } else {
                img.fragments.add(VendorFragment("vendor_ramdisk", 1, section))
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
                val data = if (rel + size <= section.size) {
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

        private fun slice(data: ByteArray, off: Long, size: Long): ByteArray {
            val s = size.toInt()
            if (s <= 0) return ByteArray(0)
            val o = off.toInt()
            if (o < 0 || o + s > data.size) {
                // tolerate truncated images: return what is available
                val end = minOf(o + s, data.size)
                if (end <= o) return ByteArray(0)
                return data.copyOfRange(o, end)
            }
            return data.copyOfRange(o, o + s)
        }
    }

    data class VendorFragment(
        var name: String,
        var type: Int,
        var data: ByteArray,
        var boardId: IntArray = IntArray(16)
    )

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
    var id: ByteArray = ByteArray(32)
    var recoveryDtboOffset: Long = 0
    var dtbAddr: Long = 0

    // vendor boot header
    var vendorRamdiskSize: Long = 0
    var vendorRamdiskTableEntryNum: Int = 0
    var vendorRamdiskTableEntrySize: Int = 0
    var bootconfigSize: Long = 0

    var kernel: ByteArray? = null
    var ramdisk: ByteArray? = null
    var second: ByteArray? = null
    var recoveryDtbo: ByteArray? = null
    var dtb: ByteArray? = null
    var signature: ByteArray? = null
    var vendorRamdiskTable: ByteArray? = null
    var bootconfig: ByteArray? = null
    val fragments: ArrayList<VendorFragment> = ArrayList()

    val kernelSize: Int get() = kernel?.size ?: 0
    val ramdiskSize: Int get() = ramdisk?.size ?: 0
    val secondSize: Int get() = second?.size ?: 0
    val dtboSize: Int get() = recoveryDtbo?.size ?: 0
    val dtbSize: Int get() = dtb?.size ?: 0
    val signatureSize: Int get() = signature?.size ?: 0

    /** Rebuilds the image from the current sections. */
    fun pack(): ByteArray {
        return if (type == Type.VENDOR_BOOT) packVendor() else {
            if (headerVersion >= 3) packBootV3() else packBootLegacy()
        }
    }

    private fun pad(out: ByteArrayOutputStream, data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        out.write(data, 0, data.size)
        val padded = Bytes.align(data.size.toLong(), pageSize) - data.size
        if (padded > 0) out.write(ByteArray(padded.toInt()))
    }

    private fun packBootLegacy(): ByteArray {
        if (headerVersion >= 1 && pageSize < HDR_V1) pageSize = 2048
        if (headerVersion >= 2 && pageSize < HDR_V2) pageSize = 2048
        val hdr = ByteArray(pageSize)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, kernelSize.toLong())
        Bytes.put32(hdr, 0x0C, kernelAddr)
        Bytes.put32(hdr, 0x10, ramdiskSize.toLong())
        Bytes.put32(hdr, 0x14, ramdiskAddr)
        Bytes.put32(hdr, 0x18, secondSize.toLong())
        Bytes.put32(hdr, 0x1C, secondAddr)
        Bytes.put32(hdr, 0x20, tagsAddr)
        Bytes.put32(hdr, 0x24, pageSize.toLong())
        Bytes.put32(hdr, 0x28, if (headerVersion >= 1) headerVersion.toLong() else 0L)
        Bytes.put32(hdr, 0x2C, osVersion)
        Bytes.putCstr(hdr, 0x30, boardName, 16)
        writeCmdlineSplit(hdr)
        val digest = recomputeId()
        System.arraycopy(digest, 0, hdr, 0x240, minOf(digest.size, 32))
        if (headerVersion >= 1) {
            Bytes.put32(hdr, 0x660, dtboSize.toLong())
            Bytes.put64(hdr, 0x664, recoveryDtboOffset)
            Bytes.put32(hdr, 0x66C, (if (headerVersion >= 2) HDR_V2 else HDR_V1).toLong())
        }
        if (headerVersion >= 2) {
            Bytes.put32(hdr, 0x670, dtbSize.toLong())
            Bytes.put64(hdr, 0x674, dtbAddr)
        }
        val out = ByteArrayOutputStream()
        out.write(hdr, 0, hdr.size)
        pad(out, kernel)
        pad(out, ramdisk)
        pad(out, second)
        if (headerVersion >= 1) pad(out, recoveryDtbo)
        if (headerVersion >= 2) pad(out, dtb)
        return out.toByteArray()
    }

    private fun packBootV3(): ByteArray {
        val ps = 4096
        val hdr = ByteArray(ps)
        BOOT_MAGIC.copyInto(hdr, 0)
        Bytes.put32(hdr, 0x08, kernelSize.toLong())
        Bytes.put32(hdr, 0x0C, ramdiskSize.toLong())
        Bytes.put32(hdr, 0x10, osVersion)
        Bytes.put32(hdr, 0x14, (if (headerVersion >= 4) HDR_V4 else HDR_V3).toLong())
        Bytes.put32(hdr, 0x28, headerVersion.toLong())
        Bytes.putCstr(hdr, 0x2C, cmdline, 1536)
        if (headerVersion >= 4) Bytes.put32(hdr, 1580, signatureSize.toLong())
        val out = ByteArrayOutputStream()
        out.write(hdr, 0, hdr.size)
        padPage(out, kernel, ps)
        padPage(out, ramdisk, ps)
        if (headerVersion >= 4) padPage(out, signature, ps)
        return out.toByteArray()
    }

    private fun padPage(out: ByteArrayOutputStream, data: ByteArray?, ps: Int) {
        if (data == null || data.isEmpty()) return
        out.write(data, 0, data.size)
        val padded = Bytes.align(data.size.toLong(), ps) - data.size
        if (padded > 0) out.write(ByteArray(padded.toInt()))
    }

    private fun packVendor(): ByteArray {
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
        Bytes.put32(hdr, 0x834, dtbSize.toLong())
        Bytes.put64(hdr, 0x838, dtbAddr)
        if (headerVersion >= 4) {
            Bytes.put32(hdr, 2112, tableBlob.size.toLong())
            Bytes.put32(hdr, 2116, fragments.size.toLong())
            Bytes.put32(hdr, 2120, TABLE_ENTRY_SIZE.toLong())
            Bytes.put32(hdr, 2124, bootconfig?.size?.toLong() ?: 0L)
        }
        val out = ByteArrayOutputStream()
        out.write(hdr, 0, hdr.size)
        pad(out, ramdiskSection)
        pad(out, dtb)
        if (headerVersion >= 4) {
            pad(out, tableBlob)
            pad(out, bootconfig)
        }
        return out.toByteArray()
    }

    private fun concatFragments(): ByteArray {
        if (fragments.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        for (f in fragments) out.write(f.data, 0, f.data.size)
        return out.toByteArray()
    }

    private fun buildVendorTable(section: ByteArray): ByteArray {
        if (headerVersion < 4 || fragments.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
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
        val total = 512 + 1024
        val n = minOf(b.size, total - 1)
        val first = minOf(n, 511)
        System.arraycopy(b, 0, hdr, 0x40, first)
        if (n > first) System.arraycopy(b, first, hdr, 0x260, n - first)
    }

    /** Classic mkbootimg style id: SHA-1 over the payload sections. */
    private fun recomputeId(): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        kernel?.let { md.update(it) }
        ramdisk?.let { md.update(it) }
        second?.let { md.update(it) }
        recoveryDtbo?.let { md.update(it) }
        dtb?.let { md.update(it) }
        return md.digest()
    }

    fun isVendorBoot(): Boolean = type == Type.VENDOR_BOOT

    /** Human readable one line summary. */
    fun summary(): String = buildString {
        append(if (isVendorBoot()) "vendor_boot" else "boot")
        append(" · header v").append(headerVersion)
        append(" · page ").append(pageSize)
        append(" · ").append(String.format("%.2f MB", (pack().size / 1048576.0)))
    }
}
