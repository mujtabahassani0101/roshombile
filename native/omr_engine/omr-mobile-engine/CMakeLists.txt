cmake_minimum_required(VERSION 3.22.1)
project(yse_mobile_omr_engine VERSION 2.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(OMR_MOBILE_ENABLE_TESTS "Build the native regression suite" OFF)
option(OMR_BUILD_ANDROID_JNI "Build the reference Android JNI bridge" ${ANDROID})
set(OMR_ZXING_SOURCE_DIR "" CACHE PATH "Optional path to a ZXing-C++ source tree")

if(ANDROID AND (NOT OMR_OPENCV_INCLUDE_DIR OR NOT OMR_OPENCV_SHARED_DIR))
  message(FATAL_ERROR
    "Android builds require OMR_OPENCV_INCLUDE_DIR and OMR_OPENCV_SHARED_DIR"
  )
endif()

if(OMR_ZXING_SOURCE_DIR AND NOT TARGET ZXing::ZXing)
  if(NOT EXISTS "${OMR_ZXING_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "OMR_ZXING_SOURCE_DIR does not contain ZXing-C++")
  endif()
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(ZXING_READERS ON CACHE BOOL "" FORCE)
  # Production only decodes QR codes, while two regression fixtures generate
  # synthetic QR images with ZXing's self-contained legacy writer. The bundled
  # dependency intentionally omits zint, so the newer writer cannot be enabled.
  if(OMR_MOBILE_ENABLE_TESTS)
    set(ZXING_WRITERS OLD CACHE STRING "" FORCE)
  else()
    set(ZXING_WRITERS OFF CACHE STRING "" FORCE)
  endif()
  set(ZXING_C_API OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_1D OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_AZTEC OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_DATAMATRIX OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_MAXICODE OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_PDF417 OFF CACHE BOOL "" FORCE)
  set(ZXING_ENABLE_QRCODE ON CACHE BOOL "" FORCE)
  set(ZXING_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(ZXING_BLACKBOX_TESTS OFF CACHE BOOL "" FORCE)
  set(ZXING_UNIT_TESTS OFF CACHE BOOL "" FORCE)
  add_subdirectory("${OMR_ZXING_SOURCE_DIR}" zxing_build EXCLUDE_FROM_ALL)
endif()

add_subdirectory(mobile)

if(ANDROID AND OMR_BUILD_ANDROID_JNI)
  add_library(omr_scanner_jni SHARED bindings/android/OmrMobileJni.cpp)
  target_include_directories(omr_scanner_jni PRIVATE cpp/include)
  target_link_libraries(omr_scanner_jni PRIVATE lajvard_omr_mobile)
endif()

install(DIRECTORY cpp/include/omr DESTINATION include)
install(DIRECTORY templates/ DESTINATION share/yse-mobile-omr/templates)
