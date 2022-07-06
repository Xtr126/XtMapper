#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Workaround a bug in abspath on Windows that existed in older versions of make
# (paths with drive letters were not handled properly in abspath). r21+ has a
# newer version of make that doesn't have this bug.

ifeq ($(call ndk-major-at-least,21),true)
    shorten_path = $(abspath $1)
else
    # Strip the drive letter, call abspath, prepend the drive letter.
    shorten_path = $(join $(filter %:,$(subst :,: ,$1)),$(abspath $(filter-out %:,$(subst :,: ,$1))))
endif

LOCAL_PATH := $(call my-dir)

JNI_SRC_PATH := $(LOCAL_PATH)/src/main/cpp

include $(CLEAR_VARS)

LOCAL_MODULE := getevent-bin
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/prebuilt/$(TARGET_ARCH_ABI)/libgetevent.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := 1e0e99df
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/prebuilt/placeholder.c
LOCAL_SHARED_LIBRARIES := getevent-bin
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE    := mouse_read
LOCAL_SRC_FILES := $(JNI_SRC_PATH)/mouse_read.c
LOCAL_LDLIBS    := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
