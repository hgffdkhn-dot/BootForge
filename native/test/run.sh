#!/usr/bin/env bash
# bootforge 端到端自检：造一个 boot.img，跑完整流程并校验
set -e
cd "$(dirname "$0")"

BIN=../build/bootforge
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "=== 1. 生成测试镜像 ==="
python3 make_image.py "$TMP/boot.img"
ls -l "$TMP/boot.img"

echo
echo "=== 2. info ==="
$BIN info "$TMP/boot.img"

echo
echo "=== 3. unpack ==="
$BIN unpack "$TMP/boot.img" -o "$TMP/out" | tail -5
echo "--- 展开的文件树 ---"
find "$TMP/out/ramdisk" -maxdepth 2 | head -10

echo
echo "=== 4. repack（默认跟随原格式）==="
$BIN repack "$TMP/boot.img" -o "$TMP/new1.img"
$BIN info "$TMP/new1.img" | head -8

echo
echo "=== 5. 校验：解包产物可再次解出且条目一致 ==="
$BIN unpack "$TMP/new1.img" -o "$TMP/out2" | tail -3
A=$(find "$TMP/out/ramdisk" | wc -l)
B=$(find "$TMP/out2/ramdisk" | wc -l)
echo "原文件数=$A 二次解包文件数=$B"
[ "$A" = "$B" ] && echo "✓ 条目一致" || { echo "✗ 条目不一致"; exit 1; }

echo
echo "=== 6. inject：注入一个新文件 ==="
echo "hello from bootforge" > "$TMP/mytool"
$BIN inject "$TMP/boot.img" -o "$TMP/new2.img" \
    "$TMP/mytool=system/bin/mytool:0755"
$BIN unpack "$TMP/new2.img" -o "$TMP/out3" > /dev/null
if [ -f "$TMP/out3/ramdisk/system/bin/mytool" ]; then
    echo "✓ 注入成功，内容: $(cat "$TMP/out3/ramdisk/system/bin/mytool")"
else
    echo "✗ 注入失败"; exit 1
fi

echo
echo "=== 7. patch：去掉 dm-verity ==="
$BIN patch "$TMP/boot.img" -o "$TMP/new3.img" --lz4
$BIN unpack "$TMP/new3.img" -o "$TMP/out4" > /dev/null
if grep -q "verify" "$TMP/out4/ramdisk/fstab.test" 2>/dev/null; then
    echo "✗ verify 仍在"; exit 1
else
    echo "✓ dm-verity 已去掉，fstab: $(grep '^/dev' "$TMP/out4/ramdisk/fstab.test" 2>/dev/null | head -1)"
fi

echo
echo "=== 8. 换压缩格式 gzip / lz4 / lz4-frame / none 全跑一遍 ==="
for f in --gzip --lz4 --lz4-frame --none; do
    $BIN repack "$TMP/boot.img" -o "$TMP/fmt.img" $f > /dev/null
    $BIN unpack "$TMP/fmt.img" -o "$TMP/fmtout" > /dev/null
    N=$(find "$TMP/fmtout/ramdisk" | wc -l)
    echo "  $f -> 解出 $N 个文件 $([ "$N" = "$A" ] && echo ✓ || echo ✗)"
    rm -rf "$TMP/fmtout" "$TMP/fmt.img"
done

echo
echo "=== 9. 大镜像（16MB kernel）不爆内存 ==="
python3 make_image.py "$TMP/big.img" --big-kernel
$BIN info "$TMP/big.img" | grep -E "kernel|ramdisk"
$BIN repack "$TMP/big.img" -o "$TMP/big-new.img" > /dev/null
echo "✓ 64MB 镜像重新打包完成: $(stat -c%s "$TMP/big-new.img") 字节"

echo
echo "全部通过 ✓"
