#include "bootforge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

static void usage(void) {
    printf("\n");
    printf("bootforge %s — 安卓启动镜像工具（解包 / 分析 / 注入 / 重新打包）\n\n", BF_VERSION);
    printf("用法:\n");
    printf("  bootforge info   <镜像>                          显示镜像头部与各段信息\n");
    printf("  bootforge unpack <镜像> [-o 目录]                解出 kernel / ramdisk / dtb 等，并展开 ramdisk\n");
    printf("  bootforge repack <镜像> -o 新镜像 [选项]          重新打包\n");
    printf("  bootforge inject <镜像> -o 新镜像 文件=路径[:权限] [更多...]\n");
    printf("                                                  把文件写进 ramdisk 后重新打包\n");
    printf("  bootforge patch  <镜像> -o 新镜像 [选项]          去掉 dm-verity / 强制加密\n\n");
    printf("选项:\n");
    printf("  --ramdisk <文件>        用指定 cpio（或压缩包）替换 ramdisk\n");
    printf("  --gzip | --lz4 | --lz4-frame | --none    设置 ramdisk 压缩格式\n");
    printf("  --keep-verity           保留 dm-verity / avb 校验（默认去掉）\n");
    printf("  --keep-forceencrypt     保留强制加密（默认改成 encryptable）\n");
    printf("  --cmdline \"...\"         追加内核命令行\n");
    printf("  --no-patch              不做 fstab 修补，只重新打包\n\n");
    printf("示例:\n");
    printf("  bootforge info boot.img\n");
    printf("  bootforge unpack boot.img -o boot_out\n");
    printf("  bootforge inject boot.img -o new.img mytool=system/bin/mytool:0755\n");
    printf("  bootforge patch boot.img -o new.img --lz4\n\n");
}

/* ------------------------------------------------------------ 参数 */

typedef struct {
    fmt_t format;
    int keep_verity;
    int keep_forceencrypt;
    const char *cmdline;
    int no_patch;
    const char *ramdisk_path;
} opts_t;

static const char *get_opt(int argc, char **argv, const char *name) {
    for (int i = 0; i < argc - 1; i++)
        if (strcmp(argv[i], name) == 0) return argv[i + 1];
    return NULL;
}

static int has_flag(int argc, char **argv, const char *name) {
    for (int i = 0; i < argc; i++)
        if (strcmp(argv[i], name) == 0) return 1;
    return 0;
}

/* 第一个不以 - 开头、且不是某选项取值的参数 */
static const char *positional(int argc, char **argv) {
    for (int i = 2; i < argc; i++) {
        if (argv[i][0] == '-') continue;
        if (i > 2 && (strcmp(argv[i - 1], "-o") == 0 ||
                      strcmp(argv[i - 1], "--ramdisk") == 0 ||
                      strcmp(argv[i - 1], "--cmdline") == 0)) continue;
        if (strchr(argv[i], '=') && i > 2) continue;   /* inject 的 file=path 形式 */
        return argv[i];
    }
    return NULL;
}

static void parse_opts(int argc, char **argv, opts_t *o) {
    o->format = FMT_AUTO;
    o->keep_verity = 0;
    o->keep_forceencrypt = 0;
    o->cmdline = NULL;
    o->no_patch = 0;
    o->ramdisk_path = NULL;
    if (has_flag(argc, argv, "--gzip")) o->format = FMT_GZIP;
    if (has_flag(argc, argv, "--lz4")) o->format = FMT_LZ4;
    if (has_flag(argc, argv, "--lz4-frame")) o->format = FMT_LZ4_FRAME;
    if (has_flag(argc, argv, "--none")) o->format = FMT_NONE;
    o->keep_verity = has_flag(argc, argv, "--keep-verity");
    o->keep_forceencrypt = has_flag(argc, argv, "--keep-forceencrypt");
    o->no_patch = has_flag(argc, argv, "--no-patch");
    o->cmdline = get_opt(argc, argv, "--cmdline");
    o->ramdisk_path = get_opt(argc, argv, "--ramdisk");
}

/* ------------------------------------------------------------ info */

