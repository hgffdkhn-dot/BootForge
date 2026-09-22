#include "bootforge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>

#define HEADER_SIZE 110
#define TRAILER_NAME "TRAILER!!!"
#define MAGIC_NEW  "070701"
#define MAGIC_CRC  "070702"

static size_t pad4(size_t v) { return (4 - (v & 3)) & 3; }

static unsigned long hexval(const char *s, int n) {
    unsigned long v = 0;
    for (int i = 0; i < n; i++) {
        char c = s[i];
        unsigned d;
        if (c >= '0' && c <= '9') d = (unsigned)(c - '0');
        else if (c >= 'A' && c <= 'F') d = (unsigned)(c - 'A' + 10);
        else if (c >= 'a' && c <= 'f') d = (unsigned)(c - 'a' + 10);
        else d = 0;
        v = (v << 4) | d;
    }
    return v;
}

void cpio_init(cpio_t *a) { a->v = NULL; a->n = 0; a->cap = 0; }

void cpio_free(cpio_t *a) {
    for (size_t i = 0; i < a->n; i++) {
        free(a->v[i].name);
        free(a->v[i].data);
    }
    free(a->v);
    cpio_init(a);
}

int cpio_is_magic_dummy(void) { return 0; }

int cpio_parse(const uint8_t *raw, size_t len, cpio_t *out) {
    cpio_init(out);
    size_t pos = 0;
    while (pos + HEADER_SIZE <= len) {
        if (memcmp(raw + pos, MAGIC_NEW, 6) != 0 && memcmp(raw + pos, MAGIC_CRC, 6) != 0) break;
        char h[HEADER_SIZE + 1];
        memcpy(h, raw + pos, HEADER_SIZE);
        h[HEADER_SIZE] = 0;

        unsigned long namesize = hexval(h + 94, 8);
        unsigned long filesize = hexval(h + 54, 8);
        if (namesize < 1 || namesize > 4096) break;
        if (pos + HEADER_SIZE + namesize > len) break;

        const char *name = (const char *)(raw + pos + HEADER_SIZE);
        size_t name_len = namesize - 1;
        if (name_len == 10 && memcmp(name, TRAILER_NAME, 10) == 0) break;

        cpio_entry e;
        memset(&e, 0, sizeof(e));
        e.name = xmalloc(name_len + 1);
        memcpy(e.name, name, name_len);
        e.name[name_len] = 0;
        e.mode = (uint32_t)hexval(h + 14, 8);
        e.uid = (uint32_t)hexval(h + 22, 8);
        e.gid = (uint32_t)hexval(h + 30, 8);
        e.nlink = (uint32_t)hexval(h + 38, 8);
        e.mtime = (uint32_t)hexval(h + 46, 8);
        e.filesize = (uint32_t)filesize;

        size_t data_off = pos + HEADER_SIZE + namesize;
        data_off += pad4(HEADER_SIZE + namesize);
        if (data_off + filesize > len) {
            free(e.name);
            break;
        }
        e.data = NULL;
        if (filesize) {
            e.data = xmalloc(filesize + 1);
            memcpy(e.data, raw + data_off, filesize);
            e.data[filesize] = 0;
        } else {
            e.data = xmalloc(1);
            e.data[0] = 0;
        }
        pos = data_off + filesize + pad4(filesize);

        if (out->n == out->cap) {
            out->cap = out->cap ? out->cap * 2 : 64;
            out->v = xrealloc(out->v, out->cap * sizeof(cpio_entry));
        }
        out->v[out->n++] = e;
    }
    return (int)out->n;
}

static void put_hex(char *dst, unsigned long v, int width) {
    static const char *d = "0123456789ABCDEF";
    for (int i = width - 1; i >= 0; i--) { dst[i] = d[v & 0xF]; v >>= 4; }
}

static void emit_entry(buf_t *b, const char *name, uint32_t mode,
                       uint32_t nlink, uint32_t mtime,
                       const uint8_t *data, uint32_t size) {
    char h[HEADER_SIZE];
    memset(h, '0', sizeof(h));
    memcpy(h, MAGIC_NEW, 6);
    put_hex(h + 6, 0, 8);      /* ino */
    put_hex(h + 14, mode, 8);
    put_hex(h + 22, 0, 8);     /* uid */
    put_hex(h + 30, 0, 8);     /* gid */
    put_hex(h + 38, nlink, 8);
    put_hex(h + 46, mtime, 8);
    put_hex(h + 54, size, 8);
    put_hex(h + 62, 0, 8);     /* devmajor */
    put_hex(h + 70, 0, 8);     /* devminor */
    put_hex(h + 78, 0, 8);     /* rdevmajor */
    put_hex(h + 86, 0, 8);     /* rdevminor */
    size_t namesize = strlen(name) + 1;
    put_hex(h + 94, namesize, 8);
    put_hex(h + 102, 0, 8);    /* check */
    buf_append(b, h, HEADER_SIZE);
    buf_append(b, name, namesize);
    static const uint8_t zero[4] = {0, 0, 0, 0};
    buf_append(b, zero, pad4(HEADER_SIZE + namesize));
    if (size) {
        buf_append(b, data, size);
        buf_append(b, zero, pad4(size));
    }
}

