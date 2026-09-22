#include "bootforge.h"
#include <stdlib.h>
#include <string.h>

/* ==================================================================== */
/*  纯 C 的 DEFLATE / GZIP                                              */
/*  压缩：LZ77 + 固定 Huffman                                            */
/*  解压：stored / 固定 Huffman / 动态 Huffman                           */
/* ==================================================================== */

static const unsigned short LEN_BASE[29] = {
    3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,
    35,43,51,59,67,83,99,115,131,163,195,227,258
};
static const unsigned char LEN_EXTRA[29] = {
    0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,
    3,3,3,3,4,4,4,4,5,5,5,5,0
};
static const unsigned short DIST_BASE[30] = {
    1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,
    257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577
};
static const unsigned char DIST_EXTRA[30] = {
    0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13
};
static const int DYN_ORDER[19] = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};

/* ------------------------------------------------------- 位流 */

typedef struct {
    uint8_t *buf;
    size_t len, cap;
    unsigned acc;
    int bits;
} bw_t;

static void bw_init(bw_t *w, size_t cap) {
    w->cap = cap ? cap : 1024;
    w->buf = xmalloc(w->cap);
    w->len = 0; w->acc = 0; w->bits = 0;
}

static void bw_need(bw_t *w, size_t extra) {
    if (w->len + extra <= w->cap) return;
    size_t cap = w->cap;
    while (cap < w->len + extra) cap *= 2;
    w->buf = xrealloc(w->buf, cap);
    w->cap = cap;
}

/* LSB-first 写 count 位 */
static void bw_bits(bw_t *w, unsigned value, int count) {
    for (int i = 0; i < count; i++) {
        w->acc |= ((value >> i) & 1u) << w->bits;
        w->bits++;
        if (w->bits == 8) {
            bw_need(w, 1);
            w->buf[w->len++] = (uint8_t)(w->acc & 0xFF);
            w->acc = 0; w->bits = 0;
        }
    }
}

static void bw_align(bw_t *w) {
    if (w->bits) {
        bw_need(w, 1);
        w->buf[w->len++] = (uint8_t)(w->acc & 0xFF);
        w->acc = 0; w->bits = 0;
    }
}

static void bw_raw(bw_t *w, const uint8_t *p, size_t n) {
    bw_align(w);
    bw_need(w, n);
    memcpy(w->buf + w->len, p, n);
    w->len += n;
}

typedef struct {
    const uint8_t *data;
    size_t len, pos;
    unsigned acc;
    int bits;
} br_t;

static void br_init(br_t *r, const uint8_t *d, size_t n) {
    r->data = d; r->len = n; r->pos = 0; r->acc = 0; r->bits = 0;
}

static int br_bit(br_t *r) {
    if (r->bits == 0) {
        if (r->pos >= r->len) return 0;
        r->acc = r->data[r->pos++];
        r->bits = 8;
    }
    int b = r->acc & 1;
    r->acc >>= 1; r->bits--;
    return b;
}

static unsigned br_bits(br_t *r, int count) {
    unsigned v = 0;
    for (int i = 0; i < count; i++) v |= (unsigned)br_bit(r) << i;
    return v;
}

static void br_align(br_t *r) { r->acc = 0; r->bits = 0; }

/* ------------------------------------------------------- Huffman */

typedef struct {
    unsigned short counts[16];
    unsigned short *symbols;   /* 按规范顺序排列的符号表 */
    int nsym;
} huff_t;

static void huff_build(huff_t *h, const unsigned char *lengths, int n) {
    memset(h->counts, 0, sizeof(h->counts));
    int total = 0;
    for (int i = 0; i < n; i++) if (lengths[i]) { h->counts[lengths[i]]++; total++; }
    h->nsym = total;
    h->symbols = xmalloc((total ? total : 1) * sizeof(unsigned short));
    unsigned short offsets[16];
    offsets[0] = 0;
    for (int l = 1; l < 16; l++) offsets[l] = offsets[l - 1] + h->counts[l - 1];
    unsigned short cursor[16];
    memcpy(cursor, offsets, sizeof(cursor));
    for (int s = 0; s < n; s++) {
        unsigned char l = lengths[s];
        if (l) h->symbols[cursor[l]++] = (unsigned short)s;
    }
}

