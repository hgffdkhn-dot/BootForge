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

   点「刷入分区」会先弹出**二次确认弹窗**，列出目标分区、镜像名与大小，并提示操作不可撤销、需先备份、以及 AVB 校验启动需另行处理 vbmeta，确认后才会真正执行。

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

## 命令行工具 bootforge（CLI）

`native/` 是一个**纯 C 实现**，用法对齐 magiskboot：

```bash
bootforge info   boot.img                       # 分析头部 / 各段 / cmdline / 补丁级别
bootforge unpack boot.img -o boot_out           # 解出 kernel、ramdisk（含展开的文件树）、dtb 等
bootforge repack boot.img -o new.img --lz4      # 重新打包，可换压缩格式、补 cmdline
bootforge inject boot.img -o new.img mytool=system/bin/mytool:0755
bootforge patch  boot.img -o new.img            # 去掉 dm-verity / 强制加密
```

**为什么是 C 而不是 Kotlin/Native**：Kotlin/Native 每次构建要先从 JetBrains 下载约 1 GB 的
konan 工具链，且 1.9.x 没有 aarch64 版本的预编译包，在 CI 上非常脆弱。改成 C 之后：

- 编译**几十秒**完成，二进制只有 **60 KB**；
- 用 Android NDK 的 clang **静态链接**，产出可直接 `adb push` 到手机执行（跟 magiskboot 完全一样的路子）；
- 零依赖：LZ4、GZIP/DEFLATE、SHA-1、cpio 全部手写，不链接 zlib；
- 镜像按 offset+size 惰性读取，100 MB 的 boot.img 也不会吃满内存。

### 本地构建

```bash
cd native
make              # 用系统 gcc，产出 build/bootforge
make static       # 静态链接
make android ANDROID_NDK_HOME=/path/to/ndk   # aarch64 动态二进制（推荐）
make android-arm ANDROID_NDK_HOME=/path/to/ndk
sh test/run.sh    # 端到端自检：造镜像 → info → unpack → repack → inject → patch
```

### 从 GitHub Actions 获取

`bootforge-cli` 这个 artifact 里有三个二进制：

- `bootforge-linux-x86_64`（x64 Linux 主机）
- `bootforge-android-aarch64`（绝大多数安卓手机，**首选这个**）
- `bootforge-android-armv7`（老设备）
- `bootforge-android-aarch64-static`（静态版，备选）

手机上用法：

```bash
adb push bootforge-android-aarch64 /data/local/tmp/bootforge
adb shell chmod 755 /data/local/tmp/bootforge
adb shell /data/local/tmp/bootforge info /dev/block/by-name/boot
```

### 报错：TLS segment is underaligned

如果运行静态版出现：

```
"bootforge": executable's TLS segment is underaligned: alignment is 8 (skew 0),
needs to be at least 64 for ARM64 Bionic
```

这是 **Android bionic 对 arm64 静态可执行文件的限制**：它要求 TLS 段对齐至少 64 字节，
而 lld 给静态可执行文件只排了 8。跟代码无关，换链接方式即可：

1. **改用动态版** `bootforge-android-aarch64`（推荐）——由 `/system/bin/linker64` 加载，
   不走这条检查，手机上自带 bionic 所以完全够用；
2. 或者在电脑上用 `bootforge-linux-x86_64` 处理镜像，再 `fastboot flash` 刷回去。

本地构建时对应 `make android`（动态）与 `make android-static`（静态）两个目标。

## 目录结构

