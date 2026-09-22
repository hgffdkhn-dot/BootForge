package com.bootforge.cli

import com.bootforge.core.BootImage
import com.bootforge.core.Compress
import com.bootforge.core.Cpio
import com.bootforge.core.FileSource
import com.bootforge.core.Format
import com.bootforge.core.Patcher
import com.bootforge.core.Ramdisk
import com.bootforge.core.joinPath

private const val VERSION = "1.0.0"

private fun out(text: String) = print(text + "\n")

private fun out(text: String, vararg args: Any?) {
    print(text.format(*args) + "\n")
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }
    try {
        when (args[0]) {
            "info" -> cmdInfo(args.drop(1))
            "unpack" -> cmdUnpack(args.drop(1))
            "repack" -> cmdRepack(args.drop(1))
            "inject" -> cmdInject(args.drop(1))
            "patch" -> cmdPatch(args.drop(1))
            "-h", "--help", "help" -> printUsage()
            "-v", "--version" -> out("bootforge $VERSION")
            else -> {
                System.err.print("未知子命令: ${args[0]}\n")
                printUsage()
            }
        }
    } catch (e: Throwable) {
        System.err.print("错误: ${e.message ?: e}\n")
    }
}

private fun printUsage() {
    out(
        """
bootforge $VERSION — 安卓启动镜像工具（解包 / 分析 / 注入 / 重新打包）

用法:
  bootforge info   <镜像>                          显示镜像头部与分区信息
  bootforge unpack <镜像> [-o 目录]                解出 kernel / ramdisk / dtb 等，并展开 ramdisk
  bootforge repack <镜像> -o 新镜像 [选项]          重新打包（可替换 ramdisk、改压缩、补 cmdline）
  bootforge inject <镜像> -o 新镜像 文件=路径[:权限] [更多...]
                                                   把文件写进 ramdisk 后重新打包
  bootforge patch  <镜像> -o 新镜像 [选项]          去掉 dm-verity / 强制加密

repack / patch 选项:
  --ramdisk <文件>     用指定的 cpio 或压缩包替换 ramdisk
  --gzip | --lz4 | --lz4-frame | --none    设置 ramdisk 压缩格式（默认跟随原镜像）
  --keep-verity        保留 dm-verity / avb 校验（默认去掉）
  --keep-forceencrypt  保留强制加密（默认改成 encryptable）
  --cmdline "..."      追加内核命令行
  --no-patch           不做任何 fstab 修补，只重新打包

示例:
  bootforge info boot.img
  bootforge unpack boot.img -o boot_out
  bootforge inject boot.img -o new.img mytool=system/bin/mytool:0755
  bootforge patch boot.img -o new.img --lz4
""".trimIndent()
    )
}

// ------------------------------------------------------------------ info

private fun cmdInfo(args: List<String>) {
    val imgPath = args.firstOrNull() ?: error("缺少镜像路径")
    val img = BootImage.parse(FileSource(imgPath))
    val rd = img.readRamdisk()

    out("文件        : %s", imgPath)
    out("类型        : %s", if (img.isVendorBoot()) "vendor_boot" else "boot / recovery / init_boot")
    out("头版本      : v%d", img.headerVersion)
    out("页大小      : %d", img.pageSize)
    out("魔数偏移    : 0x%x", img.magicOffset)
    if (img.boardName.isNotBlank()) out("设备名      : %s", img.boardName)
    if (img.osVersion != 0L) {
        val v = img.osVersion
        out("系统版本    : %d.%d.%d", (v shr 25) and 0x7F, (v shr 18) and 0x7F, (v shr 11) and 0x7F)
        out("安全补丁    : %d-%02d", ((v shr 4) and 0x7F) + 2000, v and 0xF)
    }
    if (img.cmdline.isNotBlank()) out("内核命令行  : %s", img.cmdline.trim())

    if (img.isVendorBoot()) {
        img.loadFragments()
        out("vendor ramdisk : %d 个片段", img.fragments.size)
        img.fragments.forEachIndexed { i, f ->
            out("  [%d] %-24s type=%d  %d 字节", i, f.name, f.type, f.data.size)
        }
    } else {
        out("kernel      : %d 字节", img.sizeOf(BootImage.Part.KERNEL))
        out("ramdisk     : %d 字节", img.sizeOf(BootImage.Part.RAMDISK))
        if (img.has(BootImage.Part.SECOND)) out("second      : %d 字节", img.sizeOf(BootImage.Part.SECOND))
        if (img.has(BootImage.Part.DTBO)) out("recovery dtbo: %d 字节", img.sizeOf(BootImage.Part.DTBO))
        if (img.has(BootImage.Part.DTB)) out("dtb         : %d 字节", img.sizeOf(BootImage.Part.DTB))
        if (img.has(BootImage.Part.SIGNATURE)) out("signature   : %d 字节", img.sizeOf(BootImage.Part.SIGNATURE))
    }
    img.close()
}

