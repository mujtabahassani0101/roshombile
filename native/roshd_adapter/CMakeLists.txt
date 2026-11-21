cmake_minimum_required(VERSION 3.22.1)
project(roshd_source_engine_integration LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(ROSHD_NATIVE "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
option(ROSHD_REBUILD_SOURCE_ENGINES "Rebuild the copied engines instead of using the source application's exact Android binaries" OFF)
if(ANDROID AND NOT ROSHD_REBUILD_SOURCE_ENGINES)
  # Ship the exact source-app engines. The complete matching source remains
  # vendored and is compiled by host tests or the explicit rebuild option.
  set(OMR_OPENCV_INCLUDE_DIR "${ROSHD_NATIVE}/omr_engine/third_party/opencv/sdk/native/jni/include")
  set(OMR_OPENCV_SHARED_DIR "${ROSHD_NATIVE}/omr_engine/third_party/opencv/sdk/native/libs")
  add_library(lajvard_omr_mobile SHARED IMPORTED GLOBAL)
  set_target_properties(lajvard_omr_mobile PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_LIST_DIR}/vendor/${ANDROID_ABI}/liblajvard_omr_mobile.so")
  add_library(document_scanner SHARED IMPORTED GLOBAL)
  set_target_properties(document_scanner PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_LIST_DIR}/vendor/${ANDROID_ABI}/libdocument_scanner.so")
else()
  add_subdirectory("${ROSHD_NATIVE}/omr_engine" "${CMAKE_BINARY_DIR}/omr")
  add_subdirectory("${ROSHD_NATIVE}/document_scanner" "${CMAKE_BINARY_DIR}/document-scanner")
endif()
if(NOT ANDROID)
  # The upstream Android SDK contains calib3d in its single shared library.
  # Desktop SDKs split it into a separate dependency.
  find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs calib3d)
  target_link_libraries(lajvard_omr_core PUBLIC ${OpenCV_LIBS})
endif()
add_library(roshd_engine_adapter SHARED RoshdEngine.cpp GradedSheetRenderer.cpp CameraPreparation.cpp InkVerification.cpp)
target_include_directories(roshd_engine_adapter PUBLIC include
  "${ROSHD_NATIVE}/omr_engine/omr-mobile-engine/cpp/include"
  "${ROSHD_NATIVE}/omr_engine/omr-mobile-engine/cpp/src")
target_link_libraries(roshd_engine_adapter PRIVATE lajvard_omr_mobile)
# Use the exact ZXing already exported by the source engine only to propose a
# small header ROI. BarcodeReader independently verifies that ROI afterwards.
function(roshd_header_locator_headers)
  set(ZXING_READERS ON)
  set(ZXING_WRITERS OFF)
  set(ZXING_ENABLE_QRCODE ON)
  set(PROJECT_VERSION_MAJOR 3)
  set(PROJECT_VERSION_MINOR 1)
  set(PROJECT_VERSION_PATCH 0)
  configure_file("${ROSHD_NATIVE}/omr_engine/third_party/zxing-cpp/core/Version.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/header-locator/Version.h" @ONLY)
  target_include_directories(roshd_engine_adapter PRIVATE
    "${ROSHD_NATIVE}/omr_engine/third_party/zxing-cpp/core/src"
    "${CMAKE_CURRENT_BINARY_DIR}/header-locator")
endfunction()
roshd_header_locator_headers()
if(ANDROID)
  add_library(roshd_adapter_opencv SHARED IMPORTED)
  set_target_properties(roshd_adapter_opencv PROPERTIES
    IMPORTED_LOCATION "${OMR_OPENCV_SHARED_DIR}/${ANDROID_ABI}/libopencv_java4.so")
  target_include_directories(roshd_engine_adapter PRIVATE "${OMR_OPENCV_INCLUDE_DIR}")
  target_link_libraries(roshd_engine_adapter PRIVATE roshd_adapter_opencv)
  # The JVM invokes this JNI library directly; retain it as a packaged native
  # dependency even though the application bridge does not call its C++ API.
  target_link_libraries(roshd_engine_adapter PRIVATE "-Wl,--no-as-needed" document_scanner "-Wl,--as-needed")
else()
  target_include_directories(roshd_engine_adapter PRIVATE ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(roshd_engine_adapter PRIVATE ${OpenCV_LIBS})
endif()
