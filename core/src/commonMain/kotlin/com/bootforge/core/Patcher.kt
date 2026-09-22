package com.bootforge.core

/**
 * Small helpers that rewrite ramdisk contents (the classic dm-verity / forceencrypt patches).
 */
object Patcher {

    data class Result(var files: Int, val notes: MutableList<String> = ArrayList())

    /**
     * @param keepVerity        true  -> 保留 dm-verity（不打补丁）
     * @param keepForceEncrypt  true  -> 保留强制加密（不打补丁）
     */
    fun patchFstab(ramdisk: Ramdisk, keepVerity: Boolean, keepForceEncrypt: Boolean): Result {
        val result = Result(0)
        if (keepVerity && keepForceEncrypt) return result

        for (entry in ramdisk.entries) {
            if (entry.isDir || entry.isSymlink) continue
            val name = entry.name.substringAfterLast('/')
            if (!name.startsWith("fstab")) continue

            val text = String(entry.data, Charsets.UTF_8)
            val lines = text.lines()
            var changed = false
            val out = ArrayList<String>()

            for (line in lines) {
                val trimmed = line.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    out.add(line)
                    continue
                }
                var newLine = line
                if (!keepVerity) {
                    newLine = newLine.replace(Regex(""",avb_keys=[^\s,]*"""), "")
                    newLine = newLine.replace(",verify", "")
                    newLine = newLine.replace("verify,", "")
                    newLine = newLine.replace(",avb", "")
                    newLine = newLine.replace("avb,", "")
                    newLine = newLine.replace(Regex(""",\s*,+"""), ",")
                }
                if (!keepForceEncrypt) {
                    newLine = newLine.replace("forceencrypt", "encryptable")
                    newLine = newLine.replace("forcefdeorfbe", "encryptable")
                }
                if (newLine != line) changed = true
                out.add(newLine)
            }

            if (changed) {
                entry.data = out.joinToString("\n").toByteArray(Charsets.UTF_8)
                result.files++
                result.notes.add("已修补 ${entry.name}")
            }
        }
        return result
    }

    /** Appends extra kernel command line options. */
    fun appendCmdline(image: BootImage, extra: String) {
        val add = extra.trim()
        if (add.isEmpty()) return
        val current = image.cmdline.trim()
        image.cmdline = if (current.isEmpty()) add else "$current $add"
    }
}
