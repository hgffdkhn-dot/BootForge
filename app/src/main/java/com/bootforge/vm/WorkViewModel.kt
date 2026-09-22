package com.bootforge.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bootforge.core.BootImage
import com.bootforge.core.Compress
import com.bootforge.core.Cpio
import com.bootforge.core.Dtb
import com.bootforge.core.FileImageSource
import com.bootforge.core.Format
import com.bootforge.core.Patcher
import com.bootforge.core.Ramdisk
import com.bootforge.core.Root
import com.bootforge.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class InjectItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    var target: String = "",
    var mode: String = "0755"
)

/** Lightweight ramdisk row: never carries the file payload into the UI layer. */
data class RamdiskRow(val name: String, val perms: String, val size: Int)

data class Options(
    val format: Format = Format.AUTO,
    val keepVerity: Boolean = false,
    val keepForceEncrypt: Boolean = false,
    val extraCmdline: String = ""
)

class WorkViewModel(app: Application) : AndroidViewModel(app) {

    data class Meta(val name: String, val size: Long, val kind: String, val version: String)

    val busy = MutableLiveData(false)
    val status = MutableLiveData("等待导入镜像")
    val message = MutableLiveData<String?>(null)
    val meta = MutableLiveData<Meta?>(null)
    val infoRows = MutableLiveData<List<Pair<String, String>>>(emptyList())
    val ramdiskRows = MutableLiveData<List<RamdiskRow>>(emptyList())
    val targets = MutableLiveData<List<String>>(emptyList())
    val lastOutput = MutableLiveData<File?>(null)
    val options = MutableLiveData(Options())

    private val root: File get() = File(getApplication<Application>().filesDir, "bootforge")
    private val workDir: File get() = File(root, "work").apply { mkdirs() }
    private val outDir: File get() = File(root, "out").apply { mkdirs() }
    private val backupDir: File get() = File(root, "backup").apply { mkdirs() }

    private var image: BootImage? = null
    private var ramdisk: Ramdisk? = null
    private var sourceName: String = "boot.img"
    private var targetIndex: Int = 0
    /** 删除过条目就必须整包重建，否则注入走「追加」的流式快路径。 */
    private var ramdiskDirty = false
    private var ramdiskTotal = 0

    companion object {
        /** 列表最多渲染多少条，避免上万条时一次性 inflate 卡死主线程。 */
        private const val MAX_ROWS = 300
        /** ramdisk 超过此大小不再解析（默认 96 MB，可按可用内存收紧）。 */
        private const val MAX_PARSE_BYTES = 96L * 1024 * 1024
    }

    private fun log(msg: String) = LogBus.add(msg)

    /** Shared repack settings, edited from both the image and the inject screen. */
    fun updateOptions(mutate: (Options) -> Options) {
        options.value = mutate(options.value ?: Options())
    }

    private fun postStatus(text: String) {
        status.postValue(text)
        log(text)
    }