static void huff_free(huff_t *h) { free(h->symbols); h->symbols = NULL; }

static int huff_decode(const huff_t *h, br_t *r) {
    unsigned code = 0, first = 0, index = 0;
    for (int len = 1; len <= 15; len++) {
        code |= (unsigned)br_bit(r);
        unsigned count = h->counts[len];
        if (code - first < count) return h->symbols[index + (code - first)];
        index += count;
        first = (first + count) << 1;
        code <<= 1;
    }
    die("损坏的 Huffman 编码");
    return -1;
}

/* 固定 Huffman 的规范码（已按 deflate 要求位反转） */
static unsigned short LIT_CODE[288];
static unsigned char LIT_LEN[288];
static unsigned short DIST_CODE[32];
static unsigned char DIST_LEN[32];
static int fixed_ready = 0;
static huff_t FIXED_LIT, FIXED_DIST;

static void canonical(const unsigned char *lengths, int n,
                      unsigned short *codes, unsigned char *lens) {
    unsigned short counts[16];
    memset(counts, 0, sizeof(counts));
    for (int i = 0; i < n; i++) if (lengths[i]) counts[lengths[i]]++;
    unsigned short next[16];
    memset(next, 0, sizeof(next));
    unsigned code = 0;
    for (int b = 1; b <= 15; b++) {
        code = (unsigned)((code + counts[b - 1]) << 1);
        next[b] = code;
    }
    for (int s = 0; s < n; s++) {
        unsigned char l = lengths[s];
        lens[s] = l;
        if (l) {
            unsigned c = next[l]++;
            unsigned r = 0;
            for (int i = 0; i < l; i++) r = (r << 1) | ((c >> i) & 1u);
            codes[s] = (unsigned short)r;
        } else {
            codes[s] = 0;
        }
    }
}

static void fixed_init(void) {
    if (fixed_ready) return;
    unsigned char lit[288], dist[32];
    for (int i = 0; i < 144; i++) lit[i] = 8;
    for (int i = 144; i < 256; i++) lit[i] = 9;
    for (int i = 256; i < 280; i++) lit[i] = 7;
    for (int i = 280; i < 288; i++) lit[i] = 8;
    for (int i = 0; i < 32; i++) dist[i] = 5;
    canonical(lit, 288, LIT_CODE, LIT_LEN);
    canonical(dist, 32, DIST_CODE, DIST_LEN);
    huff_build(&FIXED_LIT, lit, 288);
    huff_build(&FIXED_DIST, dist, 32);
    fixed_ready = 1;
}

/* ------------------------------------------------------- 压缩 */

static unsigned hash4(const uint8_t *p) {
    unsigned a = p[0], b = p[1], c = p[2], d = p[3];
    return (((a << 8) | b) * 2654435761u) ^ ((c << 4) | d);
}

static int len_code(size_t length, int *extra, int *extra_bits) {
    for (int i = 28; i >= 0; i--) {
        if (length >= LEN_BASE[i]) {
            *extra = (int)(length - LEN_BASE[i]);
            *extra_bits = LEN_EXTRA[i];
            return 257 + i;
        }
    }
    *extra = 0; *extra_bits = 0;
    return 257;
}

static int dist_code(size_t distance, int *extra, int *extra_bits) {
    for (int i = 29; i >= 0; i--) {
        if (distance >= DIST_BASE[i]) {
            *extra = (int)(distance - DIST_BASE[i]);
            *extra_bits = DIST_EXTRA[i];
            return i;
        }
    }
    *extra = 0; *extra_bits = 0;
    return 0;
}

#define DEF_HASH_BITS 15
#define DEF_HASH_SIZE (1 << DEF_HASH_BITS)

