# BootForge

安卓启动镜像工具箱：**解包 / 分析 / 注入 / 重新打包** `boot`、`init_boot`、`recovery`、
`vendor_boot` 镜像。

仓库里有两个产物：

| | 是什么 | 位置 |
| --- | --- | --- |
| **CLI** | 约 60 KB 的 **纯 C 二进制**，用法和 `magiskboot` 一样 | `native/` |
| **App** | 原生 Android 风格的 APK（Material 3 动态取色），纯 Kotlin，不依赖外部二进制 | `app/` |

> 英文版说明见 [README.md](README.md)。

> ⚠️ 刷写分区属于高危操作，**务必先备份分区**。应用会把每次导入的原始镜像留在私有目录
> `bootforge/work/` 下。

---

## CLI：怎么调用

### 拿到二进制

每次 GitHub Actions 运行的 `bootforge-cli` artifact 里有预编译产物：

| 文件 | 用在 |
| --- | --- |
| `bootforge-linux-x86_64` | x86_64 Linux 主机 |
| `bootforge-android-aarch64` | **绝大多数安卓手机（选这个）** |
| `bootforge-android-armv7` | 老旧的 32 位设备 |
| `bootforge-android-aarch64-static` | 静态版，仅作备选 |

也可以自己编，几秒钟的事：

```bash
cd native
make                 # 主机构建 → build/bootforge
make static          # 主机构建，静态链接
make android      ANDROID_NDK_HOME=/path/to/ndk   # aarch64，动态（推荐）
make android-arm  ANDROID_NDK_HOME=/path/to/ndk   # armv7，动态
make android-static ANDROID_NDK_HOME=/path/to/ndk # aarch64，静态
sh test/run.sh       # 端到端自检
```

### 命令

```
bootforge info   <image>                       查看头部与各段信息
bootforge unpack <image> [-o dir]              解出 kernel/ramdisk/dtb，并展开 ramdisk
bootforge repack <image> -o out.img [opts]     重新打包
bootforge inject <image> -o out.img file=path[:mode] [更多...]
bootforge patch  <image> -o out.img [opts]     去掉 dm-verity / 强制加密
```

| 选项 | 含义 |
| --- | --- |
| `--ramdisk <file>` | 用指定的 cpio（或压缩包）替换 ramdisk |
| `--gzip` / `--lz4` / `--lz4-frame` / `--none` | 设置 ramdisk 压缩（默认跟随原镜像） |
| `--keep-verity` | 保留 dm-verity / avb 标志（默认去掉） |
| `--keep-forceencrypt` | 保留 forceencrypt（默认改成 encryptable） |
| `--cmdline "..."` | 追加内核命令行 |
| `--no-patch` | 不修补 fstab，只重新打包 |

`bootforge --help` 看完整帮助（全部输出均为英文）。

### 在手机上

```bash
adb push bootforge-android-aarch64 /data/local/tmp/bootforge
adb shell chmod 755 /data/local/tmp/bootforge

# 只读，直接对分区跑也安全
adb shell /data/local/tmp/bootforge info /dev/block/by-name/boot
```

典型流程 —— 去掉 dm-verity：

```bash
# 1. 先备份
adb shell su -c "dd if=/dev/block/by-name/boot of=/sdcard/boot-stock.img"

# 2. 生成修补后的镜像（不要把 -o 指向分区）
adb shell /data/local/tmp/bootforge patch /dev/block/by-name/boot \
    -o /sdcard/boot-patched.img --lz4

# 3. 刷回
adb shell su -c "dd if=/sdcard/boot-patched.img of=/dev/block/by-name/boot"
# 或者：fastboot flash boot boot-patched.img
```

往 ramdisk 里注入文件：

```bash
adb shell /data/local/tmp/bootforge inject /dev/block/by-name/boot \
    -o /sdcard/boot-patched.img \
    /sdcard/mytool=system/bin/mytool:0755
```

`unpack` 会在目标目录生成 `kernel`、`ramdisk.cpio`、`ramdisk/`（完整文件树，保留软链
与权限位）、`dtb.img` 等。

