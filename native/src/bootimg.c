#include "bootforge.h"

#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* ==================================================================== */
/*  boot / vendor_boot 镜像解析与重建                                    */
/*  除头部外，各段均按需从文件读取，避免整份镜像占满内存                  */
/* ==================================================================== */

typedef struct {
    FILE *f;
} src_t;

static int src_open(src_t *s, const char *path) {
    s->f = fopen(path, "rb");
    return s->f ? 0 : -1;
}

static void src_close(src_t *s) {
    if (s->f) { fclose(s->f); s->f = NULL; }
}

static size_t src_read(src_t *s, uint64_t off, void *dst, size_t n) {
    if (!s->f || n == 0) return 0;
    if (fseek(s->f, (long)off, SEEK_SET) != 0) return 0;
    return fread(dst, 1, n, s->f);
}

/* ------------------------------------------------------------ 生命周期 */

int boot_is_vendor(const boot_image *img) { return img->is_vendor; }

void boot_free(boot_image *img) {
    src_close((src_t *)img->raw);
    for (int i = 0; i < P_COUNT; i++) {
        free(img->override[i]);
        free(img->override_path[i]);
    }
    for (size_t i = 0; i < img->nfrag; i++) {
        free(img->frags[i].data);
    }
    free(img->frags);
    memset(img, 0, sizeof(*img));
}

uint64_t boot_size_of(const boot_image *img, part_t p) {
    if (img->override[p]) return img->override_len[p];
    if (img->override_path[p]) {
        struct stat st;
        if (stat(img->override_path[p], &st) == 0) return (uint64_t)st.st_size;
    }
    return img->size[p];
}

int boot_has(const boot_image *img, part_t p) { return boot_size_of(img, p) > 0; }

void boot_set_part(boot_image *img, part_t p, uint8_t *data, size_t len) {
    free(img->override[p]);
    free(img->override_path[p]);
    img->override_path[p] = NULL;
    img->override[p] = data;
    img->override_len[p] = len;
}

void boot_set_part_file(boot_image *img, part_t p, const char *path) {
    free(img->override[p]);
    free(img->override_path[p]);
    img->override[p] = NULL;
    img->override_len[p] = 0;
    img->override_path[p] = xmalloc(strlen(path) + 1);
    strcpy(img->override_path[p], path);
}

/* ------------------------------------------------------------ 解析 */

static uint64_t find_magic(src_t *s, uint64_t file_size, int *is_vendor) {
    uint8_t buf[65536];
    uint64_t limit = file_size - MAGIC_LEN;
    if (limit > 131072) limit = 131072;
    uint64_t i = 0;
    uint64_t buf_off = 0;
    size_t buf_len = 0;
    while (i <= limit) {
        if (i < buf_off || i + MAGIC_LEN > buf_off + buf_len) {
            buf_off = i;
            buf_len = src_read(s, i, buf, sizeof(buf));
            if (buf_len < MAGIC_LEN) break;
        }
        size_t rel = (size_t)(i - buf_off);
        if (memcmp(buf + rel, BOOT_MAGIC, MAGIC_LEN) == 0) { *is_vendor = 0; return i; }
        if (memcmp(buf + rel, VENDOR_MAGIC, MAGIC_LEN) == 0) { *is_vendor = 1; return i; }
        i++;
    }
    return (uint64_t)-1;
}

static void read_cstr(const uint8_t *h, int off, int max, char *dst, size_t cap) {
    size_t n = 0;
    for (int i = 0; i < max && n + 1 < cap; i++) {
        if (h[off + i] == 0) break;
        dst[n++] = (char)h[off + i];
    }
    dst[n] = 0;
}

