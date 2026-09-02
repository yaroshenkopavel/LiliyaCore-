package pro.liliya.android.llamacppengine

/** Narrow structural JNI protocol for the pinned llama.cpp backend. */
internal object LlamaCppNativeBridge {
    const val LOAD_RESOURCE_REJECTED = -1L
    const val LOAD_UNSUPPORTED = -2L
    const val LOAD_REJECTED = -3L
    const val LOAD_PROVIDER_FAILED = -4L

    const val INFER_OK: Byte = 0
    const val INFER_RESOURCE_REJECTED: Byte = 1
    const val INFER_REQUEST_REJECTED: Byte = 2
    const val INFER_STALE_SESSION: Byte = 3
    const val INFER_OPERATION_FAILED: Byte = 4
    const val INFER_PROVIDER_FAILED: Byte = 5

    const val CLOSE_OK = 0
    const val CLOSE_FAILED = 1
    const val CLOSE_PROVIDER_FAILED = 2

    init {
        System.loadLibrary("liliya-llama-jni")
    }

    external fun nativeLinkProbe(): Int

    external fun nativeLoad(
        sourcePathUtf8: ByteArray,
        contextTokens: Int,
        maxPromptTokens: Int,
        maxGeneratedTokens: Int,
        batchTokens: Int,
        microBatchTokens: Int,
        threadCount: Int,
        maxPromptUtf8Bytes: Int,
        maxOutputUtf8Bytes: Int,
        useMmap: Boolean
    ): Long

    /** First byte is a structural status; remaining bytes are private UTF-8 output on success. */
    external fun nativeInfer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): ByteArray

    external fun nativeClose(nativeSessionId: Long): Int
}