### 在电脑上

```bash
adb pull /sdcard/boot-stock.img
./bootforge-linux-x86_64 patch boot-stock.img -o boot-patched.img
fastboot flash boot boot-patched.img
```

### 两个必踩的坑

**校验启动**：开了 AVB 的设备，只改 boot 会卡开机动画，需要一并处理 vbmeta：

```bash
fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
```

**init_boot**：Android 13+ 很多机型把 ramdisk 放在 `init_boot` 而不是 `boot`。`info`
里看到 ramdisk 为 0 字节，就换成 `init_boot` 再试。

### 报错：TLS segment is underaligned

```
"bootforge": executable's TLS segment is underaligned: alignment is 8 (skew 0),
needs to be at least 64 for ARM64 Bionic
```

这不是代码问题：bionic 要求 arm64 的**静态**可执行文件 TLS 段对齐至少 64 字节，而 lld
只给了 8。解决办法：

1. 改用**动态版** `bootforge-android-aarch64` —— 由 `/system/bin/linker64` 加载，不走
   这条检查，手机自带的 bionic 完全够用；
2. 或者用电脑上的 `bootforge-linux-x86_64` 处理镜像再 `fastboot flash` 刷回去。

### 为什么用 C 而不是 Kotlin/Native

Kotlin/Native 每次构建都要通过 ivy 仓库拉取约 1 GB 的 konan 工具链，而且 1.9.x 没有
aarch64 的预编译包，在 CI 上连续栽了五次。改成 C 之后：

- 编译只要**几秒**，二进制仅 **约 60 KB**；
- NDK clang 产出的东西可以直接 `adb push` 到手机运行，跟 magiskboot 完全同一条路子；
- **零依赖**：LZ4、DEFLATE/gzip、SHA-1、cpio 全部手写，不链接 zlib；
- 镜像按 offset+size 惰性读取，100 MB 的镜像也不会吃满内存。

---

## App 用法

装好 APK 按标签页顺序走：

1. **镜像** 页 → 选择镜像（SAF 选择器，支持 `.img`、payload 提取的裸镜像等）。
2. 自动完成分析，信息卡片列出全部字段；vendor_boot 可切换 ramdisk 片段。
3. **镜像** 页 → **解包**，产物在 `bootforge/out/<镜像名>_unpacked/`。
4. **注入** 页 → 添加文件 → 填目标路径（如 `overlay.d/sbin/mytool`、`system/bin/xx`）
   → 权限默认 `0755` → 注入并重新打包。
5. 回到 **镜像** 页 → **导出产物** → 可选 **刷入分区**（boot / init_boot / vendor_boot /
   recovery，需要 root）。

点「刷入分区」会先弹**二次确认弹窗**，列出分区、镜像名与大小，并提示操作不可撤销、
需先备份、AVB 设备需另行处理 vbmeta。

### 功能

| | |
| --- | --- |
| 镜像分析 | 头版本 v0–v4、页大小、kernel / ramdisk / second / recovery_dtbo / dtb / signature、cmdline、系统版本与安全补丁级别、DTB 的 model / compatible |
| vendor_boot | 识别 `VNDRBOOT`，解析 vendor ramdisk 表，逐个列出 platform / recovery / dlkm 片段 |
| 解包 | kernel、ramdisk（原始压缩包 + cpio + 展开的目录树）、second、dtbo、dtb、bootconfig、vendor 片段 |
| 注入文件 | 写进 ramdisk 任意路径，可指定八进制权限（默认 0755），自动补父目录，支持替换 |
| 修补 | 去掉 fstab 的 dm-verity / avb，forceencrypt 改 encryptable，可追加内核命令行 |
| 重新打包 | 按原头版本重建，ramdisk 支持 gzip / lz4(legacy) / lz4(frame) / 不压缩，自动重算 size 与 SHA-1 id |
| root 相关 | 备份分区、把产物 dd 回分区 |
| 日志 | 全程留痕，可复制 / 清空 |