static uint8_t *deflate_raw(const uint8_t *src, size_t n, size_t *out_len) {
    fixed_init();
    bw_t w;
    bw_init(&w, n / 4 + 64);

    if (n < 64) {
        bw_bits(&w, 1, 1);   /* BFINAL */
        bw_bits(&w, 0, 2);   /* stored */
        bw_align(&w);
        uint8_t hdr[4];
        hdr[0] = (uint8_t)(n & 0xFF);
        hdr[1] = (uint8_t)((n >> 8) & 0xFF);
        hdr[2] = (uint8_t)((~n) & 0xFF);
        hdr[3] = (uint8_t)(((~n) >> 8) & 0xFF);
        bw_raw(&w, hdr, 4);
        bw_raw(&w, src, n);
        *out_len = w.len;
        return w.buf;
    }

    bw_bits(&w, 1, 1);  /* BFINAL */
    bw_bits(&w, 1, 2);  /* 固定 Huffman */

    int *table = xmalloc(DEF_HASH_SIZE * sizeof(int));
    for (int i = 0; i < DEF_HASH_SIZE; i++) table[i] = -1;

    size_t anchor = 0, i = 0;
    size_t limit = n > 12 ? n - 12 : 0;
    while (i < limit) {
        if (i + 4 > n) break;
        unsigned key = hash4(src + i) & (DEF_HASH_SIZE - 1);
        int cand = table[key];
        table[key] = (int)i;
        if (cand < 0 || (size_t)(i - cand) > 32768 || memcmp(src + cand, src + i, 4) != 0) {
            i++;
            continue;
        }
        size_t mlen = 4;
        while (mlen < 258 && i + mlen < n && src[cand + mlen] == src[i + mlen]) mlen++;
        for (size_t p = anchor; p < i; p++)
            bw_bits(&w, LIT_CODE[src[p]], LIT_LEN[src[p]]);
        int ex, eb;
        int lc = len_code(mlen, &ex, &eb);
        bw_bits(&w, LIT_CODE[lc], LIT_LEN[lc]);
        bw_bits(&w, (unsigned)ex, eb);
        int dc = dist_code(i - (size_t)cand, &ex, &eb);
        bw_bits(&w, DIST_CODE[dc], DIST_LEN[dc]);
        bw_bits(&w, (unsigned)ex, eb);
        i += mlen;
        anchor = i;
    }
    for (size_t p = anchor; p < n; p++)
        bw_bits(&w, LIT_CODE[src[p]], LIT_LEN[src[p]]);
    bw_bits(&w, LIT_CODE[256], LIT_LEN[256]);
    bw_align(&w);
    free(table);
    *out_len = w.len;
    return w.buf;
}

/* ------------------------------------------------------- 解压 */

