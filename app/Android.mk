LOCAL_PATH := $(call my-dir)

JNI_SRC_PATH := $(LOCAL_PATH)/src/main/cpp

include $(CLEAR_VARS)

LOCAL_MODULE := getevent
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/getevent.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := mouse_read
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/mouse_read.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := mouse_cursor
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/mouse_cursor.cpp
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := evdev_common
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/evdev_common.cpp
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := touchpad_direct
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/touchpad_direct.cpp
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := touchpad_relative
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/touchpad_relative.cpp
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := keyboard_grab

# Per-ABI output avoids concurrent native builds writing the same header.
linux_key_header := $(NDK_OUT)/local/$(TARGET_ARCH_ABI)/generated/keyboard_grab/linux_key_table.h
linux_key_generator := $(JNI_SRC_PATH)/generate_linux_key_table.py
linux_key_input := $(TOOLCHAIN_ROOT)/sysroot/usr/include/linux/input-event-codes.h

$(linux_key_header): PRIVATE_GENERATOR := $(linux_key_generator)
$(linux_key_header): PRIVATE_INPUT := $(linux_key_input)
$(linux_key_header): $(linux_key_generator) $(linux_key_input)
	$(hide)$(HOST_PYTHON) "$(PRIVATE_GENERATOR)" "$(PRIVATE_INPUT)" "$@"

LOCAL_C_INCLUDES := $(dir $(linux_key_header))
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/keyboard_grab.cpp
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

# ndk-build must generate the header before the first compilation too.
$(JNI_SRC_PATH)/keyboard_grab.cpp: | $(linux_key_header)
