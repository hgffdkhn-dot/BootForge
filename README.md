# BootForge

[中文说明](README.zh-CN.md)

A boot image toolkit for Android — **unpack / analyze / inject / repack** `boot`, `init_boot`,
`recovery` and `vendor_boot` images.

Two deliverables live in this repository:

| | What it is | Where |
| --- | --- | --- |
| **CLI** | A ~60 KB static-friendly **C binary**, used exactly like `magiskboot` | `native/` |
| **App** | A native-Android-looking APK (Material 3, dynamic color), pure Kotlin, no external binaries | `app/` |

> ⚠️ Flashing a partition is destructive. **Always back up the partition first.**
> The app keeps every imported original under its private `bootforge/work/` directory.

---

## CLI: how to invoke it

### Get a binary

Prebuilt binaries come from the `bootforge-cli` artifact of every GitHub Actions run:

| File | Target |
| --- | --- |
| `bootforge-linux-x86_64` | x86_64 Linux host |
| `bootforge-android-aarch64` | **most Android phones (use this one)** |
| `bootforge-android-armv7` | older 32-bit devices |
| `bootforge-android-aarch64-static` | static build, fallback only |

Or build it yourself — it takes a few seconds:

```bash
cd native
make                 # host build (build/bootforge)
make static          # host build, statically linked
make android      ANDROID_NDK_HOME=/path/to/ndk   # aarch64, dynamic (recommended)
make android-arm  ANDROID_NDK_HOME=/path/to/ndk   # armv7, dynamic
make android-static ANDROID_NDK_HOME=/path/to/ndk # aarch64, static
sh test/run.sh       # end-to-end self test
```

### Commands

```
bootforge info   <image>                        Show header and section info
bootforge unpack <image> [-o dir]               Extract kernel/ramdisk/dtb, unpack ramdisk
bootforge repack <image> -o out.img [opts]      Repack the image
bootforge inject <image> -o out.img file=path[:mode] [more...]
bootforge patch  <image> -o out.img [opts]      Strip dm-verity / forceencrypt
```

| Option | Meaning |
| --- | --- |
| `--ramdisk <file>` | Replace the ramdisk with the given cpio (or archive) |
| `--gzip` / `--lz4` / `--lz4-frame` / `--none` | Set ramdisk compression (default: follow source) |
| `--keep-verity` | Keep dm-verity / avb flags (stripped by default) |
| `--keep-forceencrypt` | Keep `forceencrypt` (rewritten to `encryptable` by default) |
| `--cmdline "..."` | Append to the kernel command line |
| `--no-patch` | Do not patch fstab, just repack |

Run `bootforge --help` for the full text; all output is in English.

### On a phone

```bash
adb push bootforge-android-aarch64 /data/local/tmp/bootforge
adb shell chmod 755 /data/local/tmp/bootforge

# read-only, safe to run against a live partition
adb shell /data/local/tmp/bootforge info /dev/block/by-name/boot
```

Typical workflow — strip dm-verity:

```bash
# 1. back up first
adb shell su -c "dd if=/dev/block/by-name/boot of=/sdcard/boot-stock.img"

# 2. produce a patched image (never point -o at a partition)
adb shell /data/local/tmp/bootforge patch /dev/block/by-name/boot \
    -o /sdcard/boot-patched.img --lz4

# 3. flash it back
adb shell su -c "dd if=/sdcard/boot-patched.img of=/dev/block/by-name/boot"
# or: fastboot flash boot boot-patched.img
```

Inject a file into the ramdisk:

```bash
adb shell /data/local/tmp/bootforge inject /dev/block/by-name/boot \
    -o /sdcard/boot-patched.img \
    /sdcard/mytool=system/bin/mytool:0755
```

`unpack` writes `kernel`, `ramdisk.cpio`, `ramdisk/` (full tree, symlinks and modes
preserved), `dtb.img`, … into the target directory.

### On a PC

```bash
adb pull /sdcard/boot-stock.img
./bootforge-linux-x86_64 patch boot-stock.img -o boot-patched.img
fastboot flash boot boot-patched.img
```

### Two things that will bite you

**Verified boot.** On AVB devices, patching `boot` alone leaves you stuck at the boot
animation. Also disable verification on `vbmeta`:

```bash
fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
```

**`init_boot`.** On Android 13+ many devices keep the ramdisk in `init_boot`, not `boot`.
If `info` reports a 0-byte ramdisk, run it against `init_boot` instead.

### Error: `TLS segment is underaligned`

```
"bootforge": executable's TLS segment is underaligned: alignment is 8 (skew 0),
needs to be at least 64 for ARM64 Bionic
```

Not a code bug — bionic requires arm64 **static** executables to have a TLS segment
aligned to at least 64 bytes, and lld only emits 8. Fixes:

1. Use the **dynamic** build (`bootforge-android-aarch64`). It is loaded by
   `/system/bin/linker64`, which skips that check; the phone's own bionic is enough.
2. Or process the image on a PC with `bootforge-linux-x86_64` and `fastboot flash` it.

### Why C and not Kotlin/Native

Kotlin/Native has to pull a ~1 GB `konan` toolchain through an ivy repo on every build,
and 1.9.x ships no aarch64 prebuilt — it broke CI five times in a row. In C:

- it compiles in **seconds** and the binary is **~60 KB**;
- the NDK clang produces something you can `adb push` and run, exactly like `magiskboot`;
- **zero dependencies** — LZ4, DEFLATE/gzip, SHA-1 and cpio are all hand-written, no zlib;
- sections are read lazily by offset+size, so a 100 MB image never blows up memory.

---

## App

Install the APK and work top to bottom:

