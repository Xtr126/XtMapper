LOCAL_PATH := $(call my-dir)

JNI_SRC_PATH := $(LOCAL_PATH)/src/main/cpp

include $(CLEAR_VARS)

LOCAL_MODULE := getevent
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/getevent.c
include $(BUILD_SHARED_LIBRARY)

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
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/keyboard_grab.cpp $(JNI_SRC_PATH)/xt_diagnostics.c $(JNI_SRC_PATH)/xt_diag_events.c
LOCAL_SHARED_LIBRARIES := evdev_common
include $(BUILD_SHARED_LIBRARY)
