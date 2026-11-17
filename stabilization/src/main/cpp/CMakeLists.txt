cmake_minimum_required(VERSION 3.22.1)
project(stabilization_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# YouRec/ holds the iPhone app's motion estimator, file for file: see README.md.
add_library(stabilization_jni SHARED
    stabilization_jni.cpp
    YouRec/StabilizationMotion.cpp)

target_include_directories(stabilization_jni PRIVATE YouRec)
set_target_properties(stabilization_jni PROPERTIES CXX_VISIBILITY_PRESET hidden)
# Optimized in debug builds too: the estimator runs on every frame of a clip.
target_compile_options(stabilization_jni PRIVATE -O2)

if(ANDROID)
    # Phones with 16 KB memory pages only load libraries aligned to 16 KB. NDK r28 and
    # newer do this by default; saying it here keeps it true with an older NDK.
    target_link_options(stabilization_jni PRIVATE -Wl,-z,max-page-size=16384)
    # Only what the estimator uses of the C++ library it links in, to keep the app small.
    target_compile_options(stabilization_jni PRIVATE -ffunction-sections -fdata-sections)
    # Without its debug information too: the release build packs the library as linked.
    target_link_options(stabilization_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    # Built for the computer running the unit tests: jni.h comes from its JDK.
    if(APPLE)
        set(JNI_PLATFORM darwin)
    else()
        set(JNI_PLATFORM linux)
    endif()
    target_include_directories(stabilization_jni PRIVATE
        ${HOST_JAVA_HOME}/include ${HOST_JAVA_HOME}/include/${JNI_PLATFORM})
    # The original's own test of the estimator (Tests/StabilizationMotionTests.cpp), unchanged:
    # the unit tests run it and check that it passes.
    add_executable(stabilization_motion_tests
        ${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp/Tests/StabilizationMotionTests.cpp
        YouRec/StabilizationMotion.cpp)
    target_include_directories(stabilization_motion_tests PRIVATE YouRec)
    target_compile_options(stabilization_motion_tests PRIVATE -O2)
endif()
