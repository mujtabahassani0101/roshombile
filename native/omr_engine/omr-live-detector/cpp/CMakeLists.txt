cmake_minimum_required(VERSION 3.16)
project(lajvard_omr_core LANGUAGES CXX)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(OMR_ENABLE_LIVE_DETECTOR "Build raw camera-frame live guidance" ON)

set(OMR_CORE_SOURCES
  src/OmrTemplate.cpp
  src/NormalizeEngine.cpp
  src/MobileOmrBatch.cpp
  src/MobileOmrC.cpp
  src/BarcodeReader.cpp
  src/AnswerReader.cpp
  src/quality/QualityAnalyzer.cpp
  src/rails/SideDotRailDetector.cpp
  src/geometry/SheetQuadEstimator.cpp
  src/geometry/Homography.cpp
  src/geometry/BubbleGridRefiner.cpp
  src/utils/Json.cpp
  src/utils/Timer.cpp
)

if(OMR_ENABLE_LIVE_DETECTOR)
  list(APPEND OMR_CORE_SOURCES
    src/OmrLiveEngine.cpp
    src/decision/LiveDecisionEngine.cpp
    src/decision/StabilityTracker.cpp
  )
endif()

add_library(lajvard_omr_core STATIC ${OMR_CORE_SOURCES})

if(ANDROID)
  # The app's normal development build used CMake's -O0 default, which made
  # the CPU-heavy OMR loops more than twice as slow on the device. Keep debug
  # symbols while optimizing this native engine target in both app variants.
  target_compile_options(lajvard_omr_core PRIVATE
    $<$<CONFIG:Debug>:-O2>
  )
endif()

target_include_directories(lajvard_omr_core PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/include
  ${CMAKE_CURRENT_SOURCE_DIR}/src
)

if(ANDROID AND OMR_OPENCV_INCLUDE_DIR AND OMR_OPENCV_SHARED_DIR)
  set(OMR_OPENCV_LIBRARY
    "${OMR_OPENCV_SHARED_DIR}/${CMAKE_ANDROID_ARCH_ABI}/libopencv_java4.so"
  )
  if(NOT EXISTS "${OMR_OPENCV_LIBRARY}")
    message(FATAL_ERROR "Missing vendored OpenCV library: ${OMR_OPENCV_LIBRARY}")
  endif()
  if(NOT EXISTS "${OMR_OPENCV_INCLUDE_DIR}/opencv2/core.hpp")
    message(FATAL_ERROR "Missing vendored OpenCV headers: ${OMR_OPENCV_INCLUDE_DIR}")
  endif()
  add_library(omr_opencv_java4 SHARED IMPORTED)
  set_target_properties(omr_opencv_java4 PROPERTIES
    IMPORTED_LOCATION "${OMR_OPENCV_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${OMR_OPENCV_INCLUDE_DIR}"
  )
  set(OpenCV_LIBS omr_opencv_java4)
else()
  find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs)
endif()
if(NOT TARGET ZXing::ZXing)
  find_package(ZXing REQUIRED)
endif()

target_link_libraries(lajvard_omr_core PUBLIC
  ${OpenCV_LIBS}
  ZXing::ZXing
)

include(CTest)
if(BUILD_TESTING)
  if(ANDROID)
    set(OMR_TEST_TEMPLATE_PATH "/data/local/tmp/omr-bench/lajvard_answer_sheet_120q_qr_template_v2.json")
  else()
    set(OMR_TEST_TEMPLATE_PATH "${CMAKE_CURRENT_LIST_DIR}/../../templates/lajvard_answer_sheet_120q_qr_template_v2.json")
  endif()

  add_executable(omr_answer_reader_regression
    tests/AnswerReaderRegression.cpp
  )
  target_link_libraries(omr_answer_reader_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_answer_reader_regression COMMAND omr_answer_reader_regression)

  add_executable(omr_barcode_reader_regression
    tests/BarcodeReaderRegression.cpp
  )
  target_link_libraries(omr_barcode_reader_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_barcode_reader_regression COMMAND omr_barcode_reader_regression)

  add_executable(omr_geometry_accuracy_regression
    tests/GeometryAccuracyRegression.cpp
  )
  target_link_libraries(omr_geometry_accuracy_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_geometry_accuracy_regression COMMAND omr_geometry_accuracy_regression)

  add_executable(omr_normalize_accuracy_regression
    tests/NormalizeAccuracyRegression.cpp
  )
  target_compile_definitions(omr_normalize_accuracy_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
  )
  target_link_libraries(omr_normalize_accuracy_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_normalize_accuracy_regression COMMAND omr_normalize_accuracy_regression)

  if(OMR_ENABLE_LIVE_DETECTOR)
    add_executable(omr_live_orientation_regression
      tests/OmrLiveOrientationRegression.cpp
    )
    target_link_libraries(omr_live_orientation_regression PRIVATE lajvard_omr_core)
    add_test(NAME omr_live_orientation_regression COMMAND omr_live_orientation_regression)
  endif()

  add_executable(omr_mobile_contract_regression
    tests/MobileOmrContractRegression.cpp
  )
  target_compile_definitions(omr_mobile_contract_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
  )
  target_link_libraries(omr_mobile_contract_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_mobile_contract_regression COMMAND omr_mobile_contract_regression)

  add_executable(omr_template_layout_regression
    tests/TemplateLayoutRegression.cpp
  )
  target_compile_definitions(omr_template_layout_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
  )
  target_link_libraries(omr_template_layout_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_template_layout_regression COMMAND omr_template_layout_regression)
endif()
