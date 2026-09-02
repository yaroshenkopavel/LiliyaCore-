#include <jni.h>

#include "llama.h"

namespace {

constexpr jint LINK_PROBE_OK = 1;
constexpr jint LINK_PROBE_FAILED = 0;

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_pro_liliya_android_llamacppengine_LlamaCppNativeBridge_nativeLinkProbe(
    JNIEnv *,
    jobject
) {
    // This is intentionally not a model load. It only proves that the JNI library
    // resolves a concrete symbol from the pinned llama.cpp build at runtime.
    return llama_max_devices() > 0 ? LINK_PROBE_OK : LINK_PROBE_FAILED;
}