1. **Image** tab → pick an image (SAF picker; `.img`, payload-extracted raw images, …).
2. It is analyzed automatically; every field is listed. `vendor_boot` lets you switch
   between ramdisk fragments.
3. **Image** tab → **Unpack** to dump contents to `bootforge/out/<name>_unpacked/`.
4. **Inject** tab → add files → set the target path (`overlay.d/sbin/mytool`,
   `system/bin/xx`) → mode defaults to `0755` → inject and repack.
5. Back on **Image** → **Export** the result → optionally **Flash** to
   `boot` / `init_boot` / `vendor_boot` / `recovery` (needs root).

Flashing shows a **confirmation dialog** first, listing partition, file name and size,
and warning that it is irreversible, that you must have a backup, and that AVB devices
need separate vbmeta handling.

### Features

| | |
| --- | --- |
| Analyze | header v0–v4, page size, kernel / ramdisk / second / recovery_dtbo / dtb / signature, cmdline, OS version and patch level, DTB model / compatible |
| vendor_boot | recognizes `VNDRBOOT`, parses the vendor ramdisk table, lists platform / recovery / dlkm fragments |
| Unpack | kernel, ramdisk (raw archive + cpio + extracted tree), second, dtbo, dtb, bootconfig, vendor fragments |
| Inject | write files anywhere in the ramdisk with an octal mode (default `0755`); parent dirs are created, existing files replaced |
| Patch | strip dm-verity / avb from fstab, rewrite `forceencrypt` → `encryptable`, append kernel cmdline |
| Repack | rebuild at the source header version; ramdisk as gzip / lz4(legacy) / lz4(frame) / raw; sizes and SHA-1 id recomputed |
| Root | back up a partition, `dd` the result back |
| Log | every operation is recorded, copyable and clearable |

Supported: boot / recovery / init_boot header v0–v4, vendor_boot header v3–v4;
ramdisk compression gzip, lz4 legacy frame, lz4 standard frame, raw cpio
(xz / bzip2 are detected and reported but not repacked).

---

## Build

### GitHub Actions (recommended)

Push to GitHub and `.github/workflows/build.yml` builds with JDK 17 + Gradle 8.9:

- `BootForge-debug.apk`
- `bootforge-cli` (the four CLI binaries above)

Tagging (`git tag v1.0.0 && git push --tags`) also publishes a Release with the artifacts
attached.

Optional signing: set the repository secrets `KEYSTORE_BASE64` (`base64 -w0 your.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — the workflow then signs with
`apksigner` and emits `BootForge-release-signed.apk`.

### Locally

```bash
gradle :app:assembleDebug          # needs JDK 17 + Gradle 8.9
# or just open the project root in Android Studio
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

---

## Large images and memory (>90 MB is fine)

The image is never loaded whole: only the 4 KB header is read, and kernel / ramdisk / dtb
are streamed by offset, with repacking done read-as-you-write. The app also declares
`android:largeHeap`. Three extra guards target big images:

1. The file list renders at most 300 rows, carrying only name / mode / size — file contents
   never reach the UI layer, so a ramdisk with tens of thousands of entries cannot stall
   the main thread.
2. A ramdisk larger than a quarter of available memory (capped at 96 MB) is **not parsed**;
   the UI says so and injection switches to append mode.
3. **Injection appends by default**: stream-decompress the original ramdisk → concatenate a
   small cpio holding only the new files → stream-compress to a temp file. The kernel already
   parses concatenated cpio archives, so the result is identical to in-place injection while
   memory stays at "new file + 1 MB buffer". A full rebuild only happens when you delete
   ramdisk entries or operate on vendor_boot fragments.

---

## Known limitations

- Repacking follows the source compression by default; if a device is picky about the lz4
  frame variant, switch to **gzip** (always supported by the kernel).
- A boot header v4 signature cannot be regenerated; it is preserved as-is (affects VTS
  checks only, not normal boot).
- Flashing is plain `dd` — vbmeta is not touched. On verified-boot devices run
  `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img` yourself.
- xz / bzip2 ramdisk images are reported as unsupported and are not repacked.

## Repo layout

```
native/                        # pure C CLI, built the magiskboot way
├── Makefile                   # host gcc / NDK cross compilation
├── src/bootforge.h            # shared declarations
├── src/bootimg.c              # boot / vendor_boot parse + rebuild (v0-v4)
├── src/lz4.c  gzip.c          # hand-written compression codecs
├── src/sha1   cpio.c          # hand-written hash + cpio newc
├── src/patcher.c              # fstab dm-verity / forceencrypt patching
├── src/main.c                 # CLI entry point
└── test/                      # end-to-end self test

app/src/main/java/com/bootforge/
├── BootForgeApp.kt            # app entry, Material You dynamic color
├── core/                      # Kotlin implementation mirroring native/
│   ├── BootImage.kt           # boot / vendor_boot parse + rebuild (v0-v4)
│   ├── Cpio.kt                # cpio newc read/write
│   ├── Lz4.kt                 # LZ4 block codec + legacy / standard frames
│   ├── Compress.kt            # ramdisk format detection and conversion
│   ├── Ramdisk.kt             # initramfs container (add / remove / modify / extract)
│   ├── Dtb.kt                 # minimal DTB / DTBO parsing
│   ├── Patcher.kt             # fstab dm-verity / forceencrypt patching
│   ├── Root.kt                # su execution, partition backup and flashing
│   └── Bytes.kt               # little-endian helpers
├── vm/WorkViewModel.kt        # all business logic and state
├── ui/                        # MainActivity + 3 fragments + adapters
└── util/LogBus.kt             # logging
```

## License

MIT