// ------------------------------------------------------------------ unpack

private fun cmdUnpack(args: List<String>) {
    val imgPath = args.firstOrNull() ?: error("缺少镜像路径")
    val dir = option(args, "-o") ?: (imgPath.substringBeforeLast('.') + "_unpacked")
    val img = BootImage.parse(FileSource(imgPath))

    val written = img.extractParts(dir)
    written.forEach { out("写出 %s", it) }

    if (img.isVendorBoot()) {
        img.loadFragments()
        for (f in img.fragments) {
            val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val sub = joinPath(dir, "vendor_$safe")
            runCatching {
                val count = Ramdisk.fromImage(f.data).extractTo(sub)
                out("解出 %s：%d 个条目", sub, count)
            }
        }
        return
    }

    val rd = img.readRamdisk()
    if (rd == null || rd.isEmpty()) {
        out("镜像中没有 ramdisk")
        img.close()
        return
    }
    val fmt = Compress.detect(rd)
    val raw = Compress.decompress(rd, fmt)
    writeFile(joinPath(dir, "ramdisk.cpio"), raw)
    out("写出 %s（%s，%d 字节）", joinPath(dir, "ramdisk.cpio"), fmt.label, raw.size)

    val tree = joinPath(dir, "ramdisk")
    val count = Ramdisk.fromImage(rd).extractTo(tree)
    out("解出 %s：%d 个条目", tree, count)
    img.close()
}

// ------------------------------------------------------------------ repack

private fun cmdRepack(args: List<String>) {
    val imgPath = args.firstOrNull() ?: error("缺少镜像路径")
    val outPath = option(args, "-o") ?: error("缺少 -o 输出路径")
    val opts = buildOptions(args)

    val img = BootImage.parse(FileSource(imgPath))
    applyOptions(img, opts)
    img.packTo(outPath)
    img.close()
    out("已生成 %s（%d 字节）", outPath, fileSizeLong(outPath))
}

// ------------------------------------------------------------------ inject

private fun cmdInject(args: List<String>) {
    val imgPath = args.firstOrNull() ?: error("缺少镜像路径")
    val outPath = option(args, "-o") ?: error("缺少 -o 输出路径")
    val opts = buildOptions(args)

    val specs = args.filter { it.contains('=') && !it.startsWith("-") }
    if (specs.isEmpty()) error("没有要注入的文件，格式：本地文件=ramdisk内路径[:权限]")

    val img = BootImage.parse(FileSource(imgPath))
    if (img.isVendorBoot()) img.loadFragments()

    val payload = img.readRamdisk() ?: error("镜像中没有 ramdisk")
    val disk = Ramdisk.fromImage(payload)
    for (spec in specs) {
        val (local, rest) = spec.split('=', limit = 2)
        val target: String
        val modeStr: String
        if (rest.contains(':')) {
            target = rest.substringBeforeLast(':')
            modeStr = rest.substringAfterLast(':')
        } else {
            target = rest
            modeStr = "0755"
        }
        val mode = modeStr.trim().toIntOrNull(8) ?: error("权限 $modeStr 不是合法八进制数")
        val data = readFileBytes(local) ?: error("无法读取 $local")
        disk.add(target, data, mode or 0x8000)
        out("注入 %s -> %s (%s)", local, target, modeStr)
    }

    val packed = disk.toImage(
        if (opts.format == Format.AUTO) Format.AUTO else opts.format
    )
    if (img.isVendorBoot()) {
        if (img.fragments.isEmpty()) error("vendor_boot 没有可写入的片段")
        img.fragments[0].data = packed
    } else {
        img.setRamdisk(packed)
    }
    applyOptions(img, opts)
    img.packTo(outPath)
    img.close()
    out("已生成 %s（%d 字节）", outPath, fileSizeLong(outPath))
}

