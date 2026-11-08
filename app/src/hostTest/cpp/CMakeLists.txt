cmake_minimum_required(VERSION 3.16)
project(xtmapper_hostTest CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Debug)
endif()

enable_testing()

set(XTMAPPER_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_executable(touchpad_lifecycle_host_tests
  touchpad_lifecycle_host_tests.cpp
  ${XTMAPPER_CPP}/touchpad_lifecycle.cpp
)
target_include_directories(touchpad_lifecycle_host_tests PRIVATE ${XTMAPPER_CPP})
find_package(Threads REQUIRED)
target_link_libraries(touchpad_lifecycle_host_tests PRIVATE Threads::Threads)

add_executable(touchpad_scanner_host_tests
  touchpad_scanner_host_tests.cpp
  ${XTMAPPER_CPP}/evdev_common.cpp
)
target_include_directories(touchpad_scanner_host_tests PRIVATE ${XTMAPPER_CPP})

add_test(NAME touchpad_lifecycle_host_tests COMMAND touchpad_lifecycle_host_tests)
add_test(NAME touchpad_scanner_host_tests COMMAND touchpad_scanner_host_tests)