static int parse_boot(boot_image *img, src_t *s, uint64_t base) {
    uint8_t h[4096];
    if (src_read(s, base, h, sizeof(h)) < MAGIC_LEN) return -1;
    if (memcmp(h, BOOT_MAGIC, MAGIC_LEN) != 0) return -1;

    int ver = (int)rd32(h + 0x28);
    if (ver > 8) ver = 0;   /* legacy 镜像此处存的是 dt_size */
    img->header_version = ver;

    if (ver >= 3) {
        img->page_size = 4096;
        img->os_version = rd32(h + 0x10);
        img->header_size_field = rd32(h + 0x14);
        read_cstr(h, 0x2C, 1536, img->cmdline, sizeof(img->cmdline));
        uint64_t pos = base + img->page_size;
        img->off[P_KERNEL] = pos;
        img->size[P_KERNEL] = rd32(h + 0x08);
        pos += align_up(img->size[P_KERNEL], img->page_size);
        img->off[P_RAMDISK] = pos;
        img->size[P_RAMDISK] = rd32(h + 0x0C);
        pos += align_up(img->size[P_RAMDISK], img->page_size);
        if (ver >= 4) {
            img->off[P_SIGNATURE] = pos;
            img->size[P_SIGNATURE] = rd32(h + 1580);
        }
    } else {
        uint32_t ps = rd32(h + 0x24);
        if (ps >= 512 && ps <= 65536 && (ps & (ps - 1)) == 0) img->page_size = ps;
        else img->page_size = 2048;
        img->kernel_addr = rd32(h + 0x0C);
        img->ramdisk_addr = rd32(h + 0x14);
        img->second_addr = rd32(h + 0x1C);
        img->tags_addr = rd32(h + 0x20);
        img->os_version = rd32(h + 0x2C);
        read_cstr(h, 0x30, 16, img->board_name, sizeof(img->board_name));
        char extra[1024];
        read_cstr(h, 0x40, 512, img->cmdline, sizeof(img->cmdline));
        read_cstr(h, 0x260, 1024, extra, sizeof(extra));
        strncat(img->cmdline, extra, sizeof(img->cmdline) - strlen(img->cmdline) - 1);

        uint64_t pos = base + img->page_size;
        img->off[P_KERNEL] = pos;
        img->size[P_KERNEL] = rd32(h + 0x08);
        pos += align_up(img->size[P_KERNEL], img->page_size);
        img->off[P_RAMDISK] = pos;
        img->size[P_RAMDISK] = rd32(h + 0x10);
        pos += align_up(img->size[P_RAMDISK], img->page_size);
        img->off[P_SECOND] = pos;
        img->size[P_SECOND] = rd32(h + 0x18);
        pos += align_up(img->size[P_SECOND], img->page_size);
        if (ver >= 1) {
            img->recovery_dtbo_offset = rd64(h + 0x664);
            img->header_size_field = rd32(h + 0x66C);
            img->off[P_DTBO] = pos;
            img->size[P_DTBO] = rd32(h + 0x660);
            pos += align_up(img->size[P_DTBO], img->page_size);
        }
        if (ver >= 2) {
            img->dtb_addr = rd64(h + 0x674);
            img->off[P_DTB] = pos;
            img->size[P_DTB] = rd32(h + 0x670);
        }
    }
    return 0;
}

static int parse_vendor(boot_image *img, src_t *s, uint64_t base) {
    uint8_t h[4096];
    if (src_read(s, base, h, sizeof(h)) < MAGIC_LEN) return -1;
    if (memcmp(h, VENDOR_MAGIC, MAGIC_LEN) != 0) return -1;

    img->header_version = (int)rd32(h + 0x08);
    uint32_t ps = rd32(h + 0x0C);
    if (ps >= 512 && ps <= 65536 && (ps & (ps - 1)) == 0) img->page_size = ps;
    else img->page_size = 2048;
    img->kernel_addr = rd32(h + 0x10);
    img->ramdisk_addr = rd32(h + 0x14);
    img->vendor_ramdisk_size = rd32(h + 0x18);
    read_cstr(h, 0x1C, 2048, img->cmdline, sizeof(img->cmdline));
    img->tags_addr = rd32(h + 0x81C);
    read_cstr(h, 0x820, 16, img->board_name, sizeof(img->board_name));
    img->header_size_field = rd32(h + 0x830);
    img->dtb_addr = rd64(h + 0x838);

    uint64_t hdr_size = img->header_version >= 4 ? VENDOR_HDR_V4 : VENDOR_HDR_V3;
    uint64_t pos = base + align_up(hdr_size, img->page_size);
    img->off[P_RAMDISK] = pos;
    img->size[P_RAMDISK] = img->vendor_ramdisk_size;
    pos += align_up(img->vendor_ramdisk_size, img->page_size);
    img->off[P_DTB] = pos;
    img->size[P_DTB] = rd32(h + 0x834);
    pos += align_up(img->size[P_DTB], img->page_size);

    if (img->header_version >= 4) {
        img->table_entry_num = rd32(h + 2116);
        img->table_entry_size = rd32(h + 2120);
        uint64_t tsize = rd32(h + 2112);
        img->off[P_VENDOR_TABLE] = pos;
        img->size[P_VENDOR_TABLE] = tsize;
        pos += align_up(tsize, img->page_size);
        img->off[P_BOOTCONFIG] = pos;
        img->size[P_BOOTCONFIG] = rd32(h + 2124);
    }
    return 0;
}

