package pro.liliya.app

import java.util.concurrent.Executors
import pro.liliya.android.runtime.ProductChatResult

internal sealed interface ProductionAndroidAppChatTaskResult {
    data class Completed(
        val result: ProductChatResult
    ) : ProductionAndroidAppChatTaskResult

    data object Failed : ProductionAndroidAppChatTaskResult
}

internal enum class ProductionAndroidAppChatRequestDecision {
    STARTED,
    JOINED,
    BUSY,
    INVALID
}

internal fun interface ProductionAndroidAppChatExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope owner for one in-flight product chat request.
 *
 * The owner keeps only process-local presentation continuity: exact current message plus callbacks.
 * It does not persist, replay, retry, or mutate runtime/cognitive state on its own.
 */
internal class ProductionAndroidAppChatTask(
    private val executor: ProductionAndroidAppChatExecutor = productionExecutor()
) {
    private var inFlightMessage: String? = null
    private val callbacks = mutableListOf<(ProductionAndroidAppChatTaskResult) -> Unit>()

    @Synchronized
    fun request(
        message: String,
        send: () -> ProductChatResult,
        callback: (ProductionAndroidAppChatTaskResult) -> Unit
    ): ProductionAndroidAppChatRequestDecision {
        if (message.isBlank()) return ProductionAndroidAppChatRequestDecision.INVALID

        val current = inFlightMessage
        if (current != null) {
            return if (current == message) {
                callbacks += callback
                ProductionAndroidAppChatRequestDecision.JOINED
            } else {
                ProductionAndroidAppChatRequestDecision.BUSY
            }
        }

        inFlightMessage = message
        callbacks += callback
        try {
            executor.execute {
                val result = try {
                    ProductionAndroidAppChatTaskResult.Completed(send())
                } catch (_: Exception) {
                    ProductionAndroidAppChatTaskResult.Failed
                }
                complete(result)
            }
        } catch (_: Exception) {
            complete(ProductionAndroidAppChatTaskResult.Failed)
        }
        return ProductionAndroidAppChatRequestDecision.STARTED
    }

    @Synchronized
    fun currentMessage(): String? = inFlightMessage

    @Synchronized
    fun subscribeCurrent(
        callback: (ProductionAndroidAppChatTaskResult) -> Unit
    ): String? {
        val current = inFlightMessage ?: return null
        callbacks += callback
        return current
    }

    private fun complete(result: ProductionAndroidAppChatTaskResult) {
        val listeners = synchronized(this) {
            inFlightMessage = null
            callbacks.toList().also { callbacks.clear() }
        }
        listeners.forEach { listener ->
            try {
                listener(result)
            } catch (_: Exception) {
                // One stale Activity callback must not prevent delivery to a recreated Activity.
            }
        }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidAppChatExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidAppChatExecutor { block -> executor.execute(block) }
        }
    }
}