static void cmd_info(int argc, char **argv) {
    const char *path = positional(argc, argv);
    if (!path) die("缺少镜像路径");

    boot_image img;
    boot_parse(&img, path);

    printf("文件       : %s\n", path);
    printf("类型       : %s\n", img.is_vendor ? "vendor_boot" : "boot / recovery / init_boot");
    printf("头版本     : v%d\n", img.header_version);
    printf("页大小     : %u\n", img.page_size);
    printf("魔数偏移   : 0x%llx\n", (unsigned long long)img.magic_offset);
    if (img.board_name[0]) printf("设备名     : %s\n", img.board_name);
    if (img.os_version) {
        uint64_t v = img.os_version;
        printf("系统版本   : %llu.%llu.%llu\n",
               (unsigned long long)((v >> 25) & 0x7F),
               (unsigned long long)((v >> 18) & 0x7F),
               (unsigned long long)((v >> 11) & 0x7F));
        printf("安全补丁   : %llu-%02llu\n",
               (unsigned long long)(((v >> 4) & 0x7F) + 2000),
               (unsigned long long)(v & 0xF));
    }
    if (img.cmdline[0]) printf("内核命令行 : %s\n", img.cmdline);

    if (img.is_vendor) {
        boot_load_frags(&img);
        printf("vendor ramdisk : %zu 个片段\n", img.nfrag);
        for (size_t i = 0; i < img.nfrag; i++)
            printf("  [%zu] %s  type=%u  %zu 字节\n", i, img.frags[i].name, img.frags[i].type, img.frags[i].size);
    } else {
        printf("kernel     : %llu 字节\n", (unsigned long long)boot_size_of(&img, P_KERNEL));
        printf("ramdisk    : %llu 字节\n", (unsigned long long)boot_size_of(&img, P_RAMDISK));
        if (boot_has(&img, P_SECOND)) printf("second     : %llu 字节\n", (unsigned long long)boot_size_of(&img, P_SECOND));
        if (boot_has(&img, P_DTBO)) printf("recovery dtbo: %llu 字节\n", (unsigned long long)boot_size_of(&img, P_DTBO));
        if (boot_has(&img, P_DTB)) printf("dtb        : %llu 字节\n", (unsigned long long)boot_size_of(&img, P_DTB));
        if (boot_has(&img, P_SIGNATURE)) printf("signature  : %llu 字节\n", (unsigned long long)boot_size_of(&img, P_SIGNATURE));
    }

    size_t rlen = 0, plen = 0;
    uint8_t *peek = boot_peek_part(&img, P_RAMDISK, 8, &plen);
    if (peek && plen >= 4) {
        fmt_t f = fmt_detect(peek, plen);
        printf("ramdisk 格式: %s\n", fmt_name(f));
    }
    free(peek);

    uint8_t *rd = boot_read_part(&img, P_RAMDISK, &rlen);
    if (rd && rlen) {
        size_t raw_len = 0;
        uint8_t *raw = fmt_decompress(rd, rlen, FMT_AUTO, &raw_len);
        if (raw && cpio_is_magic(raw, raw_len)) {
            cpio_t a;
            cpio_parse(raw, raw_len, &a);
            printf("ramdisk 条目: %zu 个\n", a.n);
            cpio_free(&a);
        }
        free(raw);
    }
    free(rd);
    boot_free(&img);
}

/* ------------------------------------------------------------ unpack */

static void cmd_unpack(int argc, char **argv) {
    const char *path = positional(argc, argv);
    if (!path) die("缺少镜像路径");
    char dir[4096];
    const char *o = get_opt(argc, argv, "-o");
    if (o) snprintf(dir, sizeof(dir), "%s", o);
    else snprintf(dir, sizeof(dir), "%s_unpacked", path);

    boot_image img;
    boot_parse(&img, path);
    if (mkdir_p(dir) != 0) die("无法创建目录 %s", dir);
    if (boot_extract_parts(&img, dir) != 0) die("解出分区失败");
    printf("已解出各段到 %s\n", dir);

    if (img.is_vendor) {
        boot_load_frags(&img);
        for (size_t i = 0; i < img.nfrag; i++) {
            size_t need = strlen(dir) + 48;
            char *sub = xmalloc(need);
            snprintf(sub, need, "%s/vendor_%zu", dir, i);
            size_t raw_len = 0;
            uint8_t *raw = fmt_decompress(img.frags[i].data, img.frags[i].size, FMT_AUTO, &raw_len);
            if (raw && cpio_is_magic(raw, raw_len)) {
                cpio_t a;
                cpio_parse(raw, raw_len, &a);
                cpio_extract(&a, sub);
                printf("解出 %s：%zu 个条目\n", sub, a.n);
                cpio_free(&a);
            }
            free(sub);
            free(raw);
        }
        boot_free(&img);
        return;
    }

    size_t rlen = 0;
    uint8_t *rd = boot_read_part(&img, P_RAMDISK, &rlen);
    if (!rd || !rlen) {
        printf("镜像中没有 ramdisk\n");
        boot_free(&img);
        return;
    }
    size_t raw_len = 0;
    uint8_t *raw = fmt_decompress(rd, rlen, FMT_AUTO, &raw_len);
    free(rd);
    if (!raw || !cpio_is_magic(raw, raw_len)) {
        printf("ramdisk 解压失败或不是 cpio，仅保留原始文件\n");
        free(raw);
        boot_free(&img);
        return;
    }

    size_t need = strlen(dir) + 32;
    char *cpio_path = xmalloc(need);
    snprintf(cpio_path, need, "%s/ramdisk.cpio", dir);
    write_file(cpio_path, raw, raw_len);

    cpio_t a;
    cpio_parse(raw, raw_len, &a);
    printf("写出 %s（%zu 个条目）\n", cpio_path, a.n);
    free(cpio_path);

    char *tree = xmalloc(strlen(dir) + 32);
    snprintf(tree, strlen(dir) + 32, "%s/ramdisk", dir);
    cpio_extract(&a, tree);
    printf("解出 %s：%zu 个条目\n", tree, a.n);
    free(tree);

    cpio_free(&a);
    free(raw);
    boot_free(&img);
}

