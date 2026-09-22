# bootforge — 纯 C 实现，magiskboot 式构建
#
# 本地构建（PC 上）：
#   make                 # 用系统 gcc，产出 build/bootforge
#   make static          # 静态链接
#
# Android 交叉编译（NDK）：
#   make android ANDROID_NDK_HOME=/path/to/ndk
#   默认用 NDK 的 aarch64 clang + 静态链接，产物可直接 adb push 到手机

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

.PHONY: all static android clean test
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

# 交叉编译 aarch64（手机）
android: CC      := $(NDK_BIN)/aarch64-linux-android$(NDK_API)-clang
android: CFLAGS  := -O2 -std=c11 -Wall -Wextra -static -fPIC
android: LDFLAGS := -static
android: clean $(TARGET)
	@echo "aarch64 静态二进制: $(TARGET)"

android-arm: CC := $(NDK_BIN)/armv7a-linux-androideabi$(NDK_API)-clang
android-arm: CFLAGS := -O2 -std=c11 -Wall -Wextra -static -fPIC
android-arm: LDFLAGS := -static
android-arm: clean $(TARGET)

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD)

test: $(TARGET)
	@sh test/run.sh
