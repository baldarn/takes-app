// A network run by ONNX Runtime through its C API, for OnnxSession.kt.
//
// The runtime is not linked in: it is opened by name when the first session is made, so
// that this library uses the libonnxruntime.so the app already carries for sherpa-onnx
// (speech/), and the app has one copy of ONNX Runtime. Only the C API is used, which a
// newer runtime keeps serving to code built for an older one.

#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

namespace {

const OrtApi* api = nullptr;
OrtEnv* environment = nullptr;
OrtMemoryInfo* processor = nullptr;

void raise(JNIEnv* jni, const std::string& message) {
    jclass type = jni->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) jni->ThrowNew(type, message.c_str());
}

// Turns an error of ONNX Runtime into a Java exception; true when there was one.
bool failed(JNIEnv* jni, OrtStatus* status) {
    if (status == nullptr) return false;
    std::string message = std::string("ONNX Runtime: ") + api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    raise(jni, message);
    return true;
}

struct Values {
    std::vector<OrtValue*> list;
    ~Values() {
        for (OrtValue* value : list) if (value != nullptr) api->ReleaseValue(value);
    }
};

struct Session {
    OrtSession* session = nullptr;
    std::vector<std::string> inputs;
    std::vector<std::string> outputs;
    std::vector<const char*> inputNames;
    std::vector<const char*> outputNames;
};

std::vector<std::string> strings(JNIEnv* jni, jobjectArray array) {
    std::vector<std::string> out;
    jsize count = jni->GetArrayLength(array);
    for (jsize i = 0; i < count; i++) {
        auto text = static_cast<jstring>(jni->GetObjectArrayElement(array, i));
        const char* chars = jni->GetStringUTFChars(text, nullptr);
        out.emplace_back(chars);
        jni->ReleaseStringUTFChars(text, chars);
        jni->DeleteLocalRef(text);
    }
    return out;
}

// Wraps the memory of a direct buffer, without copying it, as a tensor of [shape]. False,
// with a Java exception, when the buffer is not direct or too small. The capacity of a
// ByteBuffer counts bytes, that of a FloatBuffer floats: [unit] is the size of one.
bool wrap(JNIEnv* jni, jobject buffer, jlongArray shapeArray, ONNXTensorElementDataType type, size_t size, size_t unit, OrtValue** out) {
    void* data = jni->GetDirectBufferAddress(buffer);
    jlong capacity = jni->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0) {
        raise(jni, "tensor data must be a direct buffer");
        return false;
    }
    jsize rank = jni->GetArrayLength(shapeArray);
    std::vector<int64_t> shape(rank);
    jni->GetLongArrayRegion(shapeArray, 0, rank, reinterpret_cast<jlong*>(shape.data()));
    size_t count = 1;
    for (int64_t dim : shape) count *= static_cast<size_t>(dim);
    size_t bytes = count * size;
    size_t available = static_cast<size_t>(capacity) * unit;
    if (bytes > available) {
        raise(jni, "tensor data holds " + std::to_string(available) + " bytes, " + std::to_string(bytes) + " needed");
        return false;
    }
    return !failed(jni, api->CreateTensorWithDataAsOrtValue(processor, data, bytes, shape.data(), shape.size(), type, out));
}

size_t elementSize(jint type) {
    switch (type) {
        case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT: return 4;
        case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64: return 8;
        default: return 0;
    }
}

// Options for a session run on [threads] processor threads, with every optimization of the graph. Null, with
// a Java exception, on failure; the caller releases them.
OrtSessionOptions* sessionOptions(JNIEnv* jni, int threads) {
    OrtSessionOptions* options = nullptr;
    if (failed(jni, api->CreateSessionOptions(&options))) return nullptr;
    OrtStatus* status = api->SetIntraOpNumThreads(options, threads);
    if (status == nullptr) status = api->SetInterOpNumThreads(options, 1);
    // Threads that wait on a busy loop between runs would hold cores the rest of the app needs.
    if (status == nullptr && threads > 1) status = api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0");
    if (status == nullptr) status = api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL);
    if (failed(jni, status)) {
        api->ReleaseSessionOptions(options);
        return nullptr;
    }
    return options;
}

std::unique_ptr<Session> namedSession(JNIEnv* jni, jobjectArray inputs, jobjectArray outputs) {
    auto session = std::make_unique<Session>();
    session->inputs = strings(jni, inputs);
    session->outputs = strings(jni, outputs);
    for (auto& name : session->inputs) session->inputNames.push_back(name.c_str());
    for (auto& name : session->outputs) session->outputNames.push_back(name.c_str());
    return session;
}

}  // namespace

