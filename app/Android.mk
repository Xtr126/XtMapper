LOCAL_PATH := $(call my-dir)

JNI_SRC_PATH := $(LOCAL_PATH)/src/main/cpp

include $(CLEAR_VARS)

LOCAL_MODULE    := mouse_read
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/mouse_read.c $(JNI_SRC_PATH)/xt_diagnostics.c $(JNI_SRC_PATH)/xt_diag_events.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := mouse_cursor
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/mouse_cursor.cpp
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := evdev_common
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/evdev_common.cpp
# The scanner contains exceptions from the injectable ops seam (FdGuard and
# the loop catch); ndk-build's default would reject try/catch outright.
LOCAL_CPPFLAGS  := -fexceptions
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := touchpad_direct
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/touchpad_direct.cpp $(JNI_SRC_PATH)/touchpad_lifecycle.cpp
LOCAL_CPPFLAGS  := -fexceptions
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := touchpad_relative
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/touchpad_relative.cpp $(JNI_SRC_PATH)/touchpad_lifecycle.cpp
LOCAL_CPPFLAGS  := -fexceptions
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := keyboard_grab

# Per-ABI output avoids concurrent native builds writing the same header.
input_header := $(NDK_OUT)/local/$(TARGET_ARCH_ABI)/generated/keyboard_grab/input.h-labels.h
input_generator := $(JNI_SRC_PATH)/generate-input.h-labels.py
input_input := $(TOOLCHAIN_ROOT)/sysroot/usr/include/linux/input-event-codes.h

$(input_header): PRIVATE_GENERATOR := $(input_generator)
$(input_header): PRIVATE_INPUT := $(input_input)
$(input_header): $(input_generator) $(input_input) $(dir $(input_input))input.h
	$(hide)$(HOST_PYTHON) "$(PRIVATE_GENERATOR)" "$(PRIVATE_INPUT)" "$(dir $(PRIVATE_INPUT))input.h" --native "$@"

LOCAL_C_INCLUDES := $(dir $(input_header))
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/keyboard_grab.cpp $(JNI_SRC_PATH)/xt_diagnostics.c $(JNI_SRC_PATH)/xt_diag_events.c
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

# ndk-build must generate the header before the first compilation too.
$(JNI_SRC_PATH)/keyboard_grab.cpp: | $(input_header)

# The client and its dependencies are built from the pinned source subset.
# Refresh it with src/main/cpp/refresh-wayland-client.sh.
ffi_src := $(JNI_SRC_PATH)/third-party/libffi
ffi_includes := $(ffi_src)/config/$(TARGET_ARCH_ABI) $(ffi_src)/include
ffi_arch_armeabi-v7a := arm/ffi.c arm/sysv.S
ffi_arch_arm64-v8a := aarch64/ffi.c aarch64/sysv.S
ffi_arch_x86 := x86/ffi.c x86/sysv.S
ffi_arch_x86_64 := x86/ffi64.c x86/unix64.S x86/ffiw64.c x86/win64.S
ffi_common := closures.c java_raw_api.c prep_cif.c raw_api.c tramp.c types.c

include $(CLEAR_VARS)
LOCAL_MODULE := xtmapper_ffi
LOCAL_C_INCLUDES := $(ffi_includes)
LOCAL_SRC_FILES := $(addprefix $(ffi_src)/src/,$(ffi_common) $(ffi_arch_$(TARGET_ARCH_ABI)))
LOCAL_CFLAGS := -fvisibility=hidden
include $(BUILD_STATIC_LIBRARY)

wayland_src := $(JNI_SRC_PATH)/third-party/wayland/src
wayland_generated := $(JNI_SRC_PATH)/third-party/wayland/generated
wayland_includes := $(wayland_src) $(wayland_generated)
wayland_core := connection.c wayland-client.c wayland-os.c wayland-util.c
wayland_protocol := wayland-protocol.c xdg-shell-protocol.c pointer-constraints-protocol.c relative-pointer-protocol.c

include $(CLEAR_VARS)
LOCAL_MODULE := xtmapper_wayland_client
LOCAL_C_INCLUDES := $(wayland_includes) $(ffi_includes)
LOCAL_SRC_FILES := $(addprefix $(wayland_src)/,$(wayland_core)) \
                   $(addprefix $(wayland_generated)/,$(wayland_protocol))
LOCAL_CFLAGS := -D_POSIX_C_SOURCE=200809L -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := xtmapper_ffi
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := xtmapper_wayland
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/wayland_capture.cpp $(JNI_SRC_PATH)/wayland_launcher.cpp
LOCAL_C_INCLUDES := $(wayland_includes)
LOCAL_CPPFLAGS  := -std=c++17
LOCAL_LDLIBS    := -llog
LOCAL_LDFLAGS   := -Wl,--exclude-libs,ALL
LOCAL_STATIC_LIBRARIES := xtmapper_wayland_client
include $(BUILD_SHARED_LIBRARY)

ifeq ($(XTMAPPER_WAYLAND_CAPTURE_DIAGNOSTICS),1)
include $(CLEAR_VARS)
LOCAL_MODULE    := wayland_capture_diagnostics
LOCAL_SRC_FILES := src/debug/cpp/wayland_capture_diagnostics.cpp
LOCAL_CPPFLAGS  := -std=c++17
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
endif