// ------------------------------------------------------------------ patch

private fun cmdPatch(args: List<String>) {
    val imgPath = args.firstOrNull() ?: error("缺少镜像路径")
    val outPath = option(args, "-o") ?: error("缺少 -o 输出路径")
    val opts = buildOptions(args).copy(noPatch = false)

    val img = BootImage.parse(FileSource(imgPath))
    applyOptions(img, opts)
    img.packTo(outPath)
    img.close()
    out("已生成 %s（%d 字节）", outPath, fileSizeLong(outPath))
}

// ------------------------------------------------------------------ shared

private class CliOptions(
    val format: Format,
    val keepVerity: Boolean,
    val keepForceEncrypt: Boolean,
    val cmdline: String,
    val noPatch: Boolean,
    val ramdiskPath: String?
)

private fun buildOptions(args: List<String>): CliOptions {
    var format = Format.AUTO
    if (args.contains("--gzip")) format = Format.GZIP
    if (args.contains("--lz4")) format = Format.LZ4
    if (args.contains("--lz4-frame")) format = Format.LZ4_FRAME
    if (args.contains("--none")) format = Format.NONE
    return CliOptions(
        format = format,
        keepVerity = args.contains("--keep-verity"),
        keepForceEncrypt = args.contains("--keep-forceencrypt"),
        cmdline = option(args, "--cmdline") ?: "",
        noPatch = args.contains("--no-patch"),
        ramdiskPath = option(args, "--ramdisk")
    )
}

private fun applyOptions(img: BootImage, o: CliOptions) {
    o.ramdiskPath?.let { path ->
        val data = readFileBytes(path) ?: error("无法读取 ramdisk: $path")
        // 允许传入已解压的 cpio 或压缩包
        val asRamdisk = if (Compress.isCpio(data)) {
            Compress.compress(Cpio.build(Ramdisk(parseCpio(data), Compress.detect(data)).entries), o.format)
        } else {
            data
        }
        if (img.isVendorBoot()) {
            if (img.fragments.isEmpty()) img.loadFragments()
            if (img.fragments.isEmpty()) error("vendor_boot 没有片段")
            img.fragments[0].data = asRamdisk
        } else {
            img.setRamdisk(asRamdisk)
        }
        out("替换 ramdisk：%s（%d 字节）", path, asRamdisk.size)
    }

    if (!o.noPatch && (!o.keepVerity || !o.keepForceEncrypt)) {
        if (img.isVendorBoot()) {
            if (img.fragments.isEmpty()) img.loadFragments()
            var total = 0
            img.fragments.forEach { f ->
                runCatching {
                    val disk = Ramdisk.fromImage(f.data)
                    val r = Patcher.patchFstab(disk, o.keepVerity, o.keepForceEncrypt)
                    if (r.files > 0) {
                        f.data = disk.toImage(o.format)
                        total += r.files
                    }
                }
            }
            if (total > 0) out("已修补 %d 个 fstab 文件", total)
        } else {
            val payload = img.readRamdisk()
            if (payload != null && payload.isNotEmpty()) {
                val disk = Ramdisk.fromImage(payload)
                val r = Patcher.patchFstab(disk, o.keepVerity, o.keepForceEncrypt)
                r.notes.forEach { out("  %s", it) }
                if (r.files > 0) {
                    img.setRamdisk(disk.toImage(o.format))
                    out("已修补 %d 个 fstab 文件", r.files)
                }
            }
        }
    }

    if (o.cmdline.isNotBlank()) {
        Patcher.appendCmdline(img, o.cmdline)
        out("追加 cmdline：%s", o.cmdline.trim())
    }
}

private fun parseCpio(data: ByteArray) = Cpio.parse(data)

private fun option(args: List<String>, name: String): String? {
    val i = args.indexOf(name)
    if (i < 0 || i + 1 >= args.size) return null
    return args[i + 1]
}

private fun readFileBytes(path: String): ByteArray? {
    val r = com.bootforge.core.FileReader(path)
    if (r.size() < 0) {
        r.close()
        return null
    }
    val data = r.read(0, r.size().toInt())
    r.close()
    return data
}

private fun writeFile(path: String, data: ByteArray) {
    val w = com.bootforge.core.FileWriter(path)
    w.write(data)
    w.close()
}

private fun fileSizeLong(path: String): Long = com.bootforge.core.fileSize(path)
