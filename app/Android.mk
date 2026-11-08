LOCAL_PATH := $(call my-dir)

JNI_SRC_PATH := $(LOCAL_PATH)/src/main/cpp

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
input_header := $(NDK_OUT)/local/$(TARGET_ARCH_ABI)/generated/keyboard_grab/input.h-labels.h
input_generator := $(JNI_SRC_PATH)/generate-input.h-labels.py
input_input := $(TOOLCHAIN_ROOT)/sysroot/usr/include/linux/input-event-codes.h

$(input_header): PRIVATE_GENERATOR := $(input_generator)
$(input_header): PRIVATE_INPUT := $(input_input)
$(input_header): $(input_generator) $(input_input) $(dir $(input_input))input.h
	$(hide)$(HOST_PYTHON) "$(PRIVATE_GENERATOR)" "$(PRIVATE_INPUT)" "$(dir $(PRIVATE_INPUT))input.h" --native "$@"

LOCAL_C_INCLUDES := $(dir $(input_header))
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/keyboard_grab.cpp
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)

# ndk-build must generate the header before the first compilation too.
$(JNI_SRC_PATH)/keyboard_grab.cpp: | $(input_header)
