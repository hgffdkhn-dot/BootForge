#include "bootforge.h"
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------ 块级：解码 */

#define MIN_MATCH 4

uint8_t *lz4_decompress_block(const uint8_t *src, size_t src_len, size_t *out_len) {
    size_t cap = src_len * 3 + 1024;
    uint8_t *out = xmalloc(cap);
    size_t len = 0, i = 0;

    while (i < src_len) {
        uint32_t token = src[i++];
        size_t lit = token >> 4;
        if (lit == 15) {
            uint32_t b;
            do { b = src[i++]; lit += b; } while (b == 255 && i < src_len);
        }
        if (len + lit > cap) {
            while (len + lit > cap) cap *= 2;
            out = xrealloc(out, cap);
        }
        if (i + lit > src_len) lit = src_len - i;
        memcpy(out + len, src + i, lit);
        len += lit;
        i += lit;
        if (i >= src_len) break;

        if (i + 1 >= src_len) break;
        size_t offset = src[i] | ((size_t)src[i + 1] << 8);
        i += 2;
        size_t mlen = token & 0x0F;
        if (mlen == 15) {
            uint32_t b;
            do { b = src[i++]; mlen += b; } while (b == 255 && i < src_len);
        }
        mlen += MIN_MATCH;
        if (offset == 0 || offset > len) break;
        if (len + mlen > cap) {
            while (len + mlen > cap) cap *= 2;
            out = xrealloc(out, cap);
        }
        size_t start = len - offset;
        if (offset >= mlen) {
            memcpy(out + len, out + start, mlen);
            len += mlen;
        } else {
            /* 重叠：逐字节复制 */
            for (size_t k = 0; k < mlen; k++) out[len + k] = out[start + k];
            len += mlen;
        }
    }
    *out_len = len;
    return out;
}

/* ------------------------------------------------------ 块级：编码 */

#define MFLIMIT 12
#define MAX_DISTANCE 65535
#define LZ4_HASH_BITS 16
#define LZ4_HASH_SIZE (1 << LZ4_HASH_BITS)

static uint32_t lz4_hash(const uint8_t *p) {
    uint32_t a = p[0], b = p[1], c = p[2], d = p[3];
    uint32_t v = ((a << 8) | b) * 2654435761u ^ ((c << 4) | d);
    return (v >> (32 - LZ4_HASH_BITS)) & (LZ4_HASH_SIZE - 1);
}

static void put_len(buf_t *b, int v) {
    while (v >= 255) { buf_putc(b, 255); v -= 255; }
    buf_putc(b, v);
}

uint8_t *lz4_compress_block(const uint8_t *src, size_t src_len, size_t *out_len) {
    buf_t b;
    buf_init(&b, src_len / 2 + 64);

    /* 表存 pos+1，0 表示空 */
    uint32_t *table = xmalloc(LZ4_HASH_SIZE * sizeof(uint32_t));
    memset(table, 0, LZ4_HASH_SIZE * sizeof(uint32_t));

    size_t anchor = 0, i = 0;
    size_t limit = src_len > MFLIMIT ? src_len - MFLIMIT : 0;

    while (i < limit) {
        uint32_t key = lz4_hash(src + i);
        uint32_t slot = table[key];
        table[key] = (uint32_t)(i + 1);
        if (slot == 0) { i++; continue; }
        size_t cand = slot - 1;
        if (i - cand > MAX_DISTANCE) { i++; continue; }
        if (memcmp(src + cand, src + i, 4) != 0) { i++; continue; }

        size_t mlen = 4;
        while (i + mlen < src_len && src[cand + mlen] == src[i + mlen]) mlen++;

        size_t lit_len = i - anchor;
        size_t match_code = mlen - MIN_MATCH;
        buf_putc(&b, (int)((lit_len < 15 ? lit_len : 15) << 4) | (match_code < 15 ? match_code : 15));
        if (lit_len >= 15) put_len(&b, (int)(lit_len - 15));
        if (lit_len) buf_append(&b, src + anchor, lit_len);
        size_t dist = i - cand;
        buf_putc(&b, (int)(dist & 0xFF));
        buf_putc(&b, (int)((dist >> 8) & 0xFF));
        if (match_code >= 15) put_len(&b, (int)(match_code - 15));
        i += mlen;
        anchor = i;
    }

    size_t lit_len = src_len - anchor;
    buf_putc(&b, (int)((lit_len < 15 ? lit_len : 15) << 4));
    if (lit_len >= 15) put_len(&b, (int)(lit_len - 15));
    if (lit_len) buf_append(&b, src + anchor, lit_len);

    free(table);
    *out_len = b.len;
    return b.data;
}

