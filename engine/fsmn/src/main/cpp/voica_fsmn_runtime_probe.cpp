#include <jni.h>
#include <dlfcn.h>
#include <onnxruntime_c_api.h>

#include <string>

namespace {

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

using OrtGetApiBaseFunction = const OrtApiBase* (*)();

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnOrtRuntimeProbe_nativeOrtVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    void* handle = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        ThrowIllegalState(
            env,
            std::string("Unable to load the APK ONNX Runtime: ") +
                (error == nullptr ? "unknown dlopen error" : error));
        return nullptr;
    }

    dlerror();
    auto get_api_base = reinterpret_cast<OrtGetApiBaseFunction>(dlsym(handle, "OrtGetApiBase"));
    const char* symbol_error = dlerror();
    if (symbol_error != nullptr || get_api_base == nullptr) {
        std::string message = "APK ONNX Runtime does not export OrtGetApiBase";
        if (symbol_error != nullptr) {
            message += ": ";
            message += symbol_error;
        }
        dlclose(handle);
        ThrowIllegalState(env, message);
        return nullptr;
    }

    const OrtApiBase* api_base = get_api_base();
    if (api_base == nullptr) {
        dlclose(handle);
        ThrowIllegalState(env, "OrtGetApiBase returned null");
        return nullptr;
    }

    const OrtApi* api = api_base->GetApi(ORT_API_VERSION);
    if (api == nullptr) {
        dlclose(handle);
        ThrowIllegalState(
            env,
            "APK ONNX Runtime does not support the API version required by the FSMN candidate");
        return nullptr;
    }

    const char* version = api_base->GetVersionString();
    if (version == nullptr || version[0] == '\0') {
        dlclose(handle);
        ThrowIllegalState(env, "APK ONNX Runtime returned an empty version string");
        return nullptr;
    }

    const std::string version_copy(version);
    dlclose(handle);
    return env->NewStringUTF(version_copy.c_str());
}
