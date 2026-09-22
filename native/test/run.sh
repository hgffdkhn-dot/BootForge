#!/usr/bin/env bash
# bootforge end-to-end self test: build a boot.img and run the whole flow
set -e
cd "$(dirname "$0")"

BIN=../build/bootforge
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "=== 1. Generate test image ==="
python3 make_image.py "$TMP/boot.img"
ls -l "$TMP/boot.img"

echo
echo "=== 2. info ==="
$BIN info "$TMP/boot.img"

echo
echo "=== 3. unpack ==="
$BIN unpack "$TMP/boot.img" -o "$TMP/out" | tail -5
echo "--- extracted tree ---"
find "$TMP/out/ramdisk" -maxdepth 2 | head -10

echo
echo "=== 4. repack (follow source format) ==="
$BIN repack "$TMP/boot.img" -o "$TMP/new1.img"
$BIN info "$TMP/new1.img" | head -8

echo
echo "=== 5. Verify: repacked image unpacks to identical entries ==="
$BIN unpack "$TMP/new1.img" -o "$TMP/out2" | tail -3
A=$(find "$TMP/out/ramdisk" | wc -l)
B=$(find "$TMP/out2/ramdisk" | wc -l)
echo "original=$A repacked=$B"
[ "$A" = "$B" ] && echo "OK entry count matches" || { echo "FAIL entry count differs"; exit 1; }

echo
echo "=== 6. inject: add a new file ==="
echo "hello from bootforge" > "$TMP/mytool"
$BIN inject "$TMP/boot.img" -o "$TMP/new2.img" \
    "$TMP/mytool=system/bin/mytool:0755"
$BIN unpack "$TMP/new2.img" -o "$TMP/out3" > /dev/null
if [ -f "$TMP/out3/ramdisk/system/bin/mytool" ]; then
    echo "OK injected, content: $(cat "$TMP/out3/ramdisk/system/bin/mytool")"
else
    echo "FAIL injection"; exit 1
fi

echo
echo "=== 7. patch: strip dm-verity ==="
$BIN patch "$TMP/boot.img" -o "$TMP/new3.img" --lz4
$BIN unpack "$TMP/new3.img" -o "$TMP/out4" > /dev/null
if grep -q "verify" "$TMP/out4/ramdisk/fstab.test" 2>/dev/null; then
    echo "FAIL verify still present"; exit 1
else
    echo "OK dm-verity stripped, fstab: $(grep '^/dev' "$TMP/out4/ramdisk/fstab.test" 2>/dev/null | head -1)"
fi

echo
echo "=== 8. Round-trip every compression format ==="
for f in --gzip --lz4 --lz4-frame --none; do
    $BIN repack "$TMP/boot.img" -o "$TMP/fmt.img" $f > /dev/null
    $BIN unpack "$TMP/fmt.img" -o "$TMP/fmtout" > /dev/null
    N=$(find "$TMP/fmtout/ramdisk" | wc -l)
    echo "  $f -> $N entries $([ "$N" = "$A" ] && echo OK || echo FAIL)"
    rm -rf "$TMP/fmtout" "$TMP/fmt.img"
done

echo
echo "=== 9. Large image (16MB kernel) without OOM ==="
python3 make_image.py "$TMP/big.img" --big-kernel
$BIN info "$TMP/big.img" | grep -E "kernel|ramdisk"
$BIN repack "$TMP/big.img" -o "$TMP/big-new.img" > /dev/null
echo "OK large image repacked: $(stat -c%s "$TMP/big-new.img") bytes"

echo
echo "ALL TESTS PASSED"
