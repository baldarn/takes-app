cmake_minimum_required(VERSION 3.22.1)
project(rnnoise_jni C)

# rnnoise/ is a verbatim copy of ThirdParty/RNNoise from the iPhone app, see README.md.
# The list of files is the one in rnnoise/src/compile.sh.
add_library(rnnoise_jni SHARED
    rnnoise_jni.c
    rnnoise/YouRecDenoiser.c
    rnnoise/src/denoise.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/pitch.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c)

target_include_directories(rnnoise_jni PRIVATE rnnoise rnnoise/include)
set_target_properties(rnnoise_jni PROPERTIES C_VISIBILITY_PRESET hidden)
# Optimized in debug builds too: nobody steps through this code, and an unoptimized
# network is several times slower.
target_compile_options(rnnoise_jni PRIVATE -O2)
target_link_libraries(rnnoise_jni PRIVATE m)

if(ANDROID)
    # Phones with 16 KB memory pages only load libraries aligned to 16 KB. NDK r28 and
    # newer do this by default; saying it here keeps it true with an older NDK.
    target_link_options(rnnoise_jni PRIVATE -Wl,-z,max-page-size=16384)
else()
    # Built for the computer running the unit tests: jni.h comes from its JDK.
    if(APPLE)
        set(JNI_PLATFORM darwin)
    else()
        set(JNI_PLATFORM linux)
        find_package(Threads REQUIRED)
        target_link_libraries(rnnoise_jni PRIVATE Threads::Threads)
    endif()
    target_include_directories(rnnoise_jni PRIVATE
        ${HOST_JAVA_HOME}/include ${HOST_JAVA_HOME}/include/${JNI_PLATFORM})
endif()
