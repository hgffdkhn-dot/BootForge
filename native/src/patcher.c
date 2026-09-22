#include "bootforge.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------ fstab 修补 */

/* 在 haystack 的前 len 字节里做不区分大小写的子串查找 */
static const char *memcase(const char *hay, size_t len, const char *needle) {
    size_t nl = strlen(needle);
    if (nl == 0 || len < nl) return NULL;
    for (size_t i = 0; i + nl <= len; i++) {
        size_t j = 0;
        for (; j < nl; j++) {
            char a = hay[i + j], b = needle[j];
            if (a >= 'A' && a <= 'Z') a = (char)(a + 32);
            if (b >= 'A' && b <= 'Z') b = (char)(b + 32);
            if (a != b) break;
        }
        if (j == nl) return hay + i;
    }
    return NULL;
}

static void note(patch_result *r, const char *fmt, ...) {
    if (r->nnote >= 64) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(r->notes[r->nnote], sizeof(r->notes[0]), fmt, ap);
    va_end(ap);
    r->nnote++;
}

/* 去掉一个挂载选项；返回新分配的字符串 */
static char *strip_opt(const char *opts, const char *key) {
    size_t olen = strlen(opts), klen = strlen(key);
    char *out = xmalloc(olen + 1);
    size_t on = 0;
    size_t i = 0;
    int changed = 0;
    while (i <= olen) {
        size_t j = i;
        while (j < olen && opts[j] != ',') j++;
        size_t seg_len = j - i;
        if (seg_len == klen && memcase(opts + i, seg_len, key) &&
            memcmp(opts + i, key, klen) == 0) {
            changed = 1;
        } else {
            if (on && on + 1 < olen + 1) out[on++] = ',';
            memcpy(out + on, opts + i, seg_len);
            on += seg_len;
        }
        i = j + 1;
        if (j >= olen) break;
    }
    out[on] = 0;
    if (!changed) { free(out); return NULL; }
    return out;
}

static int has_word(const char *opts, const char *key) {
    size_t olen = strlen(opts), klen = strlen(key);
    size_t i = 0;
    while (i <= olen) {
        size_t j = i;
        while (j < olen && opts[j] != ',') j++;
        if (j - i == klen && memcmp(opts + i, key, klen) == 0) return 1;
        i = j + 1;
        if (j >= olen) break;
    }
    return 0;
}