int boot_parse(boot_image *img, const char *path) {
    memset(img, 0, sizeof(*img));
    src_t s;
    if (src_open(&s, path) != 0) die("无法打开镜像: %s", path);

    struct stat st;
    if (stat(path, &st) != 0) { src_close(&s); die("无法获取大小: %s", path); }
    uint64_t file_size = (uint64_t)st.st_size;

    int is_vendor = 0;
    uint64_t magic = find_magic(&s, file_size, &is_vendor);
    if (magic == (uint64_t)-1) {
        src_close(&s);
        die("未找到 ANDROID! / VNDRBOOT 魔数，不是有效的启动镜像");
    }
    img->is_vendor = is_vendor;
    img->magic_offset = magic;

    int rc = is_vendor ? parse_vendor(img, &s, magic) : parse_boot(img, &s, magic);
    if (rc != 0) { src_close(&s); die("镜像头解析失败"); }

    /* 把 FILE* 藏进 raw 字段，由 boot_free 关闭 */
    img->raw = (uint8_t *)xmalloc(sizeof(src_t));
    memcpy(img->raw, &s, sizeof(src_t));
    return 0;
}

/* ------------------------------------------------------------ 读取段 */

static uint8_t *read_range(boot_image *img, uint64_t off, uint64_t len, size_t *out_len) {
    if (len == 0) { *out_len = 0; return NULL; }
    uint8_t *buf = xmalloc((size_t)len + 1);
    size_t got = src_read((src_t *)img->raw, off, buf, (size_t)len);
    buf[got] = 0;
    *out_len = got;
    return buf;
}

uint8_t *boot_read_part(boot_image *img, part_t p, size_t *out_len) {
    if (img->override[p]) {
        uint8_t *cp = xmalloc(img->override_len[p] + 1);
        memcpy(cp, img->override[p], img->override_len[p]);
        cp[img->override_len[p]] = 0;
        *out_len = img->override_len[p];
        return cp;
    }
    if (img->override_path[p]) {
        size_t n;
        uint8_t *d = read_file(img->override_path[p], &n);
        *out_len = d ? n : 0;
        return d;
    }
    return read_range(img, img->off[p], img->size[p], out_len);
}

uint8_t *boot_peek_part(boot_image *img, part_t p, size_t n, size_t *out_len) {
    if (img->override[p]) {
        size_t take = n < img->override_len[p] ? n : img->override_len[p];
        uint8_t *cp = xmalloc(take);
        memcpy(cp, img->override[p], take);
        *out_len = take;
        return cp;
    }
    if (img->override_path[p]) {
        FILE *f = fopen(img->override_path[p], "rb");
        if (!f) { *out_len = 0; return NULL; }
        uint8_t *buf = xmalloc(n);
        size_t got = fread(buf, 1, n, f);
        fclose(f);
        *out_len = got;
        return buf;
    }
    uint64_t len = img->size[p] < n ? img->size[p] : n;
    return read_range(img, img->off[p], len, out_len);
}

