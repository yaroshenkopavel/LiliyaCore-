package pro.liliya.app

import java.util.concurrent.Executors
import pro.liliya.android.runtime.ProductChatResult

internal sealed interface ProductionAndroidAppChatTaskOutcome {
    data class Result(
        val value: ProductChatResult
    ) : ProductionAndroidAppChatTaskOutcome

    data object Failed : ProductionAndroidAppChatTaskOutcome
}

internal sealed interface ProductionAndroidAppChatTaskSnapshot {
    data object Idle : ProductionAndroidAppChatTaskSnapshot

    data class InFlight(
        val requestId: Long,
        val message: String
    ) : ProductionAndroidAppChatTaskSnapshot

    data class Completed(
        val requestId: Long,
        val message: String,
        val outcome: ProductionAndroidAppChatTaskOutcome
    ) : ProductionAndroidAppChatTaskSnapshot
}

internal sealed interface ProductionAndroidAppChatTaskRequestResult {
    data class Started(
        val requestId: Long
    ) : ProductionAndroidAppChatTaskRequestResult

    data class Busy(
        val current: ProductionAndroidAppChatTaskSnapshot
    ) : ProductionAndroidAppChatTaskRequestResult
}

internal fun interface ProductionAndroidAppChatExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope owner for one product chat request at a time.
 *
 * The terminal result is retained until an Activity explicitly consumes the matching request id.
 * This lets a recreated Activity reattach without replaying inference or losing a result that
 * completed while the previous Activity instance was being destroyed.
 */
internal class ProductionAndroidAppChatTask(
    private val executor: ProductionAndroidAppChatExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var state: ProductionAndroidAppChatTaskSnapshot =
        ProductionAndroidAppChatTaskSnapshot.Idle
    private val listeners = mutableListOf<(ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit>()

    @Synchronized
    fun snapshot(): ProductionAndroidAppChatTaskSnapshot = state

    @Synchronized
    fun request(
        message: String,
        send: (String) -> ProductChatResult,
        listener: (ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidAppChatTaskRequestResult {
        val normalized = message.trim()
        require(normalized.isNotEmpty()) { "message must not be blank" }

        if (state !is ProductionAndroidAppChatTaskSnapshot.Idle) {
            return ProductionAndroidAppChatTaskRequestResult.Busy(state)
        }

        val requestId = nextRequestId++
        state = ProductionAndroidAppChatTaskSnapshot.InFlight(requestId, normalized)
        listeners += listener

        try {
            executor.execute {
                val outcome = try {
                    ProductionAndroidAppChatTaskOutcome.Result(send(normalized))
                } catch (_: Exception) {
                    ProductionAndroidAppChatTaskOutcome.Failed
                }
                complete(requestId, normalized, outcome)
            }
        } catch (_: Exception) {
            complete(
                requestId,
                normalized,
                ProductionAndroidAppChatTaskOutcome.Failed
            )
        }

        return ProductionAndroidAppChatTaskRequestResult.Started(requestId)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidAppChatTaskSnapshot {
        val current = state
        if (current is ProductionAndroidAppChatTaskSnapshot.InFlight) {
            listeners += listener
        }
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = state as? ProductionAndroidAppChatTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false

        state = ProductionAndroidAppChatTaskSnapshot.Idle
        listeners.clear()
        return true
    }

    private fun complete(
        requestId: Long,
        message: String,
        outcome: ProductionAndroidAppChatTaskOutcome
    ) {
        val completed: ProductionAndroidAppChatTaskSnapshot.Completed
        val delivery: List<(ProductionAndroidAppChatTaskSnapshot.Completed) -> Unit>
        synchronized(this) {
            val current = state as? ProductionAndroidAppChatTaskSnapshot.InFlight ?: return
            if (current.requestId != requestId || current.message != message) return

            completed = ProductionAndroidAppChatTaskSnapshot.Completed(
                requestId = requestId,
                message = message,
                outcome = outcome
            )
            state = completed
            delivery = listeners.toList()
            listeners.clear()
        }

        delivery.forEach { listener ->
            try {
                listener(completed)
            } catch (_: Exception) {
                // A stale Activity callback must not block another observer or lose the result.
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