extern "C" {

// Opens ONNX Runtime: the library at [path], or libonnxruntime.so where the app's own
// libraries are when [path] is null. Null when it is ready, otherwise why it is not.
JNIEXPORT jstring JNICALL Java_app_takes_deepfilter_OnnxSession_open(JNIEnv* jni, jclass, jstring path) {
    if (api != nullptr) return nullptr;
    std::string name = "libonnxruntime.so";
    if (path != nullptr) {
        const char* chars = jni->GetStringUTFChars(path, nullptr);
        name = chars;
        jni->ReleaseStringUTFChars(path, chars);
    }
    void* library = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        const char* error = dlerror();
        return jni->NewStringUTF((std::string("cannot open ") + name + ": " + (error ? error : "")).c_str());
    }
    using GetApiBase = const OrtApiBase* (*)();
    auto base = reinterpret_cast<GetApiBase>(dlsym(library, "OrtGetApiBase"));
    if (base == nullptr) return jni->NewStringUTF((name + " is not ONNX Runtime").c_str());
    const OrtApi* found = base()->GetApi(ORT_API_VERSION);
    if (found == nullptr) {
        std::string message = name + " is ONNX Runtime " + base()->GetVersionString() + ", older than 1." + std::to_string(ORT_API_VERSION);
        return jni->NewStringUTF(message.c_str());
    }
    OrtStatus* status = found->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "deepfilter", &environment);
    if (status == nullptr) status = found->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &processor);
    if (status != nullptr) {
        std::string message = std::string("ONNX Runtime: ") + found->GetErrorMessage(status);
        found->ReleaseStatus(status);
        return jni->NewStringUTF(message.c_str());
    }
    api = found;
    return nullptr;
}

// A session for the model [graph], whose weights kept outside it are [names] with their
// [data] (direct buffers), [shapes] and ONNX [types]; they are copied into the session.
// It is run on one processor thread, as the iPhone app runs its model.
JNIEXPORT jlong JNICALL Java_app_takes_deepfilter_OnnxSession_create(
        JNIEnv* jni, jclass, jbyteArray graph, jobjectArray names, jobjectArray data, jobjectArray shapes, jintArray types,
        jobjectArray inputs, jobjectArray outputs) {
    auto session = namedSession(jni, inputs, outputs);

    std::vector<std::string> initializerNames = strings(jni, names);
    std::vector<const char*> initializerPointers;
    Values initializers;
    jsize count = jni->GetArrayLength(data);
    std::vector<jint> typeList(count);
    jni->GetIntArrayRegion(types, 0, count, typeList.data());
    for (jsize i = 0; i < count; i++) {
        size_t size = elementSize(typeList[i]);
        if (size == 0) {
            raise(jni, "weights of unknown type " + std::to_string(typeList[i]));
            return 0;
        }
        OrtValue* value = nullptr;
        jobject buffer = jni->GetObjectArrayElement(data, i);
        auto shape = static_cast<jlongArray>(jni->GetObjectArrayElement(shapes, i));
        bool made = wrap(jni, buffer, shape, static_cast<ONNXTensorElementDataType>(typeList[i]), size, 1, &value);
        jni->DeleteLocalRef(buffer);
        jni->DeleteLocalRef(shape);
        if (!made) return 0;
        initializers.list.push_back(value);
        initializerPointers.push_back(initializerNames[i].c_str());
    }

    OrtSessionOptions* options = sessionOptions(jni, 1);
    if (options == nullptr) return 0;
    std::unique_ptr<OrtSessionOptions, void (*)(OrtSessionOptions*)> optionsOwner(options, [](OrtSessionOptions* o) { api->ReleaseSessionOptions(o); });
    if (!initializers.list.empty() &&
        failed(jni, api->AddExternalInitializers(options, initializerPointers.data(), initializers.list.data(), initializers.list.size()))) {
        return 0;
    }
    jsize length = jni->GetArrayLength(graph);
    jbyte* bytes = jni->GetByteArrayElements(graph, nullptr);
    OrtStatus* status = api->CreateSessionFromArray(environment, bytes, static_cast<size_t>(length), options, &session->session);
    jni->ReleaseByteArrayElements(graph, bytes, JNI_ABORT);
    if (failed(jni, status)) return 0;
    return reinterpret_cast<jlong>(session.release());
}