static uint32_t crc32_buf(const uint8_t *d, size_t n) {
    static uint32_t tbl[256];
    static int ready = 0;
    if (!ready) {
        for (unsigned i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) ? (c >> 1) ^ 0xEDB88320u : (c >> 1);
            tbl[i] = c;
        }
        ready = 1;
    }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++) c = tbl[(c ^ d[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

static void inflate_block(br_t *r, buf_t *out, const huff_t *lh, const huff_t *dh) {
    for (;;) {
        int sym = huff_decode(lh, r);
        if (sym == 256) return;
        if (sym < 256) {
            buf_putc(out, sym);
        } else {
            int idx = sym - 257;
            if (idx < 0 || idx >= 29) die("无效长度码 %d", sym);
            size_t length = LEN_BASE[idx] + br_bits(r, LEN_EXTRA[idx]);
            int ds = huff_decode(dh, r);
            if (ds < 0 || ds >= 30) die("无效距离码 %d", ds);
            size_t distance = DIST_BASE[ds] + br_bits(r, DIST_EXTRA[ds]);
            if (distance > out->len) die("距离越界: %zu > %zu", distance, out->len);
            size_t start = out->len - distance;
            buf_reserve(out, length);
            for (size_t k = 0; k < length; k++)
                out->data[out->len++] = out->data[start + k];
        }
    }
}

static void read_dynamic(br_t *r, huff_t *lh, huff_t *dh) {
    unsigned hlit = br_bits(r, 5) + 257;
    unsigned hdist = br_bits(r, 5) + 1;
    unsigned hclen = br_bits(r, 4) + 4;
    unsigned char cl[19];
    memset(cl, 0, sizeof(cl));
    for (unsigned i = 0; i < hclen; i++) cl[DYN_ORDER[i]] = (unsigned char)br_bits(r, 3);
    huff_t clh;
    huff_build(&clh, cl, 19);

    unsigned total = hlit + hdist;
    unsigned char *lengths = xmalloc(total);
    unsigned idx = 0;
    while (idx < total) {
        int sym = huff_decode(&clh, r);
        if (sym < 16) {
            lengths[idx++] = (unsigned char)sym;
        } else if (sym == 16) {
            unsigned char prev = lengths[idx - 1];
            unsigned rep = 3 + br_bits(r, 2);
            while (rep-- && idx < total) lengths[idx++] = prev;
        } else if (sym == 17) {
            unsigned rep = 3 + br_bits(r, 3);
            while (rep-- && idx < total) lengths[idx++] = 0;
        } else {
            unsigned rep = 11 + br_bits(r, 7);
            while (rep-- && idx < total) lengths[idx++] = 0;
        }
    }
    huff_free(&clh);
    huff_build(lh, lengths, hlit);
    huff_build(dh, lengths + hlit, hdist);
    free(lengths);
}

static uint8_t *inflate_raw(const uint8_t *src, size_t n, size_t *out_len) {
    fixed_init();
    br_t r;
    br_init(&r, src, n);
    buf_t out;
    buf_init(&out, n * 3 + 64);

    for (;;) {
        int final = br_bit(&r);
        int btype = (int)br_bits(&r, 2);
        if (btype == 0) {
            br_align(&r);
            if (r.pos + 4 > r.len) break;
            size_t len = r.data[r.pos] | ((size_t)r.data[r.pos + 1] << 8);
            size_t start = r.pos + 4;
            size_t end = start + len;
            if (end > r.len) end = r.len;
            buf_append(&out, r.data + start, end - start);
            r.pos = end;
        } else if (btype == 1) {
            inflate_block(&r, &out, &FIXED_LIT, &FIXED_DIST);
        } else if (btype == 2) {
            huff_t lh, dh;
            read_dynamic(&r, &lh, &dh);
            inflate_block(&r, &out, &lh, &dh);
            huff_free(&lh);
            huff_free(&dh);
        } else {
            die("无效的 deflate 块类型 %d", btype);
        }
        if (final) break;
    }
    *out_len = out.len;
    return out.data;
}

/* ------------------------------------------------------- GZIP 包装 */

uint8_t *gzip_compress(const uint8_t *src, size_t n, size_t *out_len) {
    size_t raw_len;
    uint8_t *raw = deflate_raw(src, n, &raw_len);
    buf_t b;
    buf_init(&b, raw_len + 18);
    buf_putc(&b, 0x1F); buf_putc(&b, 0x8B); buf_putc(&b, 8); buf_putc(&b, 0);
    for (int i = 0; i < 4; i++) buf_putc(&b, 0);   /* mtime */
    buf_putc(&b, 0); buf_putc(&b, 255);            /* XFL, OS */
    buf_append(&b, raw, raw_len);
    free(raw);
    uint32_t crc = crc32_buf(src, n);
    for (int i = 0; i < 4; i++) buf_putc(&b, (int)((crc >> (8 * i)) & 0xFF));
    for (int i = 0; i < 4; i++) buf_putc(&b, (int)(((uint32_t)n >> (8 * i)) & 0xFF));
    *out_len = b.len;
    return b.data;
}

static size_t gzip_header_end(const uint8_t *d, size_t n) {
    size_t pos = 10;
    unsigned char flg = d[3];
    if (flg & 0x04) {
        if (pos + 2 > n) return n;
        size_t xlen = d[pos] | ((size_t)d[pos + 1] << 8);
        pos += 2 + xlen;
    }
    if (flg & 0x08) { while (pos < n && d[pos]) pos++; pos++; }
    if (flg & 0x10) { while (pos < n && d[pos]) pos++; pos++; }
    if (flg & 0x02) pos += 2;
    return pos > n ? n : pos;
}

uint8_t *gzip_decompress(const uint8_t *src, size_t n, size_t *out_len) {
    if (!gzip_is(src, n)) die("不是 gzip 数据");
    size_t start = gzip_header_end(src, n);
    size_t end = n > 8 ? n - 8 : start;
    if (end < start) end = start;
    return inflate_raw(src + start, end - start, out_len);
}
