cmake_minimum_required(VERSION 3.22.1)
project(roshd_kotlin LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
get_filename_component(ROSHD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_subdirectory("${ROSHD_ROOT}/native/roshd_adapter" "${CMAKE_BINARY_DIR}/roshd-adapter")
# Integer-only, pixel-exact morphology. Keep its linear scans vectorized on
# Cortex-A53 devices without enabling fast-math or changing OMR thresholds.
set_source_files_properties(
    "${ROSHD_ROOT}/native/omr_engine/omr-mobile-engine/cpp/src/utils/EllipticalMorphology.cpp"
    TARGET_DIRECTORY lajvard_omr_core PROPERTIES COMPILE_OPTIONS "-O3")
add_library(roshd_jni SHARED OmrJni.cpp)
target_link_libraries(roshd_jni PRIVATE roshd_engine_adapter lajvard_omr_mobile)

# Unchanged Moji Scan 3.0.2 inference bridge and ARM64 runtime.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  add_library(MNN SHARED IMPORTED)
  set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so")
  add_library(paperframe_inference SHARED inference.cpp)
  target_compile_features(paperframe_inference PRIVATE cxx_std_17)
  target_include_directories(paperframe_inference PRIVATE include)
  target_link_libraries(paperframe_inference PRIVATE MNN)
  target_compile_options(paperframe_inference PRIVATE -O3 -fvisibility=hidden)
endif()