    private fun <T> run(task: String, block: suspend () -> T) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.postValue(true)
            postStatus("$task …")
            val result = runCatching { block() }
            busy.postValue(false)
            result.onSuccess {
                postStatus("$task 完成")
            }.onFailure { e ->
                val raw = e.message ?: e.javaClass.simpleName
                val msg = if (e is OutOfMemoryError || raw.contains("OutOfMemory", true) ||
                    raw.contains("Failed to allocate", true)
                ) {
                    "内存不足：镜像过大，请只解包需要的段，或在注入前先删除不用的 ramdisk 条目"
                } else raw
                postStatus("$task 失败：$msg")
                message.postValue(msg)
            }
        }
    }

    // ------------------------------------------------------------ import

    fun importImage(uri: Uri, displayName: String) = run("导入 $displayName") {
        val name = displayName.substringAfterLast('/').ifBlank { "boot.img" }
        val dest = File(workDir, name)
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("无法读取所选文件")
        image?.close()
        val src = FileImageSource(dest)
        val img = BootImage.parse(src)
        image = img
        sourceName = name
        targetIndex = 0
        log("镜像大小 ${dest.length()} 字节，魔数偏移 ${img.magicOffset}")
        if (img.isVendorBoot()) img.loadFragments()
        loadRamdisk(img, 0)
        meta.postValue(
            Meta(name, dest.length(), if (img.isVendorBoot()) "vendor_boot" else "boot", "v${img.headerVersion}")
        )
        infoRows.postValue(buildInfo(img))
        lastOutput.postValue(null)
        Unit
    }

    fun selectTarget(index: Int) = run("切换 ramdisk 目标") {
        val img = image ?: error("尚未加载镜像")
        targetIndex = index
        loadRamdisk(img, index)
        infoRows.postValue(buildInfo(img))
        Unit
    }

    private fun loadRamdisk(img: BootImage, index: Int) {
        val payload: ByteArray? = if (img.isVendorBoot()) {
            img.fragments.getOrNull(index)?.data
        } else {
            img.readRamdisk()
        }
        val size = payload?.size?.toLong() ?: 0L
        ramdisk = if (payload == null || payload.isEmpty()) {
            null
        } else if (size > parseBudget()) {
            log("ramdisk 较大（${size / 1048576} MB），跳过完整解析；注入将以追加方式流式写入")
            null
        } else try {
            Ramdisk.fromImage(payload)
        } catch (e: OutOfMemoryError) {
            log("ramdisk 过大（${payload.size} 字节），内存不足，已跳过解析（镜像本身仍可重新打包）")
            null
        } catch (e: Exception) {
            log("ramdisk 解析失败：${e.message}")
            null
        }
        val names = if (img.isVendorBoot()) {
            img.fragments.map { "${it.name}（${typeName(it.type)}）" }
        } else listOf("boot ramdisk")
        targets.postValue(names)
        ramdiskDirty = false
        ramdiskTotal = ramdisk?.size ?: 0
        ramdiskRows.postValue(buildRows(ramdisk))
        ramdisk?.let { log("ramdisk 格式 ${it.sourceFormat.label}，共 ${it.size} 个条目") }
    }

    /** 可用内存越小，允许解析的 ramdisk 越小。 */
    private fun parseBudget(): Long {
        val max = Runtime.getRuntime().maxMemory()
        return minOf(MAX_PARSE_BYTES, max / 4)
    }

    /** 只把前 [MAX_ROWS] 条交给 RecyclerView，避免上万条时主线程 inflate 卡死。 */
    private fun buildRows(rd: Ramdisk?): List<RamdiskRow> {
        val entries = rd?.entries ?: return emptyList()
        ramdiskTotal = entries.size
        return entries.take(MAX_ROWS).map { RamdiskRow(it.name, it.permissions, it.data.size) }
    }

    fun ramdiskRowCount(): Int = ramdiskTotal

    private fun typeName(type: Int) = when (type) {
        1 -> "platform"
        2 -> "recovery"
        3 -> "dlkm"
        else -> "none"
    }

    // ------------------------------------------------------------ analysis

    private fun buildInfo(img: BootImage): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        rows += "镜像类型" to if (img.isVendorBoot()) "vendor_boot" else "boot/recovery/init_boot"
        rows += "头版本" to "v${img.headerVersion}"
        rows += "页大小" to "${img.pageSize} 字节"
        rows += "魔数偏移" to "0x${img.magicOffset.toString(16)}"
        if (img.boardName.isNotBlank()) rows += "设备名" to img.boardName
        if (img.osVersion != 0L) {
            val v = img.osVersion
            val a = (v shr 25) and 0x7FL
            val b = (v shr 18) and 0x7FL
            val c = (v shr 11) and 0x7FL
            val y = ((v shr 4) and 0x7FL) + 2000
            val m = v and 0xFL
            rows += "系统版本" to "$a.$b.$c"
            rows += "安全补丁" to "$y-${String.format(Locale.US, "%02d", m)}"
        }
        if (img.cmdline.isNotBlank()) rows += "内核命令行" to img.cmdline.trim()
        if (!img.isVendorBoot()) {
            rows += "kernel" to "${img.kernelSize} 字节"
            rows += "ramdisk" to "${img.ramdiskSize} 字节（${ramdisk?.sourceFormat?.label ?: "未解析"}）"
            if (img.secondSize > 0) rows += "second stage" to "${img.secondSize} 字节"
            if (img.dtboSize > 0) rows += "recovery dtbo" to "${img.dtboSize} 字节"
            if (img.dtbSize > 0) rows += "dtb" to "${img.dtbSize} 字节"
            if (img.signatureSize > 0) rows += "boot signature" to "${img.signatureSize} 字节"
        } else {
            rows += "vendor ramdisk" to "${img.fragments.sumOf { it.data.size }} 字节 / ${img.fragments.size} 个片段"
            img.fragments.forEachIndexed { i, f ->
                rows += "  片段 $i" to "${f.name} · ${typeName(f.type)} · ${f.data.size} 字节"
            }
            rows += "dtb" to "${img.dtbSize} 字节"
            if (img.has(BootImage.Part.BOOTCONFIG)) {
                rows += "bootconfig" to "${img.sizeOf(BootImage.Part.BOOTCONFIG)} 字节"
            }
        }
        val dtbTarget = img.readPart(BootImage.Part.DTB)
        if (dtbTarget != null && dtbTarget.isNotEmpty()) {
            val blobs = Dtb.parse(dtbTarget)
            rows += "DTB 数量" to "${blobs.size}"
            blobs.take(4).forEach { b ->
                val desc = listOf(b.model, b.compatible).filter { it.isNotBlank() }.joinToString(" · ")
                rows += "  dtb #${b.index}" to (if (desc.isBlank()) "${b.size} 字节" else "$desc（${b.size} 字节）")
            }
        }
        if (ramdiskTotal > 0) {
            rows += "ramdisk 条目" to buildString {
                append(ramdiskTotal)
                append(" 个")
                if (ramdiskTotal > MAX_ROWS) append("（列表仅显示前 $MAX_ROWS 个）")
                if (ramdisk == null) append(" · 未解析，注入走追加模式")
            }
        }
        return rows
    }

    // ------------------------------------------------------------ unpack

    fun unpack() = run("解包") {
        val img = image ?: error("尚未加载镜像")
        val dir = File(outDir, sourceName.substringBeforeLast('.') + "_unpacked")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        img.extractParts(dir).forEach { f ->
            log("写出 ${f.name}：${f.length()} 字节")
        }

        if (img.isVendorBoot()) {
            img.fragments.forEach { f ->
                val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(dir, "vendor_ramdisk_$safe${extOf(f.data)}").writeBytes(f.data)
                runCatching { Ramdisk.fromImage(f.data).extractTo(File(dir, "vendor_ramdisk_$safe")) }
            }
        } else {
            val rd = img.readRamdisk()
            if (rd != null && rd.isNotEmpty()) {
                File(dir, "ramdisk${extOf(rd)}").writeBytes(rd)
                val raw = Compress.decompress(rd, Compress.detect(rd))
                File(dir, "ramdisk.cpio").writeBytes(raw)
                val tree = File(dir, "ramdisk")
                val count = Ramdisk.fromImage(rd).extractTo(tree)
                log("解出 ramdisk：$count 个条目 → ${tree.path}")
            }
        }
        withContext(Dispatchers.Main) { message.postValue("已解包到 ${dir.name}") }
        dir.path
    }

    private fun extOf(data: ByteArray): String = when (Compress.detect(data)) {
        Format.GZIP -> ".cpio.gz"
        Format.LZ4 -> ".cpio.lz4"
        Format.LZ4_FRAME -> ".cpio.lz4"
        Format.NONE -> ".cpio"
        Format.AUTO -> ".bin"
    }

    // ------------------------------------------------------------ repack

    fun repack(options: Options) = run("重新打包") { repackInternal(options, "-patched") }

    fun inject(items: List<InjectItem>, options: Options) = run("注入并打包") {
        val img = image ?: error("尚未加载镜像")
        val modeList = ArrayList<Pair<InjectItem, Int>>()
        for (item in items) {
            val mode = item.mode.trim().toIntOrNull(8)
                ?: error("权限 ${item.mode} 不是合法的八进制数")
            modeList.add(Pair(item, mode))
        }

        val rd = ramdisk
        if (rd != null && !ramdiskDirty) {
            // 已解析且没删过条目：仍然走追加模式，避免整包解压/重压大 ramdisk
            postStatus("准备注入 ${items.size} 个文件 …")
            injectByAppend(img, items, modeList, options)
        } else if (rd != null) {
            postStatus("重建 ramdisk（曾删除条目）…")
            for ((item, mode) in modeList) {
                val bytes = readUri(item.uri) ?: error("读取 ${item.name} 失败")
                val target = item.target.trim().ifBlank { item.name }
                rd.add(target, bytes, mode or 0x8000)
                log("注入 ${item.name} → $target（${item.mode}）")
            }
            ramdiskTotal = rd.size
            ramdiskDirty = false
            ramdiskRows.postValue(buildRows(rd))
            repackInternal(options, "-injected")
        } else {
            postStatus("以追加方式注入（ramdisk 未解析）…")
            injectByAppend(img, items, modeList, options)
        }
    }

    private fun readUri(uri: Uri): ByteArray? =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }

    /**
     * 追加式注入：原 ramdisk 原样解压后，把新文件拼成一个新的 cpio 追加在尾部，
     * 再流式压缩写回。内核会按顺序解析串联的 cpio，因此等价于"塞进 ramdisk"。
     * 全程不把整个 ramdisk 装进内存。
     */
    private fun injectByAppend(
        img: BootImage,
        items: List<InjectItem>,
        modeList: List<Pair<InjectItem, Int>>,
        options: Options
    ) {
        if (img.isVendorBoot()) {
            // vendor_boot 的片段需要整段重建，只能走 ramdisk 解析路径
            val rd = ramdisk ?: error("vendor_boot 需要先解析 ramdisk 才能注入（当前已跳过解析）")
            for ((item, mode) in modeList) {
                val bytes = readUri(item.uri) ?: error("读取 ${item.name} 失败")
                rd.add(item.target.trim().ifBlank { item.name }, bytes, mode or 0x8000)
                log("注入 ${item.name}")
            }
            ramdiskTotal = rd.size
            repackInternal(options, "-injected")
            return
        }

        val rawSource = img.streamRamdisk() ?: error("当前镜像没有可写入的 ramdisk")
        val fmt = Compress.detect(img.peekPart(BootImage.Part.RAMDISK, 8))
        if (fmt == Format.AUTO) error("无法识别 ramdisk 压缩格式")

        // 1) 生成一个只含新文件的 cpio
        val scratch = Ramdisk(ArrayList<Cpio.Entry>(), fmt)
        for ((item, mode) in modeList) {
            val bytes = readUri(item.uri) ?: error("读取 ${item.name} 失败")
            val target = item.target.trim().ifBlank { item.name }
            scratch.add(target, bytes, mode or 0x8000)
            log("注入 ${item.name} → $target（${item.mode}）")
        }
        val appendCpio = Cpio.build(scratch.entries)

        // 2) 解压原 ramdisk 到临时文件，再与追加段串联流式压缩
        val tmpRamdisk = File(outDir, "ramdisk_append_${System.currentTimeMillis()}.tmp")
        tmpRamdisk.deleteOnExit()
        val targetFormat = if (options.format == Format.AUTO) fmt else options.format
        postStatus("重新压缩 ramdisk（${targetFormat.label}）…")
        rawSource.use { raw ->
            val decoded = Compress.decompressStream(raw, fmt)
            val joined = Compress.concat(decoded, java.io.ByteArrayInputStream(appendCpio))
            tmpRamdisk.outputStream().use { out ->
                Compress.compressStream(joined, targetFormat, out)
            }
        }

        img.setFilePart(BootImage.Part.RAMDISK, tmpRamdisk)
        log("ramdisk 流式重建完成：${tmpRamdisk.length()} 字节")
        ramdisk = null
        ramdiskRows.postValue(emptyList())
        repackInternal(options, "-injected")
    }

    private fun repackInternal(options: Options, suffix: String): String {
        val img = image ?: error("尚未加载镜像")
        val rd = ramdisk
        if (rd == null) {
            if (!options.keepVerity || !options.keepForceEncrypt) {
                log("ramdisk 未解析，跳过 fstab 修补（注入仍已生效）")
            }
        } else {
            val patch = Patcher.patchFstab(rd, options.keepVerity, options.keepForceEncrypt)
            patch.notes.forEach { log(it) }
            if (patch.files > 0) log("共修补 ${patch.files} 个 fstab 文件")
            val bytes = rd.toImage(options.format)
            if (img.isVendorBoot()) {
                if (targetIndex !in img.fragments.indices) error("ramdisk 目标无效")
                img.fragments[targetIndex].data = bytes
            } else {
                img.setRamdisk(bytes)
            }
            log("ramdisk 重新打包：${bytes.size} 字节（${options.format.label}）")
        }
        if (options.extraCmdline.isNotBlank()) {
            Patcher.appendCmdline(img, options.extraCmdline)
            log("追加 cmdline：${options.extraCmdline.trim()}")
        }
        val name = sourceName.substringBeforeLast('.') + suffix + ".img"
        val out = File(outDir, name)
        img.packTo(out)
        lastOutput.postValue(out)
        infoRows.postValue(buildInfo(img))
        log("输出：${out.path}（${out.length()} 字节）")
        message.postValue("已生成 ${out.name}")
        return out.path
    }

    fun removeRamdiskFile(name: String) = run("删除 $name") {
        val rd = ramdisk ?: error("没有 ramdisk")
        if (rd.remove(name)) {
            ramdiskDirty = true
            ramdiskTotal = rd.size
            ramdiskRows.postValue(buildRows(rd))
            log("已删除 $name（下次打包将整包重建 ramdisk）")
        }
        Unit
    }

    // ------------------------------------------------------------ export / flash

    fun export(uri: Uri) = run("导出") {
        val file = lastOutput.value ?: error("还没有生成产物")
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("无法写入目标位置")
        log("已导出 ${file.name}（${file.length()} 字节）")
        Unit
    }

    fun flash(partition: String) = run("刷入 $partition") {
        val file = lastOutput.value ?: error("请先重新打包生成产物")
        if (!Root.available()) error("未获得 root 权限，无法直接刷入")
        val result = Root.flash(file.path, partition)
        log("dd 返回 ${result.code}")
        if (!result.ok) error(result.text().ifBlank { "刷入失败" })
        withContext(Dispatchers.Main) { message.postValue("已刷入 $partition") }
        Unit
    }

    fun backup(partition: String) = run("备份 $partition") {
        if (!Root.available()) error("未获得 root 权限")
        val dest = File(backupDir, "${partition}_${System.currentTimeMillis()}.img")
        val result = Root.backup(partition, dest.path)
        if (!result.ok) error(result.text().ifBlank { "备份失败" })
        lastOutput.postValue(dest)
        log("已备份到 ${dest.path}（${dest.length()} 字节）")
        Unit
    }

    // ------------------------------------------------------------ misc

    fun refreshTargets() {
        val img = image ?: return
        val names = if (img.isVendorBoot()) img.fragments.map { it.name } else listOf("boot ramdisk")
        targets.postValue(names)
    }

    fun hasImage(): Boolean = image != null

    fun outputDir(): File = outDir

    fun clearMessage() {
        message.postValue(null)
    }
}
