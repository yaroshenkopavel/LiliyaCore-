package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeHostBootstrapResult
import pro.liliya.android.runtime.AndroidProductRuntimeHostSession
import pro.liliya.android.runtime.ProductChatGenerationMode
import pro.liliya.android.runtime.ProductChatRequest
import pro.liliya.android.runtime.ProductChatResult

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
    fun close()
}

fun interface ProductionAndroidAppRuntimeStartPort {
    fun start(): ProductionAndroidAppRuntimeStartResult

    companion object {
        fun fromProductRuntimeBootstrap(
            bootstrap: () -> AndroidProductRuntimeHostBootstrapResult
        ): ProductionAndroidAppRuntimeStartPort =
            ProductionAndroidAppRuntimeStartPort {
                try {
                    when (val result = bootstrap()) {
                        is AndroidProductRuntimeHostBootstrapResult.Ready -> {
                            val session = result.session
                            ProductionAndroidAppRuntimeStartResult.Ready(
                                ProductRuntimeSessionAdapter(session)
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

private class ProductRuntimeSessionAdapter(
    private val session: AndroidProductRuntimeHostSession
) : ProductionAndroidAppRuntimeSession {
    override fun send(text: String): ProductChatResult {
        val chat = session.chat()
            ?: return ProductChatResult.Rejected(
                pro.liliya.android.runtime.ProductChatFailure.HEART_NOT_READY
            )
        return chat.send(
            ProductChatRequest(
                text = text,
                mode = ProductChatGenerationMode.ONE_SHOT
            )
        )
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
