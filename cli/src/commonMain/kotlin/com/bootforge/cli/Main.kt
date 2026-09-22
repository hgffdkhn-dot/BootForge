package com.bootforge.cli

import com.bootforge.core.BootImage
import com.bootforge.core.Cpio
import com.bootforge.core.FileReader
import com.bootforge.core.FileSource
import com.bootforge.core.FileWriter
import com.bootforge.core.Format
import com.bootforge.core.Patcher
import com.bootforge.core.Ramdisk
import com.bootforge.core.fileSize
import com.bootforge.core.joinPath
import com.bootforge.core.mkdirs
import com.bootforge.core.stderr

private const val VERSION = "1.0.0"

private fun o(line: String) = print(line + "\n")

/** 极简占位符替换，只支持 %s / %d / %x / %02d，避免依赖 String.format。 */
private fun fmt(template: String, vararg args: Any?): String {
    val sb = StringBuilder()
    var ai = 0
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '%' && i + 1 < template.length && ai < args.size) {
            i++
            val spec = StringBuilder()
            while (i < template.length && template[i].isLetter()) {
                spec.append(template[i])
                i++
            }
            val value = args[ai++]
            when (spec.toString()) {
                "s" -> sb.append(value?.toString() ?: "")
                "d" -> sb.append((value as? Number)?.toLong() ?: 0L)
                "x" -> sb.append(((value as? Number)?.toLong() ?: 0L).toString(16))
                "02d" -> {
                    val v = (value as? Number)?.toLong() ?: 0L
                    val s = v.toString()
                    if (s.length < 2) sb.append('0')
                    sb.append(s)
                }
                else -> sb.append(value?.toString() ?: "")
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        return
    }
    try {
        when (args[0]) {
            "info" -> cmdInfo(args)
            "unpack" -> cmdUnpack(args)
            "repack" -> cmdRepack(args)
            "inject" -> cmdInject(args)
            "patch" -> cmdPatch(args)
            "-h", "--help", "help" -> usage()
            "-v", "--version" -> o("bootforge $VERSION")
            else -> {
                stderr("未知子命令: ${args[0]}\n")
                usage()
            }
        }
    } catch (e: Throwable) {
        stderr("错误: ${e.message ?: e.toString()}\n")
    }
}

private fun usage() {
    o("")
    o("bootforge $VERSION — 安卓启动镜像工具（解包 / 分析 / 注入 / 重新打包）")
    o("")
    o("用法:")
    o("  bootforge info   <镜像>                        显示镜像头部与各段信息")
    o("  bootforge unpack <镜像> [-o 目录]              解出 kernel / ramdisk / dtb，并展开 ramdisk")
    o("  bootforge repack <镜像> -o 新镜像 [选项]        重新打包（可替换 ramdisk、改压缩、补 cmdline）")
    o("  bootforge inject <镜像> -o 新镜像 文件=路径[:权限] [更多...]")
    o("                                                把文件写进 ramdisk 后重新打包")
    o("  bootforge patch  <镜像> -o 新镜像 [选项]        去掉 dm-verity / 强制加密")
    o("")
    o("选项:")
    o("  --ramdisk <文件>   用指定 cpio（或压缩包）替换 ramdisk")
    o("  --gzip | --lz4 | --lz4-frame | --none   设置 ramdisk 压缩格式（默认跟随原镜像）")
    o("  --keep-verity      保留 dm-verity / avb 校验（默认去掉）")
    o("  --keep-forceencrypt 保留强制加密（默认改成 encryptable）")
    o("  --cmdline \"...\"    追加内核命令行")
    o("  --no-patch         不做 fstab 修补，只重新打包")
    o("")
    o("示例:")
    o("  bootforge info boot.img")
    o("  bootforge unpack boot.img -o boot_out")
    o("  bootforge inject boot.img -o new.img mytool=system/bin/mytool:0755")
    o("  bootforge patch boot.img -o new.img --lz4")
    o("")
}

// ------------------------------------------------------------------ info