uint8_t *cpio_build(const cpio_t *a, size_t *out_len) {
    buf_t b;
    buf_init(&b, 1 << 16);
    uint32_t now = (uint32_t)time(NULL);
    for (size_t i = 0; i < a->n; i++) {
        const cpio_entry *e = &a->v[i];
        uint32_t mt = e->mtime ? e->mtime : now;
        emit_entry(&b, e->name, e->mode, e->nlink ? e->nlink : 1, mt, e->data, e->filesize);
    }
    emit_entry(&b, TRAILER_NAME, 0, 1, 0, NULL, 0);
    *out_len = b.len;
    return b.data;
}

cpio_entry *cpio_find(cpio_t *a, const char *name) {
    for (size_t i = 0; i < a->n; i++)
        if (strcmp(a->v[i].name, name) == 0) return &a->v[i];
    return NULL;
}

int cpio_remove(cpio_t *a, const char *name) {
    for (size_t i = 0; i < a->n; i++) {
        if (strcmp(a->v[i].name, name) == 0) {
            free(a->v[i].name);
            free(a->v[i].data);
            memmove(&a->v[i], &a->v[i + 1], (a->n - i - 1) * sizeof(cpio_entry));
            a->n--;
            return 1;
        }
    }
    return 0;
}

static void ensure_dirs(cpio_t *a, const char *path);

void cpio_add_dir(cpio_t *a, const char *path, uint32_t mode) {
    if (!path || !*path) return;
    if (cpio_find(a, path)) return;
    if (a->n == a->cap) {
        a->cap = a->cap ? a->cap * 2 : 64;
        a->v = xrealloc(a->v, a->cap * sizeof(cpio_entry));
    }
    cpio_entry e;
    memset(&e, 0, sizeof(e));
    e.name = xmalloc(strlen(path) + 1);
    strcpy(e.name, path);
    e.mode = mode ? mode : 0040755u;
    e.nlink = 2;
    e.mtime = (uint32_t)time(NULL);
    e.data = xmalloc(1);
    e.data[0] = 0;
    e.filesize = 0;
    a->v[a->n++] = e;
}

static void ensure_dirs(cpio_t *a, const char *path) {
    char buf[4096];
    size_t n = strlen(path);
    if (n >= sizeof(buf)) return;
    memcpy(buf, path, n + 1);
    for (size_t i = 0; i < n; i++) {
        if (buf[i] == '/') {
            buf[i] = 0;
            if (buf[0]) cpio_add_dir(a, buf, 0040755u);
            buf[i] = '/';
        }
    }
}

void cpio_add(cpio_t *a, const char *path, const uint8_t *data, size_t len, uint32_t mode) {
    const char *clean = path;
    while (*clean == '/') clean++;
    if (!*clean) return;
    ensure_dirs(a, clean);

    cpio_entry *old = cpio_find(a, clean);
    if (old) {
        free(old->data);
        old->data = xmalloc(len + 1);
        memcpy(old->data, data, len);
        old->data[len] = 0;
        old->filesize = (uint32_t)len;
        old->mode = mode;
        return;
    }
    if (a->n == a->cap) {
        a->cap = a->cap ? a->cap * 2 : 64;
        a->v = xrealloc(a->v, a->cap * sizeof(cpio_entry));
    }
    cpio_entry e;
    memset(&e, 0, sizeof(e));
    e.name = xmalloc(strlen(clean) + 1);
    strcpy(e.name, clean);
    e.mode = mode;
    e.nlink = 1;
    e.mtime = (uint32_t)time(NULL);
    e.data = xmalloc(len + 1);
    memcpy(e.data, data, len);
    e.data[len] = 0;
    e.filesize = (uint32_t)len;
    a->v[a->n++] = e;
}

/* --------------------------------------------- extract to disk */

static char *path_join(const char *dir, const char *name) {
    size_t a = strlen(dir), b = strlen(name);
    char *r = xmalloc(a + b + 2);
    memcpy(r, dir, a);
    r[a] = '/';
    memcpy(r + a + 1, name, b + 1);
    return r;
}

static char *parent_of(const char *path) {
    const char *slash = strrchr(path, '/');
    if (!slash) return NULL;
    size_t n = (size_t)(slash - path);
    if (n == 0) n = 1;
    char *r = xmalloc(n + 1);
    memcpy(r, path, n);
    r[n] = 0;
    return r;
}

int cpio_extract(const cpio_t *a, const char *dir) {
    if (mkdir_p(dir) != 0) return -1;
    for (size_t i = 0; i < a->n; i++) {
        const cpio_entry *e = &a->v[i];
        char *full = path_join(dir, e->name);
        char *parent = parent_of(full);
        if (parent) { mkdir_p(parent); free(parent); }

        if (CPIO_IS_DIR(e)) {
            mkdir_p(full);
        } else if (CPIO_IS_SYMLINK(e)) {
            unlink(full);
            if (symlink((const char *)e->data, full) != 0) mkdir_p(dir);
        } else {
            FILE *f = fopen(full, "wb");
            if (f) {
                if (e->filesize) fwrite(e->data, 1, e->filesize, f);
                fclose(f);
                chmod(full, (mode_t)(e->mode & 07777));
            }
        }
        free(full);
    }
    return 0;
}