/* ------------------------------------------------------ 容器 */

#define LZ4_BLOCK (1u << 20)
#define LEGACY_MAGIC 0x184C2102u
#define FRAME_MAGIC 0x184D2204u

static void put_le32(buf_t *b, uint32_t v) {
    buf_putc(b, (int)(v & 0xFF));
    buf_putc(b, (int)((v >> 8) & 0xFF));
    buf_putc(b, (int)((v >> 16) & 0xFF));
    buf_putc(b, (int)((v >> 24) & 0xFF));
}

uint8_t *lz4_compress_legacy(const uint8_t *src, size_t src_len, size_t *out_len) {
    buf_t b;
    buf_init(&b, src_len / 2 + 64);
    put_le32(&b, LEGACY_MAGIC);
    size_t pos = 0;
    while (pos < src_len) {
        size_t n = src_len - pos < LZ4_BLOCK ? src_len - pos : LZ4_BLOCK;
        size_t blen;
        uint8_t *block = lz4_compress_block(src + pos, n, &blen);
        put_le32(&b, (uint32_t)blen);
        buf_append(&b, block, blen);
        free(block);
        pos += n;
    }
    *out_len = b.len;
    return b.data;
}

uint8_t *lz4_compress_frame(const uint8_t *src, size_t src_len, size_t *out_len) {
    buf_t b;
    buf_init(&b, src_len / 2 + 64);
    put_le32(&b, FRAME_MAGIC);
    buf_putc(&b, (1 << 6) | (1 << 5)); /* version 01 + block independent */
    buf_putc(&b, 6 << 4);              /* 1MB max block */
    buf_putc(&b, 0);                   /* header checksum */
    size_t pos = 0;
    while (pos < src_len) {
        size_t n = src_len - pos < LZ4_BLOCK ? src_len - pos : LZ4_BLOCK;
        size_t blen;
        uint8_t *block = lz4_compress_block(src + pos, n, &blen);
        if (blen >= n) {
            put_le32(&b, 0x80000000u | (uint32_t)n);
            buf_append(&b, src + pos, n);
        } else {
            put_le32(&b, (uint32_t)blen);
            buf_append(&b, block, blen);
        }
        free(block);
        pos += n;
    }
    put_le32(&b, 0); /* end mark */
    *out_len = b.len;
    return b.data;
}

uint8_t *lz4_decompress_legacy(const uint8_t *src, size_t src_len, size_t *out_len) {
    buf_t b;
    buf_init(&b, src_len * 2 + 64);
    size_t i = 4; /* skip magic */
    while (i + 4 <= src_len) {
        uint32_t raw = rd32(src + i);
        i += 4;
        if (raw == 0) break;
        if (raw & 0x80000000u) {
            size_t n = raw & 0x7FFFFFFFu;
            if (i + n > src_len) n = src_len - i;
            buf_append(&b, src + i, n);
            i += n;
        } else {
            size_t n = raw;
            if (i + n > src_len) break;
            size_t dlen;
            uint8_t *d = lz4_decompress_block(src + i, n, &dlen);
            buf_append(&b, d, dlen);
            free(d);
            i += n;
        }
    }
    *out_len = b.len;
    return b.data;
}

uint8_t *lz4_decompress_frame(const uint8_t *src, size_t src_len, size_t *out_len) {
    buf_t b;
    buf_init(&b, src_len * 2 + 64);
    if (src_len < 7) { *out_len = 0; return b.data; }
    uint8_t flg = src[4];
    size_t i = 6;
    if (flg & 0x08) i += 8;
    if (flg & 0x01) i += 4;
    i += 1; /* header checksum */
    int block_crc = (flg & 0x10) != 0;
    while (i + 4 <= src_len) {
        uint32_t bs = rd32(src + i);
        i += 4;
        if (bs == 0) break;
        int compressed = !(bs & 0x80000000u);
        size_t n = bs & 0x7FFFFFFFu;
        if (i + n > src_len) n = src_len - i;
        if (compressed) {
            size_t dlen;
            uint8_t *d = lz4_decompress_block(src + i, n, &dlen);
            buf_append(&b, d, dlen);
            free(d);
        } else {
            buf_append(&b, src + i, n);
        }
        i += n;
        if (block_crc) i += 4;
    }
    *out_len = b.len;
    return b.data;
}
