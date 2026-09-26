package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeHostBootstrapResult
import pro.liliya.android.runtime.AndroidProductRuntimeHostSession
import pro.liliya.android.runtime.ProductChatGenerationMode
import pro.liliya.android.runtime.ProductChatRequest
import pro.liliya.android.runtime.ProductChatResult
import pro.liliya.android.runtime.ProductConversationCommitStatus
import pro.liliya.android.runtime.ProductConversationResult
import pro.liliya.android.runtime.ProductConversationSnapshot

enum class ProductionAndroidAppRuntimeState {
    CONFIGURATION_REQUIRED,
    STARTING,
    READY,
    FAILED,
    CLOSED
}

enum class ProductionAndroidAppRuntimeFailure {
    BOOTSTRAP_REJECTED,
    CHAT_UNAVAILABLE,
    INTERNAL_FAILURE
}

sealed interface ProductionAndroidAppRuntimeStartResult {
    data class Ready(
        val session: ProductionAndroidAppRuntimeSession
    ) : ProductionAndroidAppRuntimeStartResult

    data class Rejected(
        val reason: ProductionAndroidAppRuntimeFailure
    ) : ProductionAndroidAppRuntimeStartResult
}

interface ProductionAndroidAppRuntimeSession {
    fun send(text: String): ProductChatResult
    fun conversationSnapshot(): ProductConversationSnapshot? = null
    fun newConversation(): Boolean = false
    fun close()
}

fun interface ProductionAndroidAppRuntimeStartPort {
    fun start(): ProductionAndroidAppRuntimeStartResult

    companion object {
        fun fromProductRuntimeBootstrap(
            bootstrap: () -> AndroidProductRuntimeHostBootstrapResult
        ): ProductionAndroidAppRuntimeStartPort =
            fromProductRuntimeBootstrap(
                bootstrap = bootstrap,
                conversationSessions = ProductionAndroidConversationSessionState
            )

        internal fun fromProductRuntimeBootstrap(
            bootstrap: () -> AndroidProductRuntimeHostBootstrapResult,
            conversationSessions: ProductionAndroidConversationSessionPort
        ): ProductionAndroidAppRuntimeStartPort =
            ProductionAndroidAppRuntimeStartPort {
                try {
                    when (val result = bootstrap()) {
                        is AndroidProductRuntimeHostBootstrapResult.Ready -> {
                            val session = result.session
                            ProductionAndroidAppRuntimeStartResult.Ready(
                                ProductRuntimeSessionAdapter(
                                    session = session,
                                    conversationSessions = conversationSessions
                                )
                            )
                        }
                        is AndroidProductRuntimeHostBootstrapResult.Rejected ->
                            ProductionAndroidAppRuntimeStartResult.Rejected(
                                ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED
                            )
                    }
                } catch (_: Exception) {
                    ProductionAndroidAppRuntimeStartResult.Rejected(
                        ProductionAndroidAppRuntimeFailure.INTERNAL_FAILURE
                    )
                }
            }
    }
}


internal fun ProductConversationResult.toAppProductChatResult(): ProductChatResult =
    when (this) {
        is ProductConversationResult.Completed ->
            if (
                conversationCommit ==
                ProductConversationCommitStatus.NOT_RETAINED_PERSISTENCE_FAILURE
            ) {
                ProductChatResult.Rejected(
                    pro.liliya.android.runtime.ProductChatFailure.INTERNAL_FAILURE
                )
            } else {
                ProductChatResult.Completed(
                    reply = reply,
                    streamedChunkCount = streamedChunkCount,
                    streamedCharacterCount = streamedCharacterCount
                )
            }
        is ProductConversationResult.Rejected ->
            ProductChatResult.Rejected(reason)
    }