/* ------------------------------------------------------------ 片段 */

int boot_load_frags(boot_image *img) {
    if (!img->is_vendor) return 0;
    for (size_t i = 0; i < img->nfrag; i++) free(img->frags[i].data);
    free(img->frags);
    img->frags = NULL;
    img->nfrag = 0;

    size_t sec_len = 0, tbl_len = 0;
    uint8_t *section = boot_read_part(img, P_RAMDISK, &sec_len);
    uint8_t *table = boot_read_part(img, P_VENDOR_TABLE, &tbl_len);

    if (!table || tbl_len == 0) {
        if (section && sec_len) {
            img->frags = xmalloc(sizeof(vendor_frag));
            memset(img->frags, 0, sizeof(vendor_frag));
            snprintf(img->frags[0].name, sizeof(img->frags[0].name), "vendor_ramdisk");
            img->frags[0].type = 1;
            img->frags[0].data = section;
            img->frags[0].size = sec_len;
            img->nfrag = 1;
            section = NULL;
        }
        free(section);
        return 0;
    }

    size_t es = img->table_entry_size ? img->table_entry_size : VENDOR_TABLE_ENTRY;
    size_t cap = 8;
    img->frags = xmalloc(cap * sizeof(vendor_frag));
    size_t off = 0;
    while (off + es <= tbl_len) {
        uint32_t size = rd32(table + off);
        uint32_t rel = rd32(table + off + 4);
        uint32_t type = rd32(table + off + 8);
        char nm[32];
        read_cstr(table, (int)(off + 12), 32, nm, sizeof(nm));
        if (img->nfrag == cap) {
            cap *= 2;
            img->frags = xrealloc(img->frags, cap * sizeof(vendor_frag));
        }
        vendor_frag *f = &img->frags[img->nfrag];
        memset(f, 0, sizeof(*f));
        if (nm[0]) snprintf(f->name, sizeof(f->name), "%s", nm);
        else snprintf(f->name, sizeof(f->name), "ramdisk_%zu", img->nfrag);
        f->type = type;
        for (int i = 0; i < 16; i++) f->board[i] = rd32(table + off + 44 + (size_t)i * 4);
        f->size = size;
        f->data = xmalloc(size ? size : 1);
        if (section && rel + (uint64_t)size <= sec_len)
            memcpy(f->data, section + rel, size);
        img->nfrag++;
        off += es;
    }
    free(section);
    free(table);
    if (img->nfrag == 0 && sec_len) {
        img->frags = xmalloc(sizeof(vendor_frag));
        memset(img->frags, 0, sizeof(vendor_frag));
        snprintf(img->frags[0].name, sizeof(img->frags[0].name), "vendor_ramdisk");
        img->frags[0].type = 1;
        size_t n2;
        img->frags[0].data = boot_read_part(img, P_RAMDISK, &n2);
        img->frags[0].size = n2;
        img->nfrag = 1;
    }
    return 0;
}

/* ------------------------------------------------------------ 解包 */

static int dump_part(const boot_image *img, part_t p, const char *dir) {
    if (!boot_has(img, p)) return 0;
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", dir, part_filename(p));
    FILE *out = fopen(path, "wb");
    if (!out) return -1;
    int rc = 0;
    if (img->override[p]) {
        if (img->override_len[p] && fwrite(img->override[p], 1, img->override_len[p], out) != img->override_len[p]) rc = -1;
    } else if (img->override_path[p]) {
        size_t n;
        uint8_t *d = read_file(img->override_path[p], &n);
        if (!d || (n && fwrite(d, 1, n, out) != n)) rc = -1;
        free(d);
    } else {
        uint8_t buf[1 << 20];
        uint64_t remaining = img->size[p];
        uint64_t off = img->off[p];
        while (remaining) {
            size_t want = remaining < sizeof(buf) ? (size_t)remaining : sizeof(buf);
            size_t got = src_read((src_t *)img->raw, off, buf, want);
            if (!got) break;
            if (fwrite(buf, 1, got, out) != got) { rc = -1; break; }
            off += got;
            remaining -= got;
        }
    }
    fclose(out);
    return rc;
}

