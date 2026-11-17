// Connects the iPhone app's motion estimator (YouRec/StabilizationMotion.cpp) to Kotlin.
//
// A MotionEstimator keeps the previous frame, already reduced to the analysis size, the
// way VideoStabilizer.measure keeps `previous` (YouRec/VideoStabilizer.swift:131, 175-190):
// every frame pushed becomes the previous one of the next, and from the second on the
// motion from the previous frame to it is estimated.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>
#include <vector>

#include "StabilizationMotion.h"

namespace {

struct Estimator {
    int width, height;
    std::vector<uint8_t> previous, current;
    bool hasPrevious = false;
    // The taps of the area resampling of one source size, kept while the size stays.
    int sourceWidth = 0, sourceHeight = 0;
    std::vector<int> columnFirst, columnCount, rowFirst, rowCount;
    std::vector<float> columnWeights, rowWeights;
    std::vector<float> rows; // one reduced row's worth of column sums per source row
};

// The weights of an area (box) resampling from `from` samples to `to`: output i covers the
// source interval [i*from/to, (i+1)*from/to), each source sample weighted by how much of it
// lies inside. The original scales with vImage's high quality resampling; any reduction of
// this kind keeps the estimator's sub-pixel accuracy on the frames it compares.
void taps(int from, int to, std::vector<int> &first, std::vector<int> &count, std::vector<float> &weights) {
    first.assign(to, 0);
    count.assign(to, 0);
    weights.clear();
    double step = double(from) / to;
    for (int i = 0; i < to; ++i) {
        double a = i * step, b = (i + 1) * step;
        int lo = int(std::floor(a)), hi = std::min(from, int(std::ceil(b)));
        first[i] = lo;
        count[i] = hi - lo;
        for (int s = lo; s < hi; ++s) {
            double overlap = std::min(b, double(s + 1)) - std::max(a, double(s));
            weights.push_back(float(overlap / step));
        }
    }
}

// Reduces the luma of a decoded frame to the estimator's size into `out`. `sample` is the
// distance between two samples of a row (2 for 16-bit P010, whose high byte `high` is read).
// Limited range is stretched to full range, as the original reads its frames
// (kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, VideoStabilizer.swift:125).
void reduce(Estimator &e, const uint8_t *plane, int rowStride, int sample, int left, int top,
            int sourceWidth, int sourceHeight, bool fullRange, std::vector<uint8_t> &out) {
    if (sourceWidth != e.sourceWidth || sourceHeight != e.sourceHeight) {
        taps(sourceWidth, e.width, e.columnFirst, e.columnCount, e.columnWeights);
        taps(sourceHeight, e.height, e.rowFirst, e.rowCount, e.rowWeights);
        e.sourceWidth = sourceWidth;
        e.sourceHeight = sourceHeight;
    }
    e.rows.assign(size_t(e.width) * sourceHeight, 0.f);
    // Across first: every source row reduced to the output's width.
    for (int y = 0; y < sourceHeight; ++y) {
        const uint8_t *row = plane + size_t(top + y) * rowStride + size_t(left) * sample;
        float *into = &e.rows[size_t(y) * e.width];
        size_t w = 0;
        for (int x = 0; x < e.width; ++x) {
            float sum = 0;
            const uint8_t *p = row + size_t(e.columnFirst[x]) * sample;
            for (int k = 0; k < e.columnCount[x]; ++k, p += sample) sum += *p * e.columnWeights[w++];
            into[x] = sum;
        }
    }
    out.resize(size_t(e.width) * e.height);
    size_t w = 0;
    for (int y = 0; y < e.height; ++y) {
        const float *weights = &e.rowWeights[w];
        w += e.rowCount[y];
        for (int x = 0; x < e.width; ++x) {
            float sum = 0;
            for (int k = 0; k < e.rowCount[y]; ++k) sum += e.rows[size_t(e.rowFirst[y] + k) * e.width + x] * weights[k];
            if (!fullRange) sum = (sum - 16.f) * (255.f / 219.f);
            out[size_t(y) * e.width + x] = uint8_t(std::lround(std::min(255.f, std::max(0.f, sum))));
        }
    }
}

void write(JNIEnv *env, jdoubleArray result, const YRMotion &m) {
    jdouble values[7] = {m.x, m.y, m.angle, m.logScale, m.inlierRatio, double(m.inliers), double(m.valid)};
    env->SetDoubleArrayRegion(result, 0, 7, values);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_takes_stabilization_MotionEstimator_create(JNIEnv *, jclass, jint width, jint height) {
    auto *e = new (std::nothrow) Estimator{width, height};
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_app_takes_stabilization_MotionEstimator_destroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Estimator *>(handle);
}

// Returns 0 for the first frame, which has nothing to be compared with, or when `estimate` is
// false (the frame only becomes the previous one), 1 when `result` holds the motion from the
// previous frame, and -1 when the plane cannot be read.
JNIEXPORT jint JNICALL
Java_app_takes_stabilization_MotionEstimator_push(JNIEnv *env, jclass, jlong handle, jobject plane, jint rowStride,
                                                  jint sample, jint high, jint left, jint top, jint width, jint height,
                                                  jboolean fullRange, jboolean estimate, jdoubleArray result) {
    auto *e = reinterpret_cast<Estimator *>(handle);
    auto *bytes = static_cast<const uint8_t *>(env->GetDirectBufferAddress(plane));
    jlong capacity = env->GetDirectBufferCapacity(plane);
    if (!bytes || width <= 0 || height <= 0 || sample <= 0 || high < 0 || high >= sample) return -1;
    jlong last = jlong(top + height - 1) * rowStride + jlong(left + width - 1) * sample + high;
    if (left < 0 || top < 0 || last >= capacity) return -1;
    reduce(*e, bytes + high, rowStride, sample, left, top, width, height, fullRange, e->current);
    jint said = 0;
    if (e->hasPrevious && estimate) {
        YRMotion m = yr_estimate_motion(e->previous.data(), e->current.data(), e->width, e->height);
        write(env, result, m);
        said = 1;
    }
    std::swap(e->previous, e->current);
    e->hasPrevious = true;
    return said;
}

// The reduced picture of the last frame pushed, for the tests.
JNIEXPORT void JNICALL
Java_app_takes_stabilization_MotionEstimator_last(JNIEnv *env, jclass, jlong handle, jbyteArray into) {
    auto *e = reinterpret_cast<Estimator *>(handle);
    env->SetByteArrayRegion(into, 0, jsize(e->previous.size()), reinterpret_cast<const jbyte *>(e->previous.data()));
}

// yr_estimate_motion on two frames already at the analysis size.
JNIEXPORT void JNICALL
Java_app_takes_stabilization_MotionEstimator_estimate(JNIEnv *env, jclass, jbyteArray previous, jbyteArray current,
                                                      jint width, jint height, jdoubleArray result) {
    jbyte *a = env->GetByteArrayElements(previous, nullptr);
    jbyte *b = env->GetByteArrayElements(current, nullptr);
    YRMotion m = yr_estimate_motion(reinterpret_cast<const uint8_t *>(a), reinterpret_cast<const uint8_t *>(b), width, height);
    env->ReleaseByteArrayElements(previous, a, JNI_ABORT);
    env->ReleaseByteArrayElements(current, b, JNI_ABORT);
    write(env, result, m);
}

}  // extern "C"
