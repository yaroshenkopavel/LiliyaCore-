package pro.liliya.android.llamacppengine

/**
 * Narrow structural test adapter used by the separate Android instrumentation host.
 * The JNI bridge remains internal; the adapter exposes no model loading or arbitrary session operations.
 */
object LlamaCppNativeStructuralTestAdapter {
    const val INFER_STALE_SESSION: Byte = 3
    const val CLOSE_FAILED: Int = 1

    fun inferUnknownSession(promptUtf8: ByteArray, maxOutputChars: Int): ByteArray =
        LlamaCppNativeBridge.nativeInfer(Long.MAX_VALUE, promptUtf8, maxOutputChars)

    fun closeUnknownSession(): Int =
        LlamaCppNativeBridge.nativeClose(Long.MAX_VALUE)
}
