# bootforge — 纯 C 实现，magiskboot 式构建
#
# 本地构建（PC 上）：
#   make                 # 用系统 gcc，产出 build/bootforge
#   make static          # 静态链接（PC 上可用）
#
# Android 交叉编译（NDK）：
#   make android         ANDROID_NDK_HOME=/path/to/ndk   # aarch64 动态链接（推荐）
#   make android-arm     ANDROID_NDK_HOME=/path/to/ndk   # armv7 动态链接
#   make android-static  ANDROID_NDK_HOME=/path/to/ndk   # aarch64 静态（部分设备报 TLS 对齐错误）
#
# 关于静态链接：Android bionic 要求 arm64 可执行文件的 TLS 段对齐至少 64 字节，
# 而 lld 对静态可执行文件只给 8，运行时会报：
#   "executable's TLS segment is underaligned: alignment is 8, needs to be at least 64"
# 动态链接的版本由 /system/bin/linker64 加载，不走这条检查，因此默认用动态链接。

CC      ?= cc
CFLAGS  ?= -O2 -std=c11 -Wall -Wextra -D_GNU_SOURCE
LDFLAGS ?=
BUILD   := build
SRC     := $(wildcard src/*.c)
OBJ     := $(patsubst src/%.c,$(BUILD)/%.o,$(SRC))
TARGET  := $(BUILD)/bootforge

# --- Android（NDK）---
ANDROID_NDK_HOME ?= /usr/local/lib/android/sdk/ndk/27.0.12077973
NDK_API   ?= 21
NDK_HOST  ?= linux-x86_64
NDK_BIN   := $(ANDROID_NDK_HOME)/toolchains/llvm/prebuilt/$(NDK_HOST)/bin
NDK_CFLAGS := -O2 -std=c11 -Wall -Wextra -fPIC -D_GNU_SOURCE

.PHONY: all static android android-arm android-static android-arm-static \
        android-all clean test inspect

all: $(TARGET)

$(BUILD)/%.o: src/%.c src/bootforge.h | $(BUILD)
	$(CC) $(CFLAGS) -c $< -o $@

$(TARGET): $(OBJ)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)
	@echo "产物: $@"
	@file $@ 2>/dev/null || true

static: CFLAGS += -static
static: LDFLAGS += -static
static: clean $(TARGET)

# --------------------------------------------------- Android 动态链接（默认推荐）

android: CC      := $(NDK_BIN)/aarch64-linux-android$(NDK_API)-clang
android: CFLAGS  := $(NDK_CFLAGS)
android: LDFLAGS := -pie
android: clean $(TARGET)
	@echo "aarch64 动态二进制: $(TARGET)"

android-arm: CC      := $(NDK_BIN)/armv7a-linux-androideabi$(NDK_API)-clang
android-arm: CFLAGS  := $(NDK_CFLAGS)
android-arm: LDFLAGS := -pie
android-arm: clean $(TARGET)
	@echo "armv7 动态二进制: $(TARGET)"

# --------------------------------------------------- Android 静态链接（可选）

android-static: CC      := $(NDK_BIN)/aarch64-linux-android$(NDK_API)-clang
android-static: CFLAGS  := $(NDK_CFLAGS) -static
android-static: LDFLAGS := -static
android-static: clean $(TARGET)
	@echo "aarch64 静态二进制: $(TARGET)（若运行报 TLS 对齐错误，改用动态版）"

android-arm-static: CC      := $(NDK_BIN)/armv7a-linux-androideabi$(NDK_API)-clang
android-arm-static: CFLAGS  := $(NDK_CFLAGS) -static
android-arm-static: LDFLAGS := -static
android-arm-static: clean $(TARGET)

# 一次产出四个 Android 二进制
android-all: ANDROID_OUT := $(BUILD)/android
android-all:
	@set -e; mkdir -p $(ANDROID_OUT); \
	for t in android android-arm android-static android-arm-static; do \
	  $(MAKE) $$t ANDROID_NDK_HOME=$(ANDROID_NDK_HOME); \
	  case $$t in \
	    android)            cp $(TARGET) $(ANDROID_OUT)/bootforge-android-aarch64 ;; \
	    android-arm)        cp $(TARGET) $(ANDROID_OUT)/bootforge-android-armv7 ;; \
	    android-static)     cp $(TARGET) $(ANDROID_OUT)/bootforge-android-aarch64-static ;; \
	    android-arm-static) cp $(TARGET) $(ANDROID_OUT)/bootforge-android-armv7-static ;; \
	  esac; \
	done; \
	ls -lh $(ANDROID_OUT)

# 查看 TLS 段对齐（用于排查上面的 bionic 报错）
inspect: $(TARGET)
	@readelf -l $(TARGET) 2>/dev/null | grep -A1 -i "tls" || echo "无 TLS 段或没有 readelf"

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD)

test: $(TARGET)
	@sh test/run.sh
