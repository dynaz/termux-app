LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
# Google Play requires 16 KB pages for the native libraries of apps that target Android 15 and later
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