int boot_extract_parts(const boot_image *img, const char *dir) {
    if (mkdir_p(dir) != 0) return -1;
    for (int p = 0; p < P_COUNT; p++) {
        if (p == P_RAMDISK) continue;
        if (dump_part(img, (part_t)p, dir) != 0) return -1;
    }
    return 0;
}

/* ------------------------------------------------------------ 打包 */

static void write_part(FILE *out, const boot_image *img, part_t p, uint64_t ps) {
    uint64_t written = 0;
    if (img->override[p]) {
        if (img->override_len[p]) fwrite(img->override[p], 1, img->override_len[p], out);
        written = img->override_len[p];
    } else if (img->override_path[p]) {
        size_t n;
        uint8_t *d = read_file(img->override_path[p], &n);
        if (d && n) fwrite(d, 1, n, out);
        free(d);
        written = n;
    } else {
        uint8_t buf[1 << 20];
        uint64_t remaining = img->size[p];
        uint64_t off = img->off[p];
        while (remaining) {
            size_t want = remaining < sizeof(buf) ? (size_t)remaining : sizeof(buf);
            size_t got = src_read((src_t *)img->raw, off, buf, want);
            if (!got) break;
            fwrite(buf, 1, got, out);
            off += got;
            remaining -= got;
            written += got;
        }
    }
    if (written) {
        uint64_t rem = align_up(written, ps) - written;
        if (rem) {
            uint8_t *z = xmalloc((size_t)rem);
            memset(z, 0, (size_t)rem);
            fwrite(z, 1, (size_t)rem, out);
            free(z);
        }
    }
}

static void compute_id(const boot_image *img, uint8_t out[20]) {
    sha1_ctx c;
    sha1_init(&c);
    static const part_t order[] = {P_KERNEL, P_RAMDISK, P_SECOND, P_DTBO, P_DTB};
    for (int i = 0; i < 5; i++) {
        part_t p = order[i];
        if (img->override[p]) {
            sha1_update(&c, img->override[p], img->override_len[p]);
            continue;
        }
        uint8_t buf[1 << 20];
        uint64_t remaining = img->size[p];
        uint64_t off = img->off[p];
        while (remaining) {
            size_t want = remaining < sizeof(buf) ? (size_t)remaining : sizeof(buf);
            size_t got = src_read((src_t *)img->raw, off, buf, want);
            if (!got) break;
            sha1_update(&c, buf, got);
            off += got;
            remaining -= got;
        }
    }
    sha1_final(&c, out);
}

static void put_cstr(uint8_t *h, int off, const char *s, int max) {
    size_t n = strlen(s);
    if (n > (size_t)(max - 1)) n = (size_t)(max - 1);
    memcpy(h + off, s, n);
}

