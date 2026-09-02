package pro.liliya.android.semanticprovider

/** Narrow structural JNI protocol for the pinned llama.cpp embedding backend. */
internal object SemanticNativeBridge {
    const val LOAD_RESOURCE_REJECTED = -1L
    const val LOAD_UNSUPPORTED = -2L
    const val LOAD_REJECTED = -3L
    const val LOAD_PROVIDER_FAILED = -4L

    const val EMBED_OK: Byte = 0
    const val EMBED_RESOURCE_REJECTED: Byte = 1
    const val EMBED_REQUEST_REJECTED: Byte = 2
    const val EMBED_STALE_SESSION: Byte = 3
    const val EMBED_OPERATION_FAILED: Byte = 4
    const val EMBED_PROVIDER_FAILED: Byte = 5

    const val CLOSE_OK = 0
    const val CLOSE_FAILED = 1
    const val CLOSE_PROVIDER_FAILED = 2

    init {
        System.loadLibrary("liliya-semantic-jni")
    }

    external fun nativeLinkProbe(): Int

    external fun nativeLoad(
        sourcePathUtf8: ByteArray,
        contextTokens: Int,
        batchTokens: Int,
        threadCount: Int,
        maxInputUtf8Bytes: Int,
        useMmap: Boolean
    ): Long

    /** First byte is a structural status; success payload is 384 little-endian float32 values. */
    external fun nativeEmbed(
        nativeSessionId: Long,
        inputUtf8: ByteArray
    ): ByteArray

    external fun nativeClose(nativeSessionId: Long): Int
}