支持 boot / recovery / init_boot 的 header v0–v4、vendor_boot header v3–v4；ramdisk 压缩
支持 gzip、lz4 legacy 帧、lz4 标准帧、未压缩 cpio（xz / bzip2 会提示但不支持重新打包）。

---

## 编译

### GitHub Actions（推荐）

推到 GitHub 后 `.github/workflows/build.yml` 会用 JDK 17 + Gradle 8.9 构建，产出：

- `BootForge-debug.apk`
- `bootforge-cli`（上面那四个 CLI 二进制）

打 tag（`git tag v1.0.0 && git push --tags`）还会自动发 Release 并挂上产物。

可选签名：配置仓库 secrets `KEYSTORE_BASE64`（`base64 -w0 your.jks`）、
`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，工作流会用 `apksigner` 签名并输出
`BootForge-release-signed.apk`。

### 本地

```bash
gradle :app:assembleDebug          # 需要 JDK 17 + Gradle 8.9
# 或者直接用 Android Studio 打开项目根目录
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 大镜像与内存（>90 MB 也没问题）

镜像不再整体载入内存：只读 4 KB 头部，kernel / ramdisk / dtb 按偏移量流式读写，重新
打包边读边写，应用还声明了 `android:largeHeap`。针对大镜像另有三层保护：

1. 文件列表最多渲染 300 条，且只带文件名 / 权限 / 大小 —— 文件内容不进 UI 层，上万条目
   的 ramdisk 不会拖死主线程。
2. ramdisk 超过可用内存的 1/4（上限 96 MB）就**不解析**，界面给出提示，注入改走追加模式。
3. **注入默认走追加模式**：原 ramdisk 流式解压 → 尾部拼接只含新文件的小 cpio → 流式压缩
   写临时文件。内核本来就支持串联解析多个 cpio，效果等同于原地注入，而内存占用只有
   「新文件大小 + 1 MB 缓冲」。只有删除过 ramdisk 条目（或操作 vendor_boot 片段）时才
   退化为整包重建。

---

## 已知限制

- 重新打包默认「跟随原镜像」压缩格式；若设备对 lz4 帧格式挑剔，改成 **gzip**（内核必然支持）。
- boot header v4 的签名无法重新生成，会原样保留（只影响 VTS 校验，不影响正常启动）。
- 刷写只做 `dd`，不碰 vbmeta：校验启动设备请先自行执行
  `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img`。
- xz / bzip2 压缩的 ramdisk 目前只提示不支持，不参与重新打包。

## 目录结构

```
native/                        # 纯 C 的命令行工具（magiskboot 式）
├── Makefile                   # 主机 gcc / NDK 交叉编译
├── src/bootforge.h            # 公共声明
├── src/bootimg.c              # boot / vendor_boot 解析与重建（v0–v4）
├── src/lz4.c  gzip.c          # 自实现的压缩编解码
├── src/sha1   cpio.c          # 自实现的哈希与 cpio newc
├── src/patcher.c              # fstab 去 dm-verity / 强制加密
├── src/main.c                 # CLI 入口
└── test/                      # 端到端自检

app/src/main/java/com/bootforge/
├── BootForgeApp.kt            # 应用入口，Material You 动态取色
├── core/                      # 与 native/ 同逻辑的 Kotlin 实现
│   ├── BootImage.kt           # boot / vendor_boot 解析与重建（v0–v4）
│   ├── Cpio.kt                # cpio newc 归档读写
│   ├── Lz4.kt                 # LZ4 块编解码 + legacy / 标准帧容器
│   ├── Compress.kt            # ramdisk 压缩格式识别与转换
│   ├── Ramdisk.kt             # initramfs 容器（增 / 删 / 改 / 解包）
│   ├── Dtb.kt                 # DTB / DTBO 简要解析
│   ├── Patcher.kt             # fstab dm-verity / forceencrypt 修补
│   ├── Root.kt                # su 执行、分区备份与刷写
│   └── Bytes.kt               # 小端读写辅助
├── vm/WorkViewModel.kt        # 全部业务逻辑与状态
├── ui/                        # MainActivity + 三个页面 + 适配器
└── util/LogBus.kt             # 日志
```

## 许可

MIT