// A session for the model in the file at [path], with its weights inside it or in files beside it, run on
// [threads] processor threads. When [expected] is not null, the [expected] bytes at [offset] are replaced by
// [replacement] first, in a private copy-on-write mapping of the file, which stays as it is on disk: a fixed
// dimension of a published graph changes without a second copy of its weights.
JNIEXPORT jlong JNICALL Java_app_takes_deepfilter_OnnxSession_createFromFile(
        JNIEnv* jni, jclass, jstring path, jlong offset, jbyteArray expected, jbyteArray replacement, jint threads,
        jobjectArray inputs, jobjectArray outputs) {
    auto session = namedSession(jni, inputs, outputs);
    OrtSessionOptions* options = sessionOptions(jni, threads);
    if (options == nullptr) return 0;
    std::unique_ptr<OrtSessionOptions, void (*)(OrtSessionOptions*)> optionsOwner(options, [](OrtSessionOptions* o) { api->ReleaseSessionOptions(o); });
    const char* chars = jni->GetStringUTFChars(path, nullptr);
    std::string file = chars;
    jni->ReleaseStringUTFChars(path, chars);
    if (expected == nullptr) {
        if (failed(jni, api->CreateSession(environment, file.c_str(), options, &session->session))) return 0;
        return reinterpret_cast<jlong>(session.release());
    }
    jsize length = jni->GetArrayLength(expected);
    if (replacement == nullptr || jni->GetArrayLength(replacement) != length) {
        raise(jni, "the replacement must be as long as the bytes it replaces");
        return 0;
    }
    int descriptor = ::open(file.c_str(), O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        raise(jni, "cannot open " + file);
        return 0;
    }
    struct stat status {};
    if (fstat(descriptor, &status) != 0 || offset < 0 || offset + length > status.st_size) {
        ::close(descriptor);
        raise(jni, file + " is shorter than expected");
        return 0;
    }
    auto size = static_cast<size_t>(status.st_size);
    void* mapped = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_PRIVATE, descriptor, 0);
    ::close(descriptor);
    if (mapped == MAP_FAILED) {
        raise(jni, "cannot map " + file);
        return 0;
    }
    jbyte* bytes = static_cast<jbyte*>(mapped) + offset;
    std::vector<jbyte> want(length), put(length);
    jni->GetByteArrayRegion(expected, 0, length, want.data());
    jni->GetByteArrayRegion(replacement, 0, length, put.data());
    if (memcmp(bytes, want.data(), static_cast<size_t>(length)) != 0) {
        munmap(mapped, size);
        raise(jni, file + " does not hold the expected bytes at " + std::to_string(offset));
        return 0;
    }
    memcpy(bytes, put.data(), static_cast<size_t>(length));
    OrtStatus* made = api->CreateSessionFromArray(environment, mapped, size, options, &session->session);
    munmap(mapped, size);
    if (failed(jni, made)) return 0;
    return reinterpret_cast<jlong>(session.release());
}

// Runs the session on [inputs] (direct FloatBuffers of [inputShapes]) and copies its
// results into [outputs] (direct FloatBuffers, each exactly as large as its result), in
// the order the names were given.
JNIEXPORT void JNICALL Java_app_takes_deepfilter_OnnxSession_execute(
        JNIEnv* jni, jclass, jlong handle, jobjectArray inputs, jobjectArray inputShapes, jobjectArray outputs) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (static_cast<size_t>(jni->GetArrayLength(inputs)) != session->inputs.size() ||
        static_cast<size_t>(jni->GetArrayLength(outputs)) != session->outputs.size()) {
        raise(jni, "wrong number of tensors");
        return;
    }
    Values in, out;
    for (size_t i = 0; i < session->inputs.size(); i++) {
        OrtValue* value = nullptr;
        jobject buffer = jni->GetObjectArrayElement(inputs, static_cast<jsize>(i));
        auto shape = static_cast<jlongArray>(jni->GetObjectArrayElement(inputShapes, static_cast<jsize>(i)));
        bool made = wrap(jni, buffer, shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, 4, 4, &value);
        jni->DeleteLocalRef(buffer);
        jni->DeleteLocalRef(shape);
        if (!made) return;
        in.list.push_back(value);
    }
    out.list.resize(session->outputs.size(), nullptr);
    if (failed(jni, api->Run(session->session, nullptr, session->inputNames.data(), in.list.data(), in.list.size(),
                             session->outputNames.data(), session->outputNames.size(), out.list.data()))) {
        return;
    }
    for (size_t i = 0; i < session->outputs.size(); i++) {
        OrtTensorTypeAndShapeInfo* info = nullptr;
        if (failed(jni, api->GetTensorTypeAndShape(out.list[i], &info))) return;
        ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
        size_t count = 0;
        OrtStatus* status = api->GetTensorElementType(info, &type);
        if (status == nullptr) status = api->GetTensorShapeElementCount(info, &count);
        api->ReleaseTensorTypeAndShapeInfo(info);
        if (failed(jni, status)) return;
        void* data = nullptr;
        if (failed(jni, api->GetTensorMutableData(out.list[i], &data))) return;
        jobject buffer = jni->GetObjectArrayElement(outputs, static_cast<jsize>(i));
        void* target = jni->GetDirectBufferAddress(buffer);
        jlong capacity = jni->GetDirectBufferCapacity(buffer);
        jni->DeleteLocalRef(buffer);
        if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || target == nullptr || capacity != static_cast<jlong>(count)) {
            raise(jni, "the network returned " + std::to_string(count) + " values for " + session->outputs[i] +
                       ", expected " + std::to_string(capacity) + " floats");
            return;
        }
        memcpy(target, data, count * 4);
    }
}

JNIEXPORT void JNICALL Java_app_takes_deepfilter_OnnxSession_destroy(JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    api->ReleaseSession(session->session);
    delete session;
}

}  // extern "C"
