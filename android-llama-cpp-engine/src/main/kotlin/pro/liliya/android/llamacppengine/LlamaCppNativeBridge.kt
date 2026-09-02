package pro.liliya.android.llamacppengine

/** Narrow structural JNI protocol for the pinned llama.cpp backend. */
internal object LlamaCppNativeBridge {
    const val LOAD_RESOURCE_REJECTED = -1L
    const val LOAD_UNSUPPORTED = -2L
    const val LOAD_REJECTED = -3L
    const val LOAD_PROVIDER_FAILED = -4L

    const val INFER_OK = "ok"
    const val INFER_RESOURCE_REJECTED = "resource_rejected"
    const val INFER_REQUEST_REJECTED = "request_rejected"
    const val INFER_STALE_SESSION = "stale_session"
    const val INFER_OPERATION_FAILED = "operation_failed"
    const val INFER_PROVIDER_FAILED = "provider_failed"

    const val CLOSE_OK = 0
    const val CLOSE_FAILED = 1
    const val CLOSE_PROVIDER_FAILED = 2

    init {
        System.loadLibrary("liliya-llama-jni")
    }

    external fun nativeLinkProbe(): Int

    external fun nativeLoad(
        sourcePath: String,
        contextTokens: Int,
        maxPromptTokens: Int,
        maxGeneratedTokens: Int,
        batchTokens: Int,
        microBatchTokens: Int,
        threadCount: Int,
        useMmap: Boolean
    ): Long

    /** Returns [structural-status, private-output-or-null]. */
    external fun nativeInfer(
        nativeSessionId: Long,
        prompt: String,
        maxOutputChars: Int
    ): Array<String?>

    external fun nativeClose(nativeSessionId: Long): Int
}
