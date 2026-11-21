cmake_minimum_required(VERSION 3.16)
project(lajvard_omr_mobile LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(OMR_MOBILE_ENABLE_TESTS "Build core regression tests for the mobile package" OFF)
set(BUILD_TESTING ${OMR_MOBILE_ENABLE_TESTS} CACHE BOOL "" FORCE)
set(OMR_ENABLE_LIVE_DETECTOR OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../cpp lajvard_omr_core_build)

if(IOS)
  add_library(lajvard_omr_mobile STATIC
    ${CMAKE_CURRENT_LIST_DIR}/../cpp/src/MobileOmrC.cpp
  )
  set_target_properties(lajvard_omr_mobile PROPERTIES
    FRAMEWORK TRUE
    FRAMEWORK_VERSION A
    MACOSX_FRAMEWORK_IDENTIFIER com.lajvard.omr
    PUBLIC_HEADER ${CMAKE_CURRENT_LIST_DIR}/../cpp/include/omr/MobileOmrC.h
  )
else()
  add_library(lajvard_omr_mobile SHARED
    ${CMAKE_CURRENT_LIST_DIR}/../cpp/src/MobileOmrC.cpp
  )
endif()

target_compile_definitions(lajvard_omr_mobile PRIVATE OMR_MOBILE_BUILD_SHARED=1)
target_include_directories(lajvard_omr_mobile PUBLIC
  ${CMAKE_CURRENT_LIST_DIR}/../cpp/include
)
target_link_libraries(lajvard_omr_mobile PRIVATE lajvard_omr_core)

set_target_properties(lajvard_omr_mobile PROPERTIES
  OUTPUT_NAME lajvard_omr_mobile
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)

install(TARGETS lajvard_omr_mobile
  LIBRARY DESTINATION lib
  ARCHIVE DESTINATION lib
  RUNTIME DESTINATION bin
  FRAMEWORK DESTINATION Frameworks
  PUBLIC_HEADER DESTINATION include/omr
)
