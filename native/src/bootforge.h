#ifndef BOOTFORGE_H
#define BOOTFORGE_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#define BF_VERSION "1.0.0"

/* ------------------------------------------------------------------ 通用 */

void *xmalloc(size_t n);
void *xrealloc(void *p, size_t n);

/* 可增长字节缓冲区 */
typedef struct {
    uint8_t *data;
    size_t len;
    size_t cap;
} buf_t;

void buf_init(buf_t *b, size_t cap);
void buf_free(buf_t *b);
void buf_putc(buf_t *b, int c);
void buf_append(buf_t *b, const void *p, size_t n);
void buf_reserve(buf_t *b, size_t extra);

/* 读整个文件；失败返回 NULL 并把大小置 0 */
uint8_t *read_file(const char *path, size_t *out_len);
int write_file(const char *path, const void *data, size_t len);

/* ------------------------------------------------------------------ SHA-1 */

typedef struct {
    uint32_t h[5];
    uint64_t total;
    uint8_t block[64];
    size_t block_len;
} sha1_ctx;

void sha1_init(sha1_ctx *c);
void sha1_update(sha1_ctx *c, const void *data, size_t len);
void sha1_final(sha1_ctx *c, uint8_t out[20]);
void sha1_bytes(const void *data, size_t len, uint8_t out[20]);

/* ------------------------------------------------------------------- LZ4 */

/* 块级编解码 */
uint8_t *lz4_decompress_block(const uint8_t *src, size_t src_len, size_t *out_len);
uint8_t *lz4_compress_block(const uint8_t *src, size_t src_len, size_t *out_len);

/* 容器（legacy frame / standard frame） */
uint8_t *lz4_compress_legacy(const uint8_t *src, size_t src_len, size_t *out_len);
uint8_t *lz4_compress_frame(const uint8_t *src, size_t src_len, size_t *out_len);
uint8_t *lz4_decompress_legacy(const uint8_t *src, size_t src_len, size_t *out_len);
uint8_t *lz4_decompress_frame(const uint8_t *src, size_t src_len, size_t *out_len);

/* ------------------------------------------------------------------ GZIP */

uint8_t *gzip_compress(const uint8_t *src, size_t src_len, size_t *out_len);
uint8_t *gzip_decompress(const uint8_t *src, size_t src_len, size_t *out_len);
int gzip_is(const uint8_t *d, size_t n);

/* ------------------------------------------------------------------- cpio */

typedef struct {
    char *name;
    uint32_t mode;
    uint32_t uid;
    uint32_t gid;
    uint32_t nlink;
    uint32_t mtime;
    uint32_t filesize;
    uint8_t *data;   /* 文件/plain 链接的内容 */
} cpio_entry;

typedef struct {
    cpio_entry *v;
    size_t n;
    size_t cap;
} cpio_t;

void cpio_init(cpio_t *a);
void cpio_free(cpio_t *a);
int cpio_parse(const uint8_t *raw, size_t len, cpio_t *out);
uint8_t *cpio_build(const cpio_t *a, size_t *out_len);
int cpio_is_magic(const uint8_t *d, size_t n);

cpio_entry *cpio_find(cpio_t *a, const char *name);
int cpio_remove(cpio_t *a, const char *name);
void cpio_add(cpio_t *a, const char *path, const uint8_t *data, size_t len, uint32_t mode);
void cpio_add_dir(cpio_t *a, const char *path, uint32_t mode);

#define CPIO_IS_DIR(e)      (((e)->mode & 0170000) == 0040000)
#define CPIO_IS_SYMLINK(e)  (((e)->mode & 0170000) == 0120000)

/* 把归档解开到目录 */
int cpio_extract(const cpio_t *a, const char *dir);

/* ---------------------------------------------------------- 压缩格式枚举 */

typedef enum {
    FMT_AUTO = 0,
    FMT_GZIP,
    FMT_LZ4,
    FMT_LZ4_FRAME,
    FMT_NONE
} fmt_t;

