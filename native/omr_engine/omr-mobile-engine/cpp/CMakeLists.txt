cmake_minimum_required(VERSION 3.16)
project(lajvard_omr_core LANGUAGES CXX)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(OMR_ENABLE_LIVE_DETECTOR "Build raw camera-frame live guidance" ON)

set(OMR_CORE_SOURCES
  src/OmrTemplate.cpp
  src/NormalizeEngine.cpp
  src/HeaderEngine.cpp
  src/QuestionEngine.cpp
  src/T1AnswerEngine.cpp
  src/T2AnswerEngine.cpp
  src/T3AnswerEngine.cpp
  src/MobileOmrBatch.cpp
  src/MobileOmrC.cpp
  src/BarcodeReader.cpp
  src/QrSheetType.cpp
  src/AnswerReader.cpp
  src/quality/QualityAnalyzer.cpp
  src/rails/SideDotRailDetector.cpp
  src/geometry/SheetQuadEstimator.cpp
  src/geometry/Homography.cpp
  src/geometry/BubbleGridRefiner.cpp
  src/utils/Json.cpp
  src/utils/Timer.cpp
  src/utils/EllipticalMorphology.cpp
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
  # Keep debug symbols while optimizing the CPU-heavy native engine on device.
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

find_package(Threads REQUIRED)

target_link_libraries(lajvard_omr_core PUBLIC
  Threads::Threads
  ${OpenCV_LIBS}
  ZXing::ZXing
)

include(CTest)
if(BUILD_TESTING)
  if(ANDROID)
    set(OMR_TEST_TEMPLATE_PATH "/data/local/tmp/omr-bench/yse_omr_a5_t1_100q_v2.json")
    set(OMR_TEST_T2_TEMPLATE_PATH "/data/local/tmp/omr-bench/yse_omr_a5_t2_60q_v1.json")
    set(OMR_TEST_T3_TEMPLATE_PATH "/data/local/tmp/omr-bench/yse_omr_a5_t3_40q_v1.json")
  else()
    set(OMR_TEST_TEMPLATE_PATH "${CMAKE_CURRENT_LIST_DIR}/../templates/yse_omr_a5_t1_100q_v2.json")
    set(OMR_TEST_T2_TEMPLATE_PATH "${CMAKE_CURRENT_LIST_DIR}/../templates/yse_omr_a5_t2_60q_v1.json")
    set(OMR_TEST_T3_TEMPLATE_PATH "${CMAKE_CURRENT_LIST_DIR}/../templates/yse_omr_a5_t3_40q_v1.json")
  endif()

  add_executable(omr_elliptical_morphology_regression
    tests/EllipticalMorphologyRegression.cpp
  )
  target_link_libraries(omr_elliptical_morphology_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_elliptical_morphology_regression COMMAND omr_elliptical_morphology_regression)

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

  add_executable(omr_qr_sheet_type_regression
    tests/QrSheetTypeRegression.cpp
  )
  target_link_libraries(omr_qr_sheet_type_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_qr_sheet_type_regression COMMAND omr_qr_sheet_type_regression)

  add_executable(omr_geometry_accuracy_regression
    tests/GeometryAccuracyRegression.cpp
  )
  target_compile_definitions(omr_geometry_accuracy_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
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
    target_compile_definitions(omr_live_orientation_regression PRIVATE
      OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
    )
    target_link_libraries(omr_live_orientation_regression PRIVATE lajvard_omr_core)
    add_test(NAME omr_live_orientation_regression COMMAND omr_live_orientation_regression)
  endif()

  add_executable(omr_mobile_contract_regression
    tests/MobileOmrContractRegression.cpp
  )
  target_compile_definitions(omr_mobile_contract_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
    OMR_TEST_T2_TEMPLATE_PATH="${OMR_TEST_T2_TEMPLATE_PATH}"
    OMR_TEST_T3_TEMPLATE_PATH="${OMR_TEST_T3_TEMPLATE_PATH}"
  )
  target_link_libraries(omr_mobile_contract_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_mobile_contract_regression COMMAND omr_mobile_contract_regression)

  add_executable(omr_template_layout_regression
    tests/TemplateLayoutRegression.cpp
  )
  target_compile_definitions(omr_template_layout_regression PRIVATE
    OMR_TEST_TEMPLATE_PATH="${OMR_TEST_TEMPLATE_PATH}"
    OMR_TEST_T2_TEMPLATE_PATH="${OMR_TEST_T2_TEMPLATE_PATH}"
    OMR_TEST_T3_TEMPLATE_PATH="${OMR_TEST_T3_TEMPLATE_PATH}"
  )
  target_link_libraries(omr_template_layout_regression PRIVATE lajvard_omr_core)
  add_test(NAME omr_template_layout_regression COMMAND omr_template_layout_regression)
endif()
