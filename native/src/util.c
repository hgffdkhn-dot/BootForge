#include "bootforge.h"

#include <errno.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

void die(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fflush(stdout);
    fprintf(stderr, "error: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    exit(1);
}

void *xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) die("out of memory (need %zu bytes)", n);
    return p;
}

void *xrealloc(void *p, size_t n) {
    void *q = realloc(p, n ? n : 1);
    if (!q) die("out of memory (grow to %zu bytes)", n);
    return q;
}

uint64_t align_up(uint64_t v, uint64_t a) {
    if (a == 0) return v;
    return ((v + a - 1) / a) * a;
}

uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

uint64_t rd64(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) v |= (uint64_t)p[i] << (8 * i);
    return v;
}

void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
    p[2] = (uint8_t)((v >> 16) & 0xFF);
    p[3] = (uint8_t)((v >> 24) & 0xFF);
}

void wr64(uint8_t *p, uint64_t v) {
    for (int i = 0; i < 8; i++) p[i] = (uint8_t)((v >> (8 * i)) & 0xFF);
}

/* ----------------------------------------------------------- buf_t */

void buf_init(buf_t *b, size_t cap) {
    b->cap = cap ? cap : 256;
    b->data = xmalloc(b->cap);
    b->len = 0;
}

void buf_free(buf_t *b) {
    free(b->data);
    b->data = NULL;
    b->len = b->cap = 0;
}

void buf_reserve(buf_t *b, size_t extra) {
    if (b->len + extra <= b->cap) return;
    size_t cap = b->cap;
    while (cap < b->len + extra) cap *= 2;
    b->data = xrealloc(b->data, cap);
    b->cap = cap;
}

void buf_putc(buf_t *b, int c) {
    buf_reserve(b, 1);
    b->data[b->len++] = (uint8_t)c;
}

void buf_append(buf_t *b, const void *p, size_t n) {
    if (!n) return;
    buf_reserve(b, n);
    memcpy(b->data + b->len, p, n);
    b->len += n;
}

/* --------------------------------------------------------- file I/O */

uint8_t *read_file(const char *path, size_t *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        *out_len = 0;
        return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); *out_len = 0; return NULL; }
    long sz = ftell(f);
    if (sz < 0) { fclose(f); *out_len = 0; return NULL; }
    rewind(f);
    uint8_t *buf = xmalloc((size_t)sz + 1);
    size_t got = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    *out_len = got;
    return buf;
}

int write_file(const char *path, const void *data, size_t len) {
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    if (len && fwrite(data, 1, len, f) != len) { fclose(f); return -1; }
    fclose(f);
    return 0;
}

int mkdir_p(const char *path) {
    char tmp[4096];
    size_t n = strlen(path);
    if (n == 0 || n >= sizeof(tmp)) return -1;
    memcpy(tmp, path, n + 1);
    for (size_t i = 1; i <= n; i++) {
        if (tmp[i] == '/' || tmp[i] == '\0') {
            char c = tmp[i];
            tmp[i] = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
                struct stat st;
                if (stat(tmp, &st) != 0) return -1;
            }
            tmp[i] = c;
        }
    }
    return 0;
}

/* ----------------------------------------------------------- SHA-1 */

#define ROTL(x, n) (((x) << (n)) | ((x) >> (32 - (n))))

static void sha1_block(sha1_ctx *c, const uint8_t *p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    }
    for (int i = 16; i < 80; i++) {
        uint32_t v = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
        w[i] = ROTL(v, 1);
    }
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3], e = c->h[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20)      { f = (b & cc) | ((~b) & d); k = 0x5A827999u; }
        else if (i < 40) { f = b ^ cc ^ d;            k = 0x6ED9EBA1u; }
        else if (i < 60) { f = (b & cc) | (b & d) | (cc & d); k = 0x8F1BBCDCu; }
        else             { f = b ^ cc ^ d;            k = 0xCA62C1D6u; }
        uint32_t tmp = ROTL(a, 5) + f + e + k + w[i];
        e = d; d = cc; cc = ROTL(b, 30); b = a; a = tmp;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d; c->h[4] += e;
}

void sha1_init(sha1_ctx *c) {
    c->h[0] = 0x67452301u; c->h[1] = 0xEFCDAB89u; c->h[2] = 0x98BADCFEu;
    c->h[3] = 0x10325476u; c->h[4] = 0xC3D2E1F0u;
    c->total = 0;
    c->block_len = 0;
}

void sha1_update(sha1_ctx *c, const void *data, size_t len) {
    const uint8_t *p = data;
    c->total += len;
    if (c->block_len) {
        size_t want = 64 - c->block_len;
        size_t take = len < want ? len : want;
        memcpy(c->block + c->block_len, p, take);
        c->block_len += take;
        p += take; len -= take;
        if (c->block_len == 64) {
            sha1_block(c, c->block);
            c->block_len = 0;
        }
    }
    while (len >= 64) {
        sha1_block(c, p);
        p += 64; len -= 64;
    }
    if (len) {
        memcpy(c->block + c->block_len, p, len);
        c->block_len += len;
    }
}

