#!/usr/bin/env python3
"""Generate a valid boot.img (header v3, gzip ramdisk) for testing."""
import gzip
import struct
import sys
import os

MAGIC = b"ANDROID!"


def cpio_entry(name, mode, data=b"", nlink=1):
    def h(v, w):
        return ("%0*X" % (w, v)).encode()
    namesize = len(name) + 1
    hdr = b"070701"
    hdr += h(0, 8)          # ino
    hdr += h(mode, 8)
    hdr += h(0, 8)          # uid
    hdr += h(0, 8)          # gid
    hdr += h(nlink, 8)
    hdr += h(0, 8)          # mtime
    hdr += h(len(data), 8)
    hdr += h(0, 8) * 4      # dev/rdev
    hdr += h(namesize, 8)
    hdr += h(0, 8)          # check
    hdr = hdr + name.encode() + b"\0"
    hdr += b"\0" * ((4 - len(hdr) % 4) % 4)
    body = data + b"\0" * ((4 - len(data) % 4) % 4)
    return hdr + body


def build_cpio():
    out = b""
    # directories
    for d in ["", "system", "system/bin", "proc", "sys"]:
        if d:
            out += cpio_entry(d, 0o040755, b"", nlink=2)
    # files
    out += cpio_entry("init", 0o100755, b"#!/system/bin/sh\n# fake init\n")
    out += cpio_entry("system/bin/sh", 0o100755, b"fake shell\n")
    out += cpio_entry("default.prop", 0o100644, b"ro.debuggable=0\nro.secure=1\n")
    # fstab with dm-verity flags (the patch command must process it)
    fstab = (
        "# comment\n"
        "/dev/block/sda1 /system ext4 ro,barrier=1,verify,avb wait,verify\n"
        "/dev/block/sda2 /vendor ext4 ro,verify wait\n"
        "/dev/block/sda3 /data  f2fs nosuid,nodev,forceencrypt wait,check\n"
    )
    out += cpio_entry("fstab.test", 0o100644, fstab.encode())
    # symlink
    out += cpio_entry("sbin/sh", 0o120777, b"/system/bin/sh")
    # trailer
    out += cpio_entry("TRAILER!!!", 0)
    return out


def main():
    path = sys.argv[1]
    big = "--big-kernel" in sys.argv

    kernel_size = 16 * 1024 * 1024 if big else 64 * 1024
    kernel = os.urandom(1024) * (kernel_size // 1024)
    ramdisk_raw = build_cpio()
    ramdisk = gzip.compress(ramdisk_raw, 9)

    page = 4096

    def align(v):
        return (v + page - 1) // page * page

    # header v3: 1580 bytes, then one page
    hdr = bytearray(page)
    hdr[0:8] = MAGIC
    struct.pack_into("<I", hdr, 0x08, len(kernel))
    struct.pack_into("<I", hdr, 0x0C, len(ramdisk))
    struct.pack_into("<I", hdr, 0x10, 0)      # os_version
    struct.pack_into("<I", hdr, 0x14, 1580)   # header_size
    struct.pack_into("<I", hdr, 0x28, 3)      # header_version
    cmdline = b"androidboot.hardware=test console=ttyMSM0"
    hdr[0x2C:0x2C + len(cmdline)] = cmdline

    with open(path, "wb") as f:
        f.write(bytes(hdr))
        f.write(kernel)
        f.write(b"\0" * (align(len(kernel)) - len(kernel)))
        f.write(ramdisk)
        f.write(b"\0" * (align(len(ramdisk)) - len(ramdisk)))

    print("generated %s: kernel=%d ramdisk=%d(compressed) / %d(raw)" %
          (path, len(kernel), len(ramdisk), len(ramdisk_raw)))


if __name__ == "__main__":
    main()
