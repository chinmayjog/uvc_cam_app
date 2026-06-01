cmake_minimum_required(VERSION 3.22.1)

project("MinimalUVCCamera")

# 16KB page-size support for Android 15+ (API 35).
#
# add_link_options() propagates to ALL targets in this directory and every
# subdirectory, regardless of how deeply nested their CMakeLists.txt files are.
# This covers libusb1.0, libjpeg9, libuvc, libyuv, uvc_preview, and Uvc_Support.
#
# Two flags are needed:
#   max-page-size  — declares the binary supports pages up to 16 KB
#   common-page-size — actually aligns ELF LOAD segments to 16 KB in the file
#
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON (set in build.gradle) additionally
# embeds the GNU_PROPERTY note required by the Play Store compatibility check
# when using NDK r27+.
add_link_options(
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

find_library(log-lib log)

add_subdirectory(libUvc_Support)
