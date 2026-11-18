cmake_minimum_required(VERSION 3.22.1)
project(deepfilter_jni CXX)

# onnxruntime/ holds ONNX Runtime's C headers, 1.28.2 (see its README). The runtime itself is
# not linked: onnx_session.cpp opens the one the app carries for sherpa-onnx when it is first
# needed.
add_library(deepfilter_jni SHARED onnx_session.cpp)

target_include_directories(deepfilter_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/onnxruntime)
target_compile_features(deepfilter_jni PRIVATE cxx_std_17)
set_target_properties(deepfilter_jni PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_compile_options(deepfilter_jni PRIVATE -O2)
target_link_libraries(deepfilter_jni PRIVATE ${CMAKE_DL_LIBS})

if(ANDROID)
    # Phones with 16 KB memory pages only load libraries aligned to 16 KB. NDK r28 and
    # newer do this by default; saying it here keeps it true with an older NDK.
    target_link_options(deepfilter_jni PRIVATE -Wl,-z,max-page-size=16384)
    # Only the parts of the C++ library it uses: the app's build strips the rest for the APK and keeps the
    # symbol tables for Play.
    target_compile_options(deepfilter_jni PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(deepfilter_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    # Built for the computer running the unit tests: jni.h comes from its JDK.
    if(APPLE)
        set(JNI_PLATFORM darwin)
    else()
        set(JNI_PLATFORM linux)
    endif()
    target_include_directories(deepfilter_jni PRIVATE
        ${HOST_JAVA_HOME}/include ${HOST_JAVA_HOME}/include/${JNI_PLATFORM})
endif()