const char *fmt_name(fmt_t f);
fmt_t fmt_detect(const uint8_t *d, size_t n);
uint8_t *fmt_decompress(const uint8_t *d, size_t n, fmt_t f, size_t *out_len);
uint8_t *fmt_compress(const uint8_t *d, size_t n, fmt_t f, size_t *out_len);

/* --------------------------------------------------------------- bootimg */

#define BOOT_MAGIC   "ANDROID!"
#define VENDOR_MAGIC "VNDRBOOT"
#define MAGIC_LEN    8

#define HDR_V0 1632
#define HDR_V1 1648
#define HDR_V2 1660
#define HDR_V3 1580
#define HDR_V4 1584
#define VENDOR_HDR_V3 2112
#define VENDOR_HDR_V4 2128
#define VENDOR_TABLE_ENTRY 108

typedef enum {
    P_KERNEL = 0,
    P_RAMDISK,
    P_SECOND,
    P_DTBO,
    P_DTB,
    P_SIGNATURE,
    P_VENDOR_TABLE,
    P_BOOTCONFIG,
    P_COUNT
} part_t;

typedef struct {
    char name[32];
    uint32_t type;
    uint8_t *data;
    size_t size;
    uint32_t board[16];
} vendor_frag;

typedef struct {
    /* 源：文件映射或已读入内存 */
    uint8_t *raw;        /* 若非 NULL，表示整份镜像已在内存 */
    size_t raw_len;
    const char *path;    /* 否则按路径惰性读取 */

    int is_vendor;
    int header_version;
    uint32_t page_size;
    uint64_t magic_offset;
    uint64_t header_size_field;

    uint64_t kernel_addr;
    uint64_t ramdisk_addr;
    uint64_t second_addr;
    uint64_t tags_addr;
    uint64_t os_version;
    char board_name[32];
    char cmdline[2048];
    uint64_t recovery_dtbo_offset;
    uint64_t dtb_addr;

    uint64_t vendor_ramdisk_size;
    uint32_t table_entry_num;
    uint32_t table_entry_size;

    /* 各段 */
    uint64_t off[P_COUNT];
    uint64_t size[P_COUNT];
    uint8_t *override[P_COUNT];      /* 内存覆盖（拥有所有权） */
    size_t override_len[P_COUNT];
    char *override_path[P_COUNT];    /* 文件覆盖 */

    vendor_frag *frags;
    size_t nfrag;
} boot_image;

/* 解析 / 释放 */
int boot_parse(boot_image *img, const char *path);
void boot_free(boot_image *img);
int boot_is_vendor(const boot_image *img);

/* 段的当前大小（考虑覆盖） */
uint64_t boot_size_of(const boot_image *img, part_t p);
int boot_has(const boot_image *img, part_t p);

/* 读取某段到内存；小段才用 */
uint8_t *boot_read_part(boot_image *img, part_t p, size_t *out_len);
uint8_t *boot_peek_part(boot_image *img, part_t p, size_t n, size_t *out_len);

void boot_set_part(boot_image *img, part_t p, uint8_t *data, size_t len); /* 接管所有权 */
void boot_set_part_file(boot_image *img, part_t p, const char *path);

int boot_load_frags(boot_image *img);
int boot_extract_parts(const boot_image *img, const char *dir);
int boot_pack(const boot_image *img, const char *out_path);
uint64_t boot_packed_size(const boot_image *img);

/* ---------------------------------------------------------------- fstab */

typedef struct {
    int files;
    char notes[64][256];
    int nnote;
} patch_result;

patch_result fstab_patch(cpio_t *a, int keep_verity, int keep_forceencrypt);
int fstab_append_cmdline(boot_image *img, const char *extra);

/* ------------------------------------------------------------------ misc */

void die(const char *fmt, ...);
uint64_t align_up(uint64_t v, uint64_t a);
uint32_t rd32(const uint8_t *p);
uint64_t rd64(const uint8_t *p);
void wr32(uint8_t *p, uint32_t v);
void wr64(uint8_t *p, uint64_t v);
int mkdir_p(const char *path);
const char *part_filename(part_t p);

#endif /* BOOTFORGE_H */