int boot_pack(const boot_image *img, const char *out_path) {
    FILE *out = fopen(out_path, "wb");
    if (!out) die("无法创建输出文件: %s", out_path);

    if (img->is_vendor) {
        uint64_t hdr_size = img->header_version >= 4 ? VENDOR_HDR_V4 : VENDOR_HDR_V3;
        uint64_t ps = img->page_size;

        /* 拼接片段或沿用原 ramdisk */
        uint8_t *section = NULL;
        size_t section_len = 0;
        if (img->nfrag) {
            size_t total = 0;
            for (size_t i = 0; i < img->nfrag; i++) total += img->frags[i].size;
            section = xmalloc(total ? total : 1);
            for (size_t i = 0; i < img->nfrag; i++) {
                if (img->frags[i].size) {
                    memcpy(section + section_len, img->frags[i].data, img->frags[i].size);
                    section_len += img->frags[i].size;
                }
            }
        } else {
            section = boot_read_part((boot_image *)img, P_RAMDISK, &section_len);
        }

        /* 重建片段表 */
        uint8_t *table = NULL;
        size_t table_len = 0;
        if (img->header_version >= 4 && img->nfrag) {
            table_len = img->nfrag * VENDOR_TABLE_ENTRY;
            table = xmalloc(table_len);
            memset(table, 0, table_len);
            size_t rel = 0;
            for (size_t i = 0; i < img->nfrag; i++) {
                uint8_t *e = table + (size_t)i * VENDOR_TABLE_ENTRY;
                wr32(e, (uint32_t)img->frags[i].size);
                wr32(e + 4, (uint32_t)rel);
                wr32(e + 8, img->frags[i].type);
                put_cstr(e, 12, img->frags[i].name, 32);
                for (int k = 0; k < 16; k++) wr32(e + 44 + (size_t)k * 4, img->frags[i].board[k]);
                rel += img->frags[i].size;
            }
        }

        size_t hdr_buf_size = (size_t)align_up(hdr_size, ps);
        uint8_t *h = xmalloc(hdr_buf_size);
        memset(h, 0, hdr_buf_size);
        memcpy(h, VENDOR_MAGIC, MAGIC_LEN);
        wr32(h + 0x08, (uint32_t)img->header_version);
        wr32(h + 0x0C, (uint32_t)ps);
        wr32(h + 0x10, (uint32_t)img->kernel_addr);
        wr32(h + 0x14, (uint32_t)img->ramdisk_addr);
        wr32(h + 0x18, (uint32_t)section_len);
        put_cstr(h, 0x1C, img->cmdline, 2048);
        wr32(h + 0x81C, (uint32_t)img->tags_addr);
        put_cstr(h, 0x820, img->board_name, 16);
        wr32(h + 0x830, (uint32_t)hdr_size);
        wr32(h + 0x834, (uint32_t)boot_size_of(img, P_DTB));
        wr64(h + 0x838, img->dtb_addr);
        if (img->header_version >= 4) {
            wr32(h + 2112, (uint32_t)table_len);
            wr32(h + 2116, (uint32_t)img->nfrag);
            wr32(h + 2120, VENDOR_TABLE_ENTRY);
            wr32(h + 2124, (uint32_t)boot_size_of(img, P_BOOTCONFIG));
        }
        fwrite(h, 1, hdr_buf_size, out);
        free(h);

        if (section_len) {
            fwrite(section, 1, section_len, out);
            uint64_t rem = align_up(section_len, ps) - section_len;
            if (rem) { uint8_t *z = xmalloc((size_t)rem); memset(z, 0, (size_t)rem); fwrite(z, 1, (size_t)rem, out); free(z); }
        }
        free(section);
        write_part(out, img, P_DTB, ps);
        if (img->header_version >= 4) {
            if (table_len) {
                fwrite(table, 1, table_len, out);
                uint64_t rem = align_up(table_len, ps) - table_len;
                if (rem) { uint8_t *z = xmalloc((size_t)rem); memset(z, 0, (size_t)rem); fwrite(z, 1, (size_t)rem, out); free(z); }
            }
            free(table);
            write_part(out, img, P_BOOTCONFIG, ps);
        }
        fclose(out);
        return 0;
    }

    if (img->header_version >= 3) {
        uint64_t ps = 4096;
        uint8_t *h = xmalloc(ps);
        memset(h, 0, (size_t)ps);
        memcpy(h, BOOT_MAGIC, MAGIC_LEN);
        wr32(h + 0x08, (uint32_t)boot_size_of(img, P_KERNEL));
        wr32(h + 0x0C, (uint32_t)boot_size_of(img, P_RAMDISK));
        wr32(h + 0x10, (uint32_t)img->os_version);
        wr32(h + 0x14, img->header_version >= 4 ? HDR_V4 : HDR_V3);
        wr32(h + 0x28, (uint32_t)img->header_version);
        put_cstr(h, 0x2C, img->cmdline, 1536);
        if (img->header_version >= 4) wr32(h + 1580, (uint32_t)boot_size_of(img, P_SIGNATURE));
        fwrite(h, 1, (size_t)ps, out);
        free(h);
        write_part(out, img, P_KERNEL, ps);
        write_part(out, img, P_RAMDISK, ps);
        if (img->header_version >= 4) write_part(out, img, P_SIGNATURE, ps);
        fclose(out);
        return 0;
    }

    uint64_t ps = img->page_size;
    if (img->header_version >= 1 && ps < HDR_V1) ps = 2048;
    if (img->header_version >= 2 && ps < HDR_V2) ps = 2048;
    uint8_t *h = xmalloc((size_t)ps);
    memset(h, 0, (size_t)ps);
    memcpy(h, BOOT_MAGIC, MAGIC_LEN);
    wr32(h + 0x08, (uint32_t)boot_size_of(img, P_KERNEL));
    wr32(h + 0x0C, (uint32_t)img->kernel_addr);
    wr32(h + 0x10, (uint32_t)boot_size_of(img, P_RAMDISK));
    wr32(h + 0x14, (uint32_t)img->ramdisk_addr);
    wr32(h + 0x18, (uint32_t)boot_size_of(img, P_SECOND));
    wr32(h + 0x1C, (uint32_t)img->second_addr);
    wr32(h + 0x20, (uint32_t)img->tags_addr);
    wr32(h + 0x24, (uint32_t)ps);
    wr32(h + 0x28, img->header_version >= 1 ? (uint32_t)img->header_version : 0);
    wr32(h + 0x2C, (uint32_t)img->os_version);
    put_cstr(h, 0x30, img->board_name, 16);

    /* cmdline 拆成两段 */
    size_t cn = strlen(img->cmdline);
    if (cn > 511 + 1023) cn = 511 + 1023;
    size_t first = cn < 511 ? cn : 511;
    if (first) memcpy(h + 0x40, img->cmdline, first);
    if (cn > first) memcpy(h + 0x260, img->cmdline + first, cn - first);

    uint8_t id[20];
    compute_id(img, id);
    memset(h + 0x240, 0, 32);
    memcpy(h + 0x240, id, 20);

    if (img->header_version >= 1) {
        wr32(h + 0x660, (uint32_t)boot_size_of(img, P_DTBO));
        wr64(h + 0x664, img->recovery_dtbo_offset);
        wr32(h + 0x66C, img->header_version >= 2 ? HDR_V2 : HDR_V1);
    }
    if (img->header_version >= 2) {
        wr32(h + 0x670, (uint32_t)boot_size_of(img, P_DTB));
        wr64(h + 0x674, img->dtb_addr);
    }
    fwrite(h, 1, (size_t)ps, out);
    free(h);
    write_part(out, img, P_KERNEL, ps);
    write_part(out, img, P_RAMDISK, ps);
    write_part(out, img, P_SECOND, ps);
    if (img->header_version >= 1) write_part(out, img, P_DTBO, ps);
    if (img->header_version >= 2) write_part(out, img, P_DTB, ps);
    fclose(out);
    return 0;
}

uint64_t boot_packed_size(const boot_image *img) {
    if (img->is_vendor) {
        uint64_t ps = img->page_size;
        uint64_t total = align_up(img->header_version >= 4 ? VENDOR_HDR_V4 : VENDOR_HDR_V3, ps);
        total += align_up(boot_size_of(img, P_RAMDISK), ps);
        total += align_up(boot_size_of(img, P_DTB), ps);
        total += align_up(boot_size_of(img, P_VENDOR_TABLE), ps);
        total += align_up(boot_size_of(img, P_BOOTCONFIG), ps);
        return total;
    }
    uint64_t ps = img->header_version >= 3 ? 4096 : img->page_size;
    uint64_t total = ps;
    total += align_up(boot_size_of(img, P_KERNEL), ps);
    total += align_up(boot_size_of(img, P_RAMDISK), ps);
    if (img->header_version >= 3) {
        total += align_up(boot_size_of(img, P_SIGNATURE), ps);
    } else {
        total += align_up(boot_size_of(img, P_SECOND), ps);
        if (img->header_version >= 1) total += align_up(boot_size_of(img, P_DTBO), ps);
        if (img->header_version >= 2) total += align_up(boot_size_of(img, P_DTB), ps);
    }
    return total;
}