private class ProductRuntimeSessionAdapter(
    private val session: AndroidProductRuntimeHostSession,
    private val conversationSessions: ProductionAndroidConversationSessionPort
) : ProductionAndroidAppRuntimeSession {
    override fun send(text: String): ProductChatResult {
        val conversation = currentConversation()
            ?: return ProductChatResult.Rejected(
                pro.liliya.android.runtime.ProductChatFailure.HEART_NOT_READY
            )

        return conversation.send(
            ProductChatRequest(
                text = text,
                mode = ProductChatGenerationMode.ONE_SHOT
            )
        ).toAppProductChatResult()
    }

    override fun conversationSnapshot(): ProductConversationSnapshot? =
        currentConversation()?.snapshot()

    override fun newConversation(): Boolean {
        val current = conversationSessions.currentSessionId() ?: return false
        val candidate = conversationSessions.freshSessionId()
        if (candidate.isBlank() || candidate == current) return false

        val opened = session.conversation(
            sessionId = candidate,
            maxRetainedMessages = PRODUCT_CONVERSATION_MAX_RETAINED_MESSAGES,
            maxRetainedCharacters = PRODUCT_CONVERSATION_MAX_RETAINED_CHARACTERS,
            maxMessageCharacters = PRODUCT_CONVERSATION_MAX_MESSAGE_CHARACTERS
        ) ?: return false

        if (opened.snapshot().messages.isNotEmpty()) return false
        return conversationSessions.commitSessionId(candidate)
    }

    private fun currentConversation() = conversationSessions.currentSessionId()?.let { sessionId ->
        session.conversation(
            sessionId = sessionId,
            maxRetainedMessages = PRODUCT_CONVERSATION_MAX_RETAINED_MESSAGES,
            maxRetainedCharacters = PRODUCT_CONVERSATION_MAX_RETAINED_CHARACTERS,
            maxMessageCharacters = PRODUCT_CONVERSATION_MAX_MESSAGE_CHARACTERS
        )
    }

    private companion object {
        const val PRODUCT_CONVERSATION_MAX_RETAINED_MESSAGES = 64
        const val PRODUCT_CONVERSATION_MAX_RETAINED_CHARACTERS = 48 * 1024
        const val PRODUCT_CONVERSATION_MAX_MESSAGE_CHARACTERS = 8 * 1024
    }

    override fun close() {
        session.close()
    }
}

class ProductionAndroidAppRuntimeOwner {
    @Volatile
    private var state: ProductionAndroidAppRuntimeState =
        ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED

    @Volatile
    private var failure: ProductionAndroidAppRuntimeFailure? = null

    private var session: ProductionAndroidAppRuntimeSession? = null

    fun state(): ProductionAndroidAppRuntimeState = state

    fun failure(): ProductionAndroidAppRuntimeFailure? = failure

    @Synchronized
    fun start(
        port: ProductionAndroidAppRuntimeStartPort?
    ): ProductionAndroidAppRuntimeState {
        when (state) {
            ProductionAndroidAppRuntimeState.READY,
            ProductionAndroidAppRuntimeState.FAILED,
            ProductionAndroidAppRuntimeState.CLOSED,
            ProductionAndroidAppRuntimeState.STARTING -> return state
            ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED -> Unit
        }

        if (port == null) {
            state = ProductionAndroidAppRuntimeState.CONFIGURATION_REQUIRED
            return state
        }

        state = ProductionAndroidAppRuntimeState.STARTING
        val result = try {
            port.start()
        } catch (_: Exception) {
            ProductionAndroidAppRuntimeStartResult.Rejected(
                ProductionAndroidAppRuntimeFailure.INTERNAL_FAILURE
            )
        }

        return when (result) {
            is ProductionAndroidAppRuntimeStartResult.Ready -> {
                session = result.session
                failure = null
                state = ProductionAndroidAppRuntimeState.READY
                state
            }
            is ProductionAndroidAppRuntimeStartResult.Rejected -> {
                session = null
                failure = result.reason
                state = ProductionAndroidAppRuntimeState.FAILED
                state
            }
        }
    }

    fun send(text: String): ProductChatResult {
        val current = synchronized(this) {
            if (state == ProductionAndroidAppRuntimeState.READY) session else null
        }
        return current?.send(text)
            ?: ProductChatResult.Rejected(
                pro.liliya.android.runtime.ProductChatFailure.HEART_NOT_READY
            )
    }

    fun conversationSnapshot(): ProductConversationSnapshot? {
        val current = synchronized(this) {
            if (state == ProductionAndroidAppRuntimeState.READY) session else null
        }
        return current?.conversationSnapshot()
    }

    fun newConversation(): Boolean {
        val current = synchronized(this) {
            if (state == ProductionAndroidAppRuntimeState.READY) session else null
        }
        return current?.newConversation() == true
    }

    @Synchronized
    fun close() {
        if (state == ProductionAndroidAppRuntimeState.CLOSED) return
        val current = session
        session = null
        state = ProductionAndroidAppRuntimeState.CLOSED
        try {
            current?.close()
        } catch (_: Exception) {
            // Cleanup is best-effort and exception text must not cross the host boundary.
        }
    }
}
