cmake_minimum_required(VERSION 3.16)
project(xtmapper_native_diagnostics_host_tests CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Debug)
endif()

enable_testing()

set(XTMAPPER_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

if(NOT DEFINED ENV{JAVA_HOME})
  message(FATAL_ERROR "JAVA_HOME must point at a JDK so the diagnostics host tests can include jni.h")
endif()

set(JNI_INCLUDE_DIRS $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/linux)

add_executable(native_diagnostics_host_tests
  native_diagnostics_host_tests.cpp
  ${XTMAPPER_CPP}/xt_diagnostics.c
  ${XTMAPPER_CPP}/xt_diag_events.c
)
target_include_directories(native_diagnostics_host_tests PRIVATE
  ${XTMAPPER_CPP} ${JNI_INCLUDE_DIRS}
)

add_executable(native_diagnostics_unavailable_host_tests
  native_diagnostics_unavailable_host_tests.cpp
  ${XTMAPPER_CPP}/xt_diagnostics.c
  ${XTMAPPER_CPP}/xt_diag_events.c
)
target_include_directories(native_diagnostics_unavailable_host_tests PRIVATE
  ${XTMAPPER_CPP} ${JNI_INCLUDE_DIRS}
)

find_package(Threads REQUIRED)
target_link_libraries(native_diagnostics_host_tests PRIVATE Threads::Threads)
target_link_libraries(native_diagnostics_unavailable_host_tests PRIVATE Threads::Threads)
target_link_options(native_diagnostics_host_tests PRIVATE -Wl,--wrap=clock_gettime)

find_path(NDK_JNI_INCLUDE_DIR jni.h HINTS
  "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
  "$ENV{ANDROID_HOME}/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
  "$ENV{ANDROID_SDK_ROOT}/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
)
if(NOT NDK_JNI_INCLUDE_DIR)
  message(FATAL_ERROR "NDK 28 jni.h is required for the producer probe; set NDK_JNI_INCLUDE_DIR")
endif()
configure_file(${NDK_JNI_INCLUDE_DIR}/jni.h ${CMAKE_CURRENT_BINARY_DIR}/ndk-jni/jni.h COPYONLY)

add_executable(keyboard_diagnostics_host_tests
  keyboard_diagnostics_host_tests.cpp
  ${XTMAPPER_CPP}/xt_diagnostics.c
  ${XTMAPPER_CPP}/xt_diag_events.c
)
target_include_directories(keyboard_diagnostics_host_tests PRIVATE ${XTMAPPER_CPP} ${CMAKE_CURRENT_BINARY_DIR}/ndk-jni)
target_link_libraries(keyboard_diagnostics_host_tests PRIVATE Threads::Threads)
target_link_options(keyboard_diagnostics_host_tests PRIVATE
  -Wl,--wrap=pipe2 -Wl,--wrap=open -Wl,--wrap=ioctl -Wl,--wrap=close
  -Wl,--wrap=poll -Wl,--wrap=read
)

add_test(NAME native_diagnostics_host_tests
  COMMAND native_diagnostics_host_tests)
add_test(NAME native_diagnostics_unavailable_find_class
  COMMAND native_diagnostics_unavailable_host_tests findclass)
add_test(NAME native_diagnostics_unavailable_method
  COMMAND native_diagnostics_unavailable_host_tests method)
add_test(NAME native_diagnostics_unavailable_global_ref
  COMMAND native_diagnostics_unavailable_host_tests globalref)
add_test(NAME native_diagnostics_foreign_pending
  COMMAND native_diagnostics_unavailable_host_tests pending)
add_test(NAME keyboard_diagnostics_host_tests COMMAND keyboard_diagnostics_host_tests)