```
native/                        # 纯 C 的命令行工具（magiskboot 式）
├── Makefile                   # 本地 gcc / NDK 交叉编译
├── src/bootforge.h            # 公共声明
├── src/bootimg.c              # boot / vendor_boot 解析与重建（v0–v4）
├── src/lz4.c / gzip.c         # 自实现的压缩编解码
├── src/sha1 / cpio.c          # 自实现的哈希与 cpio newc
├── src/patcher.c              # fstab 去 dm-verity / 强制加密
├── src/main.c                 # CLI 入口
└── test/                      # 端到端自检

app/src/main/java/com/bootforge/
├── BootForgeApp.kt            # 应用入口，启用 Material You 动态取色
├── core/                      # Android 侧的等价实现（与 core/ 同逻辑，暂未合并）
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

## 大镜像与内存（>90 MB 也不会卡）

镜像不再整体载入内存：只读取 4 KB 头部，kernel / ramdisk / dtb 等段按偏移量**流式**读写，重新打包也是边读边写。同时应用声明了 `android:largeHeap`。因此 64 MB 以上的 boot / init_boot 也能正常导入。

另外做了三层保护，专门针对大镜像：

1. **文件列表最多渲染 300 条**，且只传递文件名/权限/大小（不把文件内容带进 UI 层）。上万条目的 ramdisk 不会再把主线程拖死。
2. **ramdisk 超过可用内存的 1/4（上限 96 MB）就不解析**，界面提示"已跳过解析"，注入改走**追加模式**。
3. **注入默认走追加模式**：原 ramdisk 流式解压 → 尾部拼接一个只含新文件的 cpio → 流式压缩写回临时文件。内核本来就支持串联解析多个 cpio，效果等同于塞进 ramdisk，而内存占用只有「新文件大小 + 1 MB 缓冲」。只有当你删除过 ramdisk 条目（或操作 vendor_boot 片段）时才退化为整包重建。

LZ4 编解码也做了提速：解压时对不重叠的匹配段用 `System.arraycopy` 批量拷贝，压缩用定长 `IntArray` 哈希表 + 自建 `Sink`，避免 `ByteArrayOutputStream.write(Int)` 的开销。


## 构建加速与日志可见性

CI 里有几个设置专门针对"慢"和"看不到进度"：

- **去掉 `--no-daemon`**：正是它导致 Gradle 打印 `single-use Daemon process will be forked` 并另起一次性守护进程。改用常驻守护进程后，同一 job 内的多次调用可复用编译缓存。
- **`--console=plain`**：无 TTY 时禁用进度条转义序列，日志变成一行行任务名（`:app:compileDebugKotlin` 等），Actions 里能实时看到进度。同时 `gradle.properties` 里也设了 `org.gradle.console=plain`。
- **一次调用打两个包**：`$GRADLE :app:assembleDebug :app:assembleRelease`，只经历一次配置阶段，比两次调用明显更快。
- **缓存 Kotlin/Native 工具链**（`~/.konan`，约 1 GB），省掉每次重新下载的几分钟。
- **`org.gradle.parallel` + `org.gradle.caching`**，并把 Gradle 堆从 2 GB 提到 4 GB。
- **x86_64 与 aarch64 在同一个 job 里交叉编译产出**：Kotlin/Native 本身就是交叉编译器，在 x64 主机上直接链接 linuxArm64 二进制，既不需要 ARM runner 也不需要 QEMU，只经历一次配置阶段、共用一份 konan 工具链。

> 不要在 ARM runner（`ubuntu-24.04-arm`）上构建：Kotlin 1.9.x 没有发布 `kotlin-native-prebuilt-linux-aarch64`，会直接报 `Could not find :kotlin-native-prebuilt-linux-aarch64`。交叉编译是唯一且更快的路子。

## 已知限制

- ramdisk 重新打包默认「跟随原镜像」压缩格式；若设备对 lz4 帧格式挑剔，可在压缩选项里改成 **gzip**（内核必然支持）。
- boot header v4 的 boot signature 无法重新生成，重新打包时会原样保留（仅影响 VTS 校验，不影响正常启动）。
- 刷写只做 `dd`，不会帮你处理 vbmeta：若设备启用了校验启动，请先自行 `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img`。
- xz / bzip2 压缩的 ramdisk 目前只提示不支持，不参与重新打包。

## 许可

MIT