/* ------------------------------------------------------------ 共用：应用选项 */

static void apply_opts(boot_image *img, opts_t *o) {
    if (o->ramdisk_path) {
        size_t n = 0;
        uint8_t *d = read_file(o->ramdisk_path, &n);
        if (!d) die("无法读取 ramdisk: %s", o->ramdisk_path);
        if (img->is_vendor) {
            if (!img->nfrag) boot_load_frags(img);
            if (!img->nfrag) die("vendor_boot 没有片段");
            free(img->frags[0].data);
            img->frags[0].data = d;
            img->frags[0].size = n;
        } else {
            boot_set_part(img, P_RAMDISK, d, n);
        }
        printf("替换 ramdisk：%s（%zu 字节）\n", o->ramdisk_path, n);
    }

    if (!o->no_patch && (!o->keep_verity || !o->keep_forceencrypt)) {
        if (img->is_vendor) {
            if (!img->nfrag) boot_load_frags(img);
            int total = 0;
            for (size_t i = 0; i < img->nfrag; i++) {
                size_t raw_len = 0;
                uint8_t *raw = fmt_decompress(img->frags[i].data, img->frags[i].size, FMT_AUTO, &raw_len);
                if (!raw || !cpio_is_magic(raw, raw_len)) { free(raw); continue; }
                cpio_t a;
                cpio_parse(raw, raw_len, &a);
                free(raw);
                patch_result r = fstab_patch(&a, o->keep_verity, o->keep_forceencrypt);
                for (int k = 0; k < r.nnote; k++) printf("  %s\n", r.notes[k]);
                if (r.files) {
                    size_t nl = 0;
                    uint8_t *nb = cpio_build(&a, &nl);
                    size_t cl = 0;
                    uint8_t *comp = fmt_compress(nb, nl, o->format, &cl);
                    free(nb);
                    free(img->frags[i].data);
                    img->frags[i].data = comp;
                    img->frags[i].size = cl;
                    total += r.files;
                }
                cpio_free(&a);
            }
            if (total) printf("已修补 %d 个 fstab 文件\n", total);
        } else {
            size_t rlen = 0;
            uint8_t *rd = boot_read_part(img, P_RAMDISK, &rlen);
            if (rd && rlen) {
                size_t raw_len = 0;
                uint8_t *raw = fmt_decompress(rd, rlen, FMT_AUTO, &raw_len);
                free(rd);
                if (raw && cpio_is_magic(raw, raw_len)) {
                    cpio_t a;
                    cpio_parse(raw, raw_len, &a);
                    free(raw);
                    patch_result r = fstab_patch(&a, o->keep_verity, o->keep_forceencrypt);
                    for (int k = 0; k < r.nnote; k++) printf("  %s\n", r.notes[k]);
                    if (r.files) {
                        size_t nl = 0;
                        uint8_t *nb = cpio_build(&a, &nl);
                        size_t cl = 0;
                        uint8_t *comp = fmt_compress(nb, nl, o->format, &cl);
                        free(nb);
                        boot_set_part(img, P_RAMDISK, comp, cl);
                        printf("已修补 %d 个 fstab 文件\n", r.files);
                    }
                    cpio_free(&a);
                } else {
                    free(raw);
                }
            }
        }
    }

    if (o->cmdline && o->cmdline[0]) {
        fstab_append_cmdline(img, o->cmdline);
        printf("追加 cmdline：%s\n", o->cmdline);
    }
}

