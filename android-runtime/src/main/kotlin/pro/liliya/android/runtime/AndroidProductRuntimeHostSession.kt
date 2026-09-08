package pro.liliya.android.runtime

enum class AndroidProductRuntimeAdmissionRevalidationFailure {
    LICENSE_DENIED,
    AUTHORITY_DENIED,
    ADMISSION_INTERNAL_FAILURE,
    SESSION_INACTIVE
}

sealed interface AndroidProductRuntimeAdmissionRevalidationResult {
    data object Retained : AndroidProductRuntimeAdmissionRevalidationResult

    data class Closed(
        val reason: AndroidProductRuntimeAdmissionRevalidationFailure,
        val cleanup: HeartRuntimeCloseResult?
    ) : AndroidProductRuntimeAdmissionRevalidationResult

    data object Inactive : AndroidProductRuntimeAdmissionRevalidationResult
}

internal interface AndroidProductRuntimeHostSessionBridge {
    fun state(): HeartRuntimeState
    fun chat(): ProductChatHost?
    fun conversation(
        maxRetainedMessages: Int,
        maxRetainedCharacters: Int,
        maxMessageCharacters: Int
    ): ProductConversationHost?
    fun learningFollowUp(): ProductLearningFollowUpHost?
    fun recoverSemantic(): AndroidProductRuntimeSemanticRecoveryResult
    fun close(): HeartRuntimeCloseResult
}

/**
 * Host-facing lifetime wrapper for one exact already-started Product Runtime.
 *
 * Revalidation is explicit only. This session does not fetch License state, evaluate policy,
 * authorize capabilities, poll, retry, restart or create another runtime.
 */
class AndroidProductRuntimeHostSession internal constructor(
    private val bridge: AndroidProductRuntimeHostSessionBridge
) {
    @Volatile
    private var active: Boolean = true

    fun state(): HeartRuntimeState =
        if (active) bridge.state() else HeartRuntimeState.CLOSED

    fun chat(): ProductChatHost? =
        if (active) bridge.chat() else null

    fun conversation(
        maxRetainedMessages: Int,
        maxRetainedCharacters: Int,
        maxMessageCharacters: Int
    ): ProductConversationHost? =
        if (active) {
            bridge.conversation(
                maxRetainedMessages = maxRetainedMessages,
                maxRetainedCharacters = maxRetainedCharacters,
                maxMessageCharacters = maxMessageCharacters
            )
        } else {
            null
        }

    fun learningFollowUp(): ProductLearningFollowUpHost? =
        if (active) bridge.learningFollowUp() else null

    fun recoverSemantic(): AndroidProductRuntimeSemanticRecoveryResult =
        if (active) {
            bridge.recoverSemantic()
        } else {
            AndroidProductRuntimeSemanticRecoveryResult.Failed
        }

    @Synchronized
    fun revalidate(
        freshAdmission: AndroidProductRuntimeAdmissionResult
    ): AndroidProductRuntimeAdmissionRevalidationResult {
        if (!active) {
            return AndroidProductRuntimeAdmissionRevalidationResult.Inactive
        }

        return when (freshAdmission) {
            is AndroidProductRuntimeAdmissionResult.Admitted ->
                AndroidProductRuntimeAdmissionRevalidationResult.Retained

            is AndroidProductRuntimeAdmissionResult.LicenseDenied ->
                closeForRevalidation(
                    AndroidProductRuntimeAdmissionRevalidationFailure.LICENSE_DENIED
                )

            is AndroidProductRuntimeAdmissionResult.Rejected ->
                closeForRevalidation(
                    when (freshAdmission.reason) {
                        AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED ->
                            AndroidProductRuntimeAdmissionRevalidationFailure.AUTHORITY_DENIED
                        AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE ->
                            AndroidProductRuntimeAdmissionRevalidationFailure.ADMISSION_INTERNAL_FAILURE
                    }
                )
        }
    }

    @Synchronized
    fun close(): HeartRuntimeCloseResult {
        if (!active) {
            return HeartRuntimeCloseResult.AlreadyClosed
        }
        active = false
        return bridge.close()
    }

    private fun closeForRevalidation(
        reason: AndroidProductRuntimeAdmissionRevalidationFailure
    ): AndroidProductRuntimeAdmissionRevalidationResult.Closed {
        active = false
        val cleanup = try {
            bridge.close()
        } catch (_: Exception) {
            null
        }
        return AndroidProductRuntimeAdmissionRevalidationResult.Closed(
            reason = reason,
            cleanup = cleanup
        )
    }

    override fun toString(): String =
        "AndroidProductRuntimeHostSession(active=$active,bridge=<redacted>)"
}