private fun cmdInfo(args: Array<String>) {
    val imgPath = positional(args)
    val img = BootImage.parse(FileSource(imgPath))

    o(fmt("文件       : %s", imgPath))
    o(fmt("类型       : %s", if (img.isVendorBoot()) "vendor_boot" else "boot / recovery / init_boot"))
    o(fmt("头版本     : v%d", img.headerVersion))
    o(fmt("页大小     : %d", img.pageSize))
    o(fmt("魔数偏移   : 0x%x", img.magicOffset))
    if (img.boardName.isNotBlank()) o(fmt("设备名     : %s", img.boardName))
    if (img.osVersion != 0L) {
        val v = img.osVersion
        o(fmt("系统版本   : %d.%d.%d", (v shr 25) and 0x7F, (v shr 18) and 0x7F, (v shr 11) and 0x7F))
        o(fmt("安全补丁   : %d-%02d", ((v shr 4) and 0x7F) + 2000, v and 0xF))
    }
    if (img.cmdline.isNotBlank()) o(fmt("内核命令行 : %s", img.cmdline.trim()))

    if (img.isVendorBoot()) {
        img.loadFragments()
        o(fmt("vendor ramdisk : %d 个片段", img.fragments.size))
        img.fragments.forEachIndexed { i, f ->
            o(fmt("  [%d] %s  type=%d  %d 字节", i, f.name, f.type, f.data.size))
        }
    } else {
        o(fmt("kernel     : %d 字节", img.sizeOf(BootImage.Part.KERNEL)))
        o(fmt("ramdisk    : %d 字节", img.sizeOf(BootImage.Part.RAMDISK)))
        if (img.has(BootImage.Part.SECOND)) o(fmt("second     : %d 字节", img.sizeOf(BootImage.Part.SECOND)))
        if (img.has(BootImage.Part.DTBO)) o(fmt("recovery dtbo: %d 字节", img.sizeOf(BootImage.Part.DTBO)))
        if (img.has(BootImage.Part.DTB)) o(fmt("dtb        : %d 字节", img.sizeOf(BootImage.Part.DTB)))
        if (img.has(BootImage.Part.SIGNATURE)) o(fmt("signature  : %d 字节", img.sizeOf(BootImage.Part.SIGNATURE)))
    }

    val rd = img.readRamdisk()
    if (rd != null && rd.isNotEmpty()) {
        runCatching {
            val disk = Ramdisk.fromImage(rd)
            o(fmt("ramdisk 条目: %d 个", disk.size))
        }
    }
    img.close()
}

// ------------------------------------------------------------------ unpack

private fun cmdUnpack(args: Array<String>) {
    val imgPath = positional(args)
    val dir = option(args, "-o") ?: (imgPath.substringBeforeLast('.') + "_unpacked")
    val img = BootImage.parse(FileSource(imgPath))
    mkdirs(dir)

    img.extractParts(dir).forEach { o(fmt("写出 %s", it)) }

    if (img.isVendorBoot()) {
        img.loadFragments()
        for (f in img.fragments) {
            val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val sub = joinPath(dir, "vendor_$safe")
            runCatching {
                val count = Ramdisk.fromImage(f.data).extractTo(sub)
                o(fmt("解出 %s：%d 个条目", sub, count))
            }
        }
        img.close()
        return
    }

    val rd = img.readRamdisk()
    if (rd == null || rd.isEmpty()) {
        o("镜像中没有 ramdisk")
        img.close()
        return
    }
    val raw = runCatching { Ramdisk.fromImage(rd) }.getOrNull()
    if (raw == null) {
        o("ramdisk 解压失败，仅保留原始文件")
        img.close()
        return
    }
    val cpioPath = joinPath(dir, "ramdisk.cpio")
    val w = FileWriter(cpioPath)
    w.write(Cpio.build(raw.entries))
    w.close()
    o(fmt("写出 %s（%d 个条目）", cpioPath, raw.size))

    val tree = joinPath(dir, "ramdisk")
    val count = raw.extractTo(tree)
    o(fmt("解出 %s：%d 个条目", tree, count))
    img.close()
}

// ------------------------------------------------------------------ repack / patch

private fun cmdRepack(args: Array<String>) {
    val imgPath = positional(args)
    val outPath = option(args, "-o") ?: throw IllegalStateException("缺少 -o 输出路径")
    val opts = Options(args)

    val img = BootImage.parse(FileSource(imgPath))
    applyOptions(img, opts)
    img.packTo(outPath)
    img.close()
    o(fmt("已生成 %s（%d 字节）", outPath, fileSize(outPath)))
}

private fun cmdPatch(args: Array<String>) = cmdRepack(args)

// ------------------------------------------------------------------ inject

