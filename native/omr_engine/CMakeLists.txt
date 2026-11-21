cmake_minimum_required(VERSION 3.22.1)

project(young_scientist_omr_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(OMR_BUILD_TESTS "Build the OMR core regression tests" OFF)

# Keep the barcode dependency local and decoder-only. These settings match the
# engine's use of ZXing and avoid adding unrelated examples, writers, or C APIs.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build dependencies as static libraries" FORCE)
set(ZXING_READERS ON CACHE BOOL "Build ZXing readers" FORCE)
# The negative barcode regression generates Code128 to verify QR-only rejection.
# Production builds still contain only the QR decoder.
set(ZXING_ENABLE_1D ${OMR_BUILD_TESTS} CACHE BOOL "" FORCE)
set(ZXING_ENABLE_AZTEC OFF CACHE BOOL "" FORCE)
set(ZXING_ENABLE_DATAMATRIX OFF CACHE BOOL "" FORCE)
set(ZXING_ENABLE_MAXICODE OFF CACHE BOOL "" FORCE)
set(ZXING_ENABLE_PDF417 OFF CACHE BOOL "" FORCE)
set(ZXING_ENABLE_QRCODE ON CACHE BOOL "" FORCE)
if(OMR_BUILD_TESTS)
  add_executable(omr_four_engine_separation_regression tests/FourEngineSeparationRegression.cpp)
  target_link_libraries(omr_four_engine_separation_regression PRIVATE lajvard_omr_mobile lajvard_omr_core)
  # Synthetic barcode regressions generate their own images. Writers are never
  # included in the shipping decoder-only library.
  set(ZXING_WRITERS OLD CACHE STRING "QR writer for native regression fixtures" FORCE)
else()
  set(ZXING_WRITERS OFF CACHE STRING "Disable ZXing writers" FORCE)
endif()
set(ZXING_C_API OFF CACHE BOOL "Disable the ZXing C API" FORCE)
set(ZXING_EXAMPLES OFF CACHE BOOL "Disable ZXing examples" FORCE)
set(ZXING_EXAMPLES_QT OFF CACHE BOOL "Disable ZXing Qt examples" FORCE)
set(ZXING_BLACKBOX_TESTS OFF CACHE BOOL "Disable ZXing black-box tests" FORCE)
set(ZXING_UNIT_TESTS OFF CACHE BOOL "Disable ZXing unit tests" FORCE)
set(ZXING_TEST_DOTNET OFF CACHE BOOL "Disable ZXing .NET tests" FORCE)
set(ZXING_TEST_GO OFF CACHE BOOL "Disable ZXing Go tests" FORCE)
set(ZXING_TEST_INSTALL OFF CACHE BOOL "Disable ZXing install tests" FORCE)
set(ZXING_PYTHON_MODULE OFF CACHE BOOL "Disable the ZXing Python module" FORCE)

add_subdirectory(third_party/zxing-cpp zxing-cpp-build)

# The repository carries the exact OpenCV Android SDK used by the source app.
# Desktop and Apple builds intentionally use find_package(OpenCV) in the core.
if(ANDROID)
  set(
    OMR_OPENCV_INCLUDE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/third_party/opencv/sdk/native/jni/include"
    CACHE PATH
    "Vendored OpenCV include directory"
    FORCE
  )
  set(
    OMR_OPENCV_SHARED_DIR
    "${CMAKE_CURRENT_LIST_DIR}/third_party/opencv/sdk/native/libs"
    CACHE PATH
    "Vendored OpenCV Android shared-library directory"
    FORCE
  )
endif()

set(OMR_MOBILE_ENABLE_TESTS ${OMR_BUILD_TESTS} CACHE BOOL "Build OMR tests" FORCE)
# Build the complete r11 package with its three calibrated physical layouts.
# The application owns its JNI bridge in app/src/main/cpp. Disable the
# package's optional example bridge to avoid shipping two JNI adapters.
set(OMR_BUILD_ANDROID_JNI OFF CACHE BOOL "Use the application JNI bridge" FORCE)
add_subdirectory(omr-mobile-engine omr-mobile-build)

if(OMR_BUILD_TESTS)
  add_executable(omr_stage_timing tests/StageTiming.cpp)
  add_executable(omr_parallel_engine_queues_regression tests/ParallelEngineQueuesRegression.cpp)
  target_link_libraries(omr_parallel_engine_queues_regression PRIVATE lajvard_omr_core)
  if(ANDROID)
    target_compile_definitions(omr_parallel_engine_queues_regression PRIVATE OMR_PARALLEL_TEMPLATE_DIR="/data/local/tmp/omr-bench")
  else()
    target_compile_definitions(omr_parallel_engine_queues_regression PRIVATE OMR_PARALLEL_TEMPLATE_DIR="${CMAKE_CURRENT_LIST_DIR}/omr-mobile-engine/templates")
  endif()
  add_test(NAME omr_parallel_engine_queues_regression COMMAND omr_parallel_engine_queues_regression)
  target_link_libraries(omr_stage_timing PRIVATE lajvard_omr_core)
  add_executable(omr_header_orientation_speed_regression tests/HeaderOrientationSpeedRegression.cpp)
  target_link_libraries(omr_header_orientation_speed_regression PRIVATE lajvard_omr_core)
  add_executable(omr_striped_sheet_regression tests/StripedSheetRegression.cpp)
  target_link_libraries(omr_striped_sheet_regression PRIVATE lajvard_omr_core)
  add_executable(omr_r11_packaged_regression tests/R11PackagedRegression.cpp)
  target_link_libraries(omr_r11_packaged_regression PRIVATE lajvard_omr_mobile lajvard_omr_core)
  add_executable(omr_qr_prefix_routing_regression tests/QrPrefixRoutingRegression.cpp)
  target_link_libraries(omr_qr_prefix_routing_regression PRIVATE lajvard_omr_mobile lajvard_omr_core)
  add_executable(omr_t3_geometry_variants_regression tests/T3GeometryVariantsRegression.cpp)
  target_link_libraries(omr_t3_geometry_variants_regression PRIVATE lajvard_omr_mobile lajvard_omr_core)
  if(ANDROID)
    # A pure C loader tests the shipping C ABI without linking test-only ZXing
    # writer symbols into the executable.
    add_executable(omr_packaged_c_abi_smoke tests/PackagedCAbiSmoke.c)
    target_link_libraries(omr_packaged_c_abi_smoke PRIVATE dl)
  endif()
endif()