patch_result fstab_patch(cpio_t *a, int keep_verity, int keep_forceencrypt) {
    patch_result r;
    memset(&r, 0, sizeof(r));

    static const char *FSTAB_NAMES[] = {
        "fstab.", "etc/fstab.", "system/etc/fstab.",
        "vendor/etc/fstab.", "first_stage_ramdisk/fstab.", NULL
    };

    for (size_t i = 0; i < a->n; i++) {
        cpio_entry *e = &a->v[i];
        if (CPIO_IS_DIR(e) || CPIO_IS_SYMLINK(e)) continue;
        const char *base = strrchr(e->name, '/');
        base = base ? base + 1 : e->name;
        if (strncmp(base, "fstab.", 6) != 0) continue;

        if (!e->filesize) continue;
        char *text = xmalloc(e->filesize + 1);
        memcpy(text, e->data, e->filesize);
        text[e->filesize] = 0;

        buf_t nb;
        buf_init(&nb, e->filesize + 256);
        int changed = 0;

        size_t pos = 0;
        while (pos <= e->filesize) {
            size_t j = pos;
            while (j < e->filesize && text[j] != '\n') j++;
            size_t line_len = j - pos;
            char line[4096];
            if (line_len >= sizeof(line)) line_len = sizeof(line) - 1;
            memcpy(line, text + pos, line_len);
            line[line_len] = 0;

            char out_line[4096];
            snprintf(out_line, sizeof(out_line), "%s", line);

            /* 只处理非注释的挂载行 */
            const char *trimmed = line;
            while (*trimmed == ' ' || *trimmed == '\t') trimmed++;
            int is_mount = (*trimmed && *trimmed != '#');

            if (is_mount) {
                /* 拆分：dev mountpoint type opts rest */
                char dev[256] = {0}, mnt[256] = {0}, fstype[64] = {0}, opts[1024] = {0}, rest[512] = {0};
                int n = sscanf(line, "%255s %255s %63s %1023s %511[^\n]",
                               dev, mnt, fstype, opts, rest);
                if (n >= 4) {
                    char new_opts[1024];
                    snprintf(new_opts, sizeof(new_opts), "%s", opts);

                    if (!keep_verity) {
                        char *s = strip_opt(new_opts, "avb");
                        if (s) { snprintf(new_opts, sizeof(new_opts), "%s", s); free(s); changed = 1; }
                        s = strip_opt(new_opts, "avb_keys");
                        if (s) { snprintf(new_opts, sizeof(new_opts), "%s", s); free(s); changed = 1; }
                        s = strip_opt(new_opts, "verify");
                        if (s) { snprintf(new_opts, sizeof(new_opts), "%s", s); free(s); changed = 1; }
                        s = strip_opt(new_opts, "verifyatboot");
                        if (s) { snprintf(new_opts, sizeof(new_opts), "%s", s); free(s); changed = 1; }
                    }
                    if (!keep_forceencrypt) {
                        if (has_word(new_opts, "forceencrypt")) {
                            char *s = strip_opt(new_opts, "forceencrypt");
                            if (s) {
                                snprintf(new_opts, sizeof(new_opts), "%s,encryptable", s);
                                free(s);
                                changed = 1;
                            }
                        } else if (has_word(new_opts, "forcefdeorfbe")) {
                            char *s = strip_opt(new_opts, "forcefdeorfbe");
                            if (s) {
                                snprintf(new_opts, sizeof(new_opts), "%s,encryptable", s);
                                free(s);
                                changed = 1;
                            }
                        }
                    }
                    char new_rest[512];
                    snprintf(new_rest, sizeof(new_rest), "%s", rest);
                    if (!keep_verity && n >= 5 && rest[0]) {
                        char *s2 = strip_opt(new_rest, "verify");
                        if (s2) { snprintf(new_rest, sizeof(new_rest), "%s", s2); free(s2); changed = 1; }
                        s2 = strip_opt(new_rest, "verifyatboot");
                        if (s2) { snprintf(new_rest, sizeof(new_rest), "%s", s2); free(s2); changed = 1; }
                    }
                    if (n >= 5 && new_rest[0])
                        snprintf(out_line, sizeof(out_line), "%s %s %s %s %s", dev, mnt, fstype, new_opts, new_rest);
                    else
                        snprintf(out_line, sizeof(out_line), "%s %s %s %s", dev, mnt, fstype, new_opts);
                }
            }

            buf_append(&nb, out_line, strlen(out_line));
            if (j < e->filesize) buf_putc(&nb, '\n');
            pos = j + 1;
            if (j >= e->filesize) break;
        }

        free(text);
        if (changed) {
            free(e->data);
            e->data = xmalloc(nb.len + 1);
            memcpy(e->data, nb.data, nb.len);
            e->data[nb.len] = 0;
            e->filesize = (uint32_t)nb.len;
            r.files++;
            note(&r, "修补 %s", e->name);
        }
        buf_free(&nb);
    }
    (void)FSTAB_NAMES;
    return r;
}

/* ------------------------------------------------------------ cmdline */

int fstab_append_cmdline(boot_image *img, const char *extra) {
    if (!extra || !*extra) return 0;
    size_t cur = strlen(img->cmdline);
    size_t add = strlen(extra);
    if (cur + add + 2 >= sizeof(img->cmdline)) return -1;
    if (cur && img->cmdline[cur - 1] != ' ') strncat(img->cmdline, " ", sizeof(img->cmdline) - cur - 1);
    strncat(img->cmdline, extra, sizeof(img->cmdline) - strlen(img->cmdline) - 1);
    return 0;
}