private fun cmdInject(args: Array<String>) {
    val imgPath = positional(args)
    val outPath = option(args, "-o") ?: throw IllegalStateException("缺少 -o 输出路径")
    val opts = Options(args)

    val specs = ArrayList<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a == "-o" || a == "--ramdisk" || a == "--cmdline") {
            i += 2
            continue
        }
        if (!a.startsWith("-") && a.contains('=')) specs.add(a)
        i++
    }
    if (specs.isEmpty()) throw IllegalStateException("没有要注入的文件，格式：本地文件=ramdisk内路径[:权限]")

    val img = BootImage.parse(FileSource(imgPath))
    if (img.isVendorBoot()) img.loadFragments()

    val payload = img.readRamdisk() ?: throw IllegalStateException("镜像中没有 ramdisk")
    val disk = Ramdisk.fromImage(payload)

    for (spec in specs) {
        val idx = spec.indexOf('=')
        val local = spec.substring(0, idx)
        val rest = spec.substring(idx + 1)
        val target: String
        val modeStr: String
        val ci = rest.lastIndexOf(':')
        if (ci > 0) {
            target = rest.substring(0, ci)
            modeStr = rest.substring(ci + 1)
        } else {
            target = rest
            modeStr = "0755"
        }
        val mode = modeStr.trim().toIntOrNull(8)
            ?: throw IllegalStateException("权限 $modeStr 不是合法八进制数")
        val data = readBytes(local) ?: throw IllegalStateException("无法读取 $local")
        disk.add(target, data, mode or 0x8000)
        o(fmt("注入 %s -> %s (%s)", local, target, modeStr))
    }

    val packed = disk.toImage(if (opts.format == Format.AUTO) Format.AUTO else opts.format)
    if (img.isVendorBoot()) {
        if (img.fragments.isEmpty()) throw IllegalStateException("vendor_boot 没有可写入的片段")
        img.fragments[0].data = packed
    } else {
        img.setRamdisk(packed)
    }
    applyOptions(img, opts)
    img.packTo(outPath)
    img.close()
    o(fmt("已生成 %s（%d 字节）", outPath, fileSize(outPath)))
}

// ------------------------------------------------------------------ 公共

private class Options(args: Array<String>) {
    var format: Format = Format.AUTO
    var keepVerity = false
    var keepForceEncrypt = false
    var cmdline = ""
    var noPatch = false
    var ramdiskPath: String? = null

    init {
        if (args.contains("--gzip")) format = Format.GZIP
        if (args.contains("--lz4")) format = Format.LZ4
        if (args.contains("--lz4-frame")) format = Format.LZ4_FRAME
        if (args.contains("--none")) format = Format.NONE
        keepVerity = args.contains("--keep-verity")
        keepForceEncrypt = args.contains("--keep-forceencrypt")
        noPatch = args.contains("--no-patch")
        cmdline = option(args, "--cmdline") ?: ""
        ramdiskPath = option(args, "--ramdisk")
    }
}

private fun applyOptions(img: BootImage, o: Options) {
    o.ramdiskPath?.let { path ->
        val data = readBytes(path) ?: throw IllegalStateException("无法读取 ramdisk: $path")
        val replacement = data
        if (img.isVendorBoot()) {
            if (img.fragments.isEmpty()) img.loadFragments()
            if (img.fragments.isEmpty()) throw IllegalStateException("vendor_boot 没有片段")
            img.fragments[0].data = replacement
        } else {
            img.setRamdisk(replacement)
        }
        print(fmt("替换 ramdisk：%s（%d 字节）", path, replacement.size) + "\n")
    }

    if (!o.noPatch && (!o.keepVerity || !o.keepForceEncrypt)) {
        if (img.isVendorBoot()) {
            if (img.fragments.isEmpty()) img.loadFragments()
            var total = 0
            for (f in img.fragments) {
                runCatching {
                    val disk = Ramdisk.fromImage(f.data)
                    val r = Patcher.patchFstab(disk, o.keepVerity, o.keepForceEncrypt)
                    if (r.files > 0) {
                        f.data = disk.toImage(o.format)
                        total += r.files
                    }
                }
            }
            if (total > 0) print(fmt("已修补 %d 个 fstab 文件", total) + "\n")
        } else {
            val payload = img.readRamdisk()
            if (payload != null && payload.isNotEmpty()) {
                val disk = Ramdisk.fromImage(payload)
                val r = Patcher.patchFstab(disk, o.keepVerity, o.keepForceEncrypt)
                for (note in r.notes) print("  $note\n")
                if (r.files > 0) {
                    img.setRamdisk(disk.toImage(o.format))
                    print(fmt("已修补 %d 个 fstab 文件", r.files) + "\n")
                }
            }
        }
    }

    if (o.cmdline.isNotBlank()) {
        Patcher.appendCmdline(img, o.cmdline)
        print(fmt("追加 cmdline：%s", o.cmdline.trim()) + "\n")
    }
}

private fun positional(args: Array<String>): String {
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a == "-o" || a == "--ramdisk" || a == "--cmdline") {
            i += 2
            continue
        }
        if (!a.startsWith("-")) return a
        i++
    }
    throw IllegalStateException("缺少镜像路径")
}

private fun option(args: Array<String>, name: String): String? {
    var i = 0
    while (i < args.size) {
        if (args[i] == name && i + 1 < args.size) return args[i + 1]
        i++
    }
    return null
}

private fun readBytes(path: String): ByteArray? {
    val r = FileReader(path)
    val size = r.size()
    if (size < 0) {
        r.close()
        return null
    }
    val data = r.read(0, size.toInt())
    r.close()
    return data
}
