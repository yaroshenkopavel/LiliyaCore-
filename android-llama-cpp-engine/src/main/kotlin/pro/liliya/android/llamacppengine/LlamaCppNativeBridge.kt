package pro.liliya.android.llamacppengine

/**
 * Narrow JNI entry point for the pinned llama.cpp backend.
 *
 * Slice 7 intentionally exposes no generic path, byte-buffer, or native-handle API here.
 * Model load/inference/close operations are added only behind reviewed ownership contracts.
 */
internal object LlamaCppNativeBridge {
    init {
        System.loadLibrary("liliya-llama-jni")
    }

    external fun nativeLinkProbe(): Int
}