void sha1_final(sha1_ctx *c, uint8_t out[20]) {
    uint64_t bits = c->total * 8;
    uint8_t pad = 0x80;
    sha1_update(c, &pad, 1);
    uint8_t zero = 0;
    while (c->block_len != 56) sha1_update(c, &zero, 1);
    uint8_t lenbuf[8];
    for (int i = 0; i < 8; i++) lenbuf[i] = (uint8_t)((bits >> (8 * (7 - i))) & 0xFF);
    sha1_update(c, lenbuf, 8);
    for (int i = 0; i < 5; i++) {
        out[i * 4]     = (uint8_t)((c->h[i] >> 24) & 0xFF);
        out[i * 4 + 1] = (uint8_t)((c->h[i] >> 16) & 0xFF);
        out[i * 4 + 2] = (uint8_t)((c->h[i] >> 8) & 0xFF);
        out[i * 4 + 3] = (uint8_t)(c->h[i] & 0xFF);
    }
}

void sha1_bytes(const void *data, size_t len, uint8_t out[20]) {
    sha1_ctx c;
    sha1_init(&c);
    sha1_update(&c, data, len);
    sha1_final(&c, out);
}

/* --------------------------------------------------------- formats */

const char *fmt_name(fmt_t f) {
    switch (f) {
        case FMT_GZIP: return "gzip";
        case FMT_LZ4: return "lz4 (legacy)";
        case FMT_LZ4_FRAME: return "lz4 (frame)";
        case FMT_NONE: return "none";
        default: return "auto (follow source)";
    }
}

int cpio_is_magic(const uint8_t *d, size_t n) {
    if (n < 6) return 0;
    return (memcmp(d, "070701", 6) == 0) || (memcmp(d, "070702", 6) == 0);
}

fmt_t fmt_detect(const uint8_t *d, size_t n) {
    if (n < 8) return FMT_NONE;
    if (d[0] == 0x1F && d[1] == 0x8B) return FMT_GZIP;
    if (d[0] == 0x02 && d[1] == 0x21 && d[2] == 0x4C && d[3] == 0x18) return FMT_LZ4;
    if (d[0] == 0x04 && d[1] == 0x22 && d[2] == 0x4D && d[3] == 0x18) return FMT_LZ4_FRAME;
    if (d[0] == 0xFD && d[1] == 0x37) die("xz compression is not supported for repacking");
    if (d[0] == 0x42 && d[1] == 0x5A && d[2] == 0x68) die("bzip2 compression is not supported for repacking");
    if (cpio_is_magic(d, n)) return FMT_NONE;
    return FMT_NONE;
}

uint8_t *fmt_decompress(const uint8_t *d, size_t n, fmt_t f, size_t *out_len) {
    if (f == FMT_AUTO) f = fmt_detect(d, n);
    switch (f) {
        case FMT_GZIP: return gzip_decompress(d, n, out_len);
        case FMT_LZ4: return lz4_decompress_legacy(d, n, out_len);
        case FMT_LZ4_FRAME: return lz4_decompress_frame(d, n, out_len);
        default: {
            uint8_t *o = xmalloc(n ? n : 1);
            memcpy(o, d, n);
            *out_len = n;
            return o;
        }
    }
}

uint8_t *fmt_compress(const uint8_t *d, size_t n, fmt_t f, size_t *out_len) {
    if (f == FMT_AUTO) f = FMT_GZIP;
    switch (f) {
        case FMT_GZIP: return gzip_compress(d, n, out_len);
        case FMT_LZ4: return lz4_compress_legacy(d, n, out_len);
        case FMT_LZ4_FRAME: return lz4_compress_frame(d, n, out_len);
        default: {
            uint8_t *o = xmalloc(n ? n : 1);
            memcpy(o, d, n);
            *out_len = n;
            return o;
        }
    }
}

int gzip_is(const uint8_t *d, size_t n) {
    return n >= 2 && d[0] == 0x1F && d[1] == 0x8B;
}

const char *part_filename(part_t p) {
    switch (p) {
        case P_KERNEL: return "kernel";
        case P_RAMDISK: return "ramdisk";
        case P_SECOND: return "second.img";
        case P_DTBO: return "recovery_dtbo.img";
        case P_DTB: return "dtb.img";
        case P_SIGNATURE: return "boot_signature";
        case P_VENDOR_TABLE: return "vendor_ramdisk_table.bin";
        case P_BOOTCONFIG: return "bootconfig";
        default: return "unknown";
    }
}