static void cmd_repack(int argc, char **argv) {
    const char *path = positional(argc, argv);
    if (!path) die("缺少镜像路径");
    const char *out = get_opt(argc, argv, "-o");
    if (!out) die("缺少 -o 输出路径");
    opts_t o;
    parse_opts(argc, argv, &o);

    boot_image img;
    boot_parse(&img, path);
    apply_opts(&img, &o);
    boot_pack(&img, out);

    struct stat st;
    uint64_t sz = 0;
    if (stat(out, &st) == 0) sz = (uint64_t)st.st_size;
    printf("已生成 %s（%llu 字节）\n", out, (unsigned long long)sz);
    boot_free(&img);
}

/* ------------------------------------------------------------ inject */

static void cmd_inject(int argc, char **argv) {
    const char *path = positional(argc, argv);
    if (!path) die("缺少镜像路径");
    const char *out = get_opt(argc, argv, "-o");
    if (!out) die("缺少 -o 输出路径");
    opts_t o;
    parse_opts(argc, argv, &o);

    boot_image img;
    boot_parse(&img, path);
    if (img.is_vendor) boot_load_frags(&img);

    size_t rlen = 0;
    uint8_t *rd = boot_read_part(&img, P_RAMDISK, &rlen);
    if (!rd || !rlen) die("镜像中没有 ramdisk");

    size_t raw_len = 0;
    uint8_t *raw = fmt_decompress(rd, rlen, FMT_AUTO, &raw_len);
    free(rd);
    if (!raw || !cpio_is_magic(raw, raw_len)) die("ramdisk 解压后不是 cpio 归档");

    cpio_t a;
    cpio_parse(raw, raw_len, &a);
    free(raw);

    int count = 0;
    for (int i = 2; i < argc; i++) {
        char *arg = argv[i];
        if (arg[0] == '-') continue;
        char *eq = strchr(arg, '=');
        if (!eq) continue;
        *eq = 0;
        char *local = arg;
        char *rest = eq + 1;
        char *colon = strrchr(rest, ':');
        char *target = rest;
        char *mode_str = "0755";
        if (colon) { *colon = 0; mode_str = colon + 1; }
        unsigned mode = 0;
        if (sscanf(mode_str, "%o", &mode) != 1) die("权限 %s 不是合法八进制数", mode_str);

        size_t n = 0;
        uint8_t *data = read_file(local, &n);
        if (!data) die("无法读取 %s", local);
        cpio_add(&a, target, data, n, (uint32_t)(mode | 0x8000));
        free(data);
        printf("注入 %s -> %s (%s)\n", local, target, mode_str);
        count++;
    }
    if (!count) die("没有要注入的文件，格式：本地文件=ramdisk内路径[:权限]");

    size_t nl = 0;
    uint8_t *nb = cpio_build(&a, &nl);
    cpio_free(&a);
    size_t cl = 0;
    uint8_t *comp = fmt_compress(nb, nl, o.format, &cl);
    free(nb);

    if (img.is_vendor) {
        if (!img.nfrag) die("vendor_boot 没有可写入的片段");
        free(img.frags[0].data);
        img.frags[0].data = comp;
        img.frags[0].size = cl;
    } else {
        boot_set_part(&img, P_RAMDISK, comp, cl);
    }

    apply_opts(&img, &o);
    boot_pack(&img, out);

    struct stat st;
    uint64_t sz = 0;
    if (stat(out, &st) == 0) sz = (uint64_t)st.st_size;
    printf("已生成 %s（%llu 字节）\n", out, (unsigned long long)sz);
    boot_free(&img);
}

/* ------------------------------------------------------------ main */

int main(int argc, char **argv) {
    if (argc < 2) { usage(); return 0; }
    const char *cmd = argv[1];
    if (strcmp(cmd, "-h") == 0 || strcmp(cmd, "--help") == 0 || strcmp(cmd, "help") == 0) {
        usage();
        return 0;
    }
    if (strcmp(cmd, "-v") == 0 || strcmp(cmd, "--version") == 0) {
        printf("bootforge %s\n", BF_VERSION);
        return 0;
    }
    if (strcmp(cmd, "info") == 0) { cmd_info(argc, argv); return 0; }
    if (strcmp(cmd, "unpack") == 0) { cmd_unpack(argc, argv); return 0; }
    if (strcmp(cmd, "repack") == 0) { cmd_repack(argc, argv); return 0; }
    if (strcmp(cmd, "patch") == 0) { cmd_repack(argc, argv); return 0; }
    if (strcmp(cmd, "inject") == 0) { cmd_inject(argc, argv); return 0; }
    fprintf(stderr, "未知子命令: %s\n", cmd);
    usage();
    return 1;
}
