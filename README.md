# BootForge

一款原生 Android 风格的启动镜像工具箱：**解包 / 分析 / 注入 / 重新打包** boot、init_boot、recovery、vendor_boot 镜像，纯 Kotlin 实现，不依赖任何二进制工具（magiskboot / mkbootimg 都不需要）。

> ⚠️ 刷写分区属于高危操作，务必先「备份分区」。任何修改前的原始镜像都会留在应用私有目录 `bootforge/work/` 下。

## 功能

| 能力 | 说明 |
| --- | --- |
| 镜像分析 | 头版本 v0–v4、页大小、kernel / ramdisk / second / recovery_dtbo / dtb / signature、cmdline、系统版本与安全补丁级别、DTB 的 model / compatible |
| vendor_boot | 识别 `VNDRBOOT`，解析 vendor ramdisk 表，逐个片段列出 platform / recovery / dlkm |
| 解包 | 导出 kernel、ramdisk（原始压缩包 + cpio + 解开的目录树）、second、dtbo、dtb、bootconfig、vendor ramdisk 片段 |
| 注入文件 | 把一个 / 多个文件写进 ramdisk 任意路径（可指定八进制权限，默认 0755），自动补齐父目录，支持替换已有文件 |
| 修补 | 一键去掉 fstab 里的 dm-verity / avb 校验，把 forceencrypt 改成 encryptable；可追加内核命令行 |
| 重新打包 | 按原头版本重建镜像，ramdisk 支持 gzip / lz4(legacy) / lz4(frame) / 不压缩，自动重算 size 与 SHA-1 id |
| root 相关 | 备份分区、把产物 dd 到指定分区（需要 root） |
| 日志 | 全程操作留痕，可复制 / 清空 |

支持格式：boot / recovery / init_boot 的 header v0–v4，vendor_boot header v3–v4；ramdisk 压缩支持 gzip、lz4 legacy 帧、lz4 标准帧、未压缩 cpio（xz / bzip2 暂不支持，会在分析时给出提示）。

## 使用流程

1. **镜像** 页 → 选择镜像（SAF 文件选择器，支持 `.img`、payload 提取出来的裸镜像等）。
2. 自动完成分析，信息卡片列出全部字段；vendor_boot 可在「ramdisk 目标」里切换片段。
3. 需要看内容 → **解包**（产物在应用私有目录 `bootforge/out/<镜像名>_unpacked/`）。
4. **注入** 页 → 添加文件 → 填目标路径（如 `overlay.d/sbin/mytool`、`system/bin/xx`）→ 权限默认 `0755` → 注入并重新打包。
5. 回到 **镜像** 页 → **导出产物**（保存到你指定的目录）→ 可选 **刷入分区**（填 boot / init_boot / vendor_boot / recovery，需要 root）。

## 编译

### GitHub Actions（推荐，零本地环境）

推送到 GitHub 后 `.github/workflows/build.yml` 会自动用 JDK 17 + Gradle 8.9 构建，产出：

- `BootForge-debug.apk`
- `BootForge-release-unsigned.apk`

在 Actions 运行记录里直接下载即可。打 tag（`git tag v1.0.0 && git push --tags`）还会自动创建 Release 并把 APK 挂上去。

可选签名：在仓库 Settings → Secrets 里配置 `KEYSTORE_BASE64`（`base64 -w0 your.jks`）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，工作流会自动用 `apksigner` 签名并输出 `BootForge-release-signed.apk`。

### 本地

```bash
# 需要 JDK 17 与 Gradle 8.9（仓库不含 wrapper jar，首次执行生成一次即可）
gradle wrapper --gradle-version 8.9   # 可选，生成 ./gradlew
gradle :app:assembleDebug
# 或直接用 Android Studio 打开根目录
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 目录结构

```
app/src/main/java/com/bootforge/
├── BootForgeApp.kt            # 应用入口，启用 Material You 动态取色
├── core/
│   ├── BootImage.kt           # boot / vendor_boot 解析与重建（v0–v4）
│   ├── Cpio.kt                # cpio newc 归档读写
│   ├── Lz4.kt                 # LZ4 块编解码 + legacy / 标准帧容器
│   ├── Compress.kt            # ramdisk 压缩格式识别与转换
│   ├── Ramdisk.kt             # initramfs 容器（增 / 删 / 改 / 解包到目录）
│   ├── Dtb.kt                 # DTB / DTBO 简要解析
│   ├── Patcher.kt             # fstab dm-verity / forceencrypt 修补
│   ├── Root.kt                # su 执行、分区备份与刷写
│   └── Bytes.kt               # 小端读写辅助
├── vm/WorkViewModel.kt        # 全部业务逻辑与状态
├── ui/                        # MainActivity + 三个页面 + 适配器
└── util/LogBus.kt             # 日志
```

## 大镜像与内存

镜像不再整体载入内存：只读取 4 KB 头部，kernel / ramdisk / dtb 等段按偏移量**流式**读写，重新打包也是边读边写。同时应用声明了 `android:largeHeap`。因此 64 MB 以上的 boot / init_boot 也能正常导入。

真正会占用内存的是 ramdisk 本身（解压后的 cpio 条目），若极端精简的设备上仍报内存不足，日志会给出提示，此时建议只做「重新打包 + 修补」，避免一次性解包整个文件树。

## 已知限制

- ramdisk 重新打包默认「跟随原镜像」压缩格式；若设备对 lz4 帧格式挑剔，可在压缩选项里改成 **gzip**（内核必然支持）。
- boot header v4 的 boot signature 无法重新生成，重新打包时会原样保留（仅影响 VTS 校验，不影响正常启动）。
- 刷写只做 `dd`，不会帮你处理 vbmeta：若设备启用了校验启动，请先自行 `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img`。
- xz / bzip2 压缩的 ramdisk 目前只提示不支持，不参与重新打包。

## 许可

MIT
