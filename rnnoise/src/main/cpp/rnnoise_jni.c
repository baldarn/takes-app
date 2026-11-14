// Bridge between app.takes.rnnoise.RNNoise and the C library.
#include <jni.h>
#include <stdint.h>

#include "YouRecDenoiser.h"
#include "include/rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL Java_app_takes_rnnoise_RNNoise_create(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    // The adapter from the iPhone app: it creates the state under a lock and runs one
    // silent frame, because v0.1 fills its shared FFT tables on first use.
    return (jlong)(intptr_t)yr_denoiser_create();
}

JNIEXPORT void JNICALL Java_app_takes_rnnoise_RNNoise_destroy(JNIEnv *env, jclass type, jlong state) {
    (void)env; (void)type;
    yr_denoiser_destroy((void *)(intptr_t)state);
}

// Same arithmetic as yr_denoiser_process, which throws the speech probability away;
// here it is returned, so one call serves both denoising and speech detection.
// Kotlin has already checked that both arrays hold 480 samples from their offsets.
JNIEXPORT jfloat JNICALL Java_app_takes_rnnoise_RNNoise_process(
        JNIEnv *env, jclass type, jlong state,
        jfloatArray input, jint inputOffset, jfloatArray output, jint outputOffset) {
    (void)type;
    float scaled[FRAME], processed[FRAME];
    (*env)->GetFloatArrayRegion(env, input, inputOffset, FRAME, scaled);
    if ((*env)->ExceptionCheck(env)) return 0;
    for (int i = 0; i < FRAME; i++) scaled[i] *= 32768.f;
    float speech = rnnoise_process_frame((DenoiseState *)(intptr_t)state, processed, scaled);
    for (int i = 0; i < FRAME; i++) processed[i] /= 32768.f;
    (*env)->SetFloatArrayRegion(env, output, outputOffset, FRAME, processed);
    return speech;
}
