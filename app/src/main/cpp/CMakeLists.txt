cmake_minimum_required(VERSION 3.22.1)

project("MinimalUVCCamera")

# Set 16 KB page alignment for Android 15+ compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Find log library
find_library(log-lib log)

# Add libUvc_Support which contains source files and UVC_Camera_Saki
set(LIBUVC_SUPPORT_DIR libUvc_Support)
add_subdirectory(${LIBUVC_SUPPORT_DIR})
