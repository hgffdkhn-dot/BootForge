# bootforge - pure C, built the magiskboot way
#
# Local build (PC):
#   make                 # host gcc, produces build/bootforge
#   make static          # static linking (works on PC)
#
# Android cross compilation (NDK):
#   make android         ANDROID_NDK_HOME=/path/to/ndk   # aarch64 dynamic (recommended)
#   make android-arm     ANDROID_NDK_HOME=/path/to/ndk   # armv7 dynamic
#   make android-static  ANDROID_NDK_HOME=/path/to/ndk   # aarch64 static
#
# About static linking: Android bionic requires arm64 executables to have a TLS
# segment aligned to at least 64 bytes, but lld only gives 8 for static execs.
#   "executable's TLS segment is underaligned: alignment is 8, needs to be at least 64"
# Dynamic builds are loaded by /system/bin/linker64 and skip that check, so default to dynamic.

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
	@echo "output: $@"
	@file $@ 2>/dev/null || true

static: CFLAGS += -static
static: LDFLAGS += -static
static: clean $(TARGET)

# ------------------------------------------ Android dynamic (recommended)

android: CC      := $(NDK_BIN)/aarch64-linux-android$(NDK_API)-clang
android: CFLAGS  := $(NDK_CFLAGS)
android: LDFLAGS := -pie
android: clean $(TARGET)
	@echo "aarch64 dynamic binary: $(TARGET)"

android-arm: CC      := $(NDK_BIN)/armv7a-linux-androideabi$(NDK_API)-clang
android-arm: CFLAGS  := $(NDK_CFLAGS)
android-arm: LDFLAGS := -pie
android-arm: clean $(TARGET)
	@echo "armv7 dynamic binary: $(TARGET)"

# --------------------------------------------- Android static (optional)

android-static: CC      := $(NDK_BIN)/aarch64-linux-android$(NDK_API)-clang
android-static: CFLAGS  := $(NDK_CFLAGS) -static
android-static: LDFLAGS := -static
android-static: clean $(TARGET)
	@echo "aarch64 static binary: $(TARGET) (use dynamic if TLS alignment error)"

android-arm-static: CC      := $(NDK_BIN)/armv7a-linux-androideabi$(NDK_API)-clang
android-arm-static: CFLAGS  := $(NDK_CFLAGS) -static
android-arm-static: LDFLAGS := -static
android-arm-static: clean $(TARGET)

# build all four Android binaries at once
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

# inspect TLS segment alignment (to debug the bionic error above)
inspect: $(TARGET)
	@readelf -l $(TARGET) 2>/dev/null | grep -A1 -i "tls" || echo "no TLS segment or readelf missing"

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD)

test: $(TARGET)
	@sh test/run.sh
