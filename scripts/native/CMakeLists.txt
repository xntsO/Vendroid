cmake_minimum_required(VERSION 3.22)
project(vendroid_usb_16kb LANGUAGES C)

if(NOT ANDROID)
    message(FATAL_ERROR "Use the pinned Android NDK toolchain")
endif()

# Reuse the pinned JNI adapters with only three integer release-mode corrections
# and the unchanged libusb source list. Both link
# options are explicit so all four ABIs use the same page alignment policy.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
set(LIBUSB_PATH "${LIBUSB_SOURCE}")
add_subdirectory("${LIBAUMS_SOURCE}/libaums" core)
add_subdirectory("${LIBAUMS_SOURCE}/libusbcommunication" communication)

foreach(target errno-lib usb-lib libusb libusbcom)
    set_target_properties(${target} PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
endforeach()
