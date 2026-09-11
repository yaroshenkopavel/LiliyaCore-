package pro.liliya.app

import java.util.concurrent.Executors

internal fun interface ProductionAndroidAppChatExecutor {
    fun execute(block: () -> Unit)
}

internal sealed interface ProductionAndroidAppChatExecutionResult<out T> {
    data class Completed<T>(val value: T) : ProductionAndroidAppChatExecutionResult<T>
    data object Failed : ProductionAndroidAppChatExecutionResult<Nothing>
}

internal sealed interface ProductionAndroidAppChatTaskState<out T> {
    val requestId: Long
    val message: String

    data class InFlight(
        override val requestId: Long,
        override val message: String
    ) : ProductionAndroidAppChatTaskState<Nothing>

    data class Terminal<T>(
        override val requestId: Long,
        override val message: String,
        val result: ProductionAndroidAppChatExecutionResult<T>
    ) : ProductionAndroidAppChatTaskState<T>
}

internal sealed interface ProductionAndroidAppChatSubmitResult {
    data class Started(val requestId: Long) : ProductionAndroidAppChatSubmitResult
    data class Busy(
        val requestId: Long,
        val message: String
    ) : ProductionAndroidAppChatSubmitResult
}

/**
 * Application-scope owner for one product chat request at a time.
 *
 * The request itself is never replayed. A terminal result remains available until the live UI
 * explicitly consumes it, closing the Activity recreation race where the old Activity disappears
 * before completion and the new Activity has not subscribed yet.
 */
internal class ProductionAndroidAppChatTask<T>(
    private val executor: ProductionAndroidAppChatExecutor = productionExecutor()
) {
    private data class Active(
        val requestId: Long,
        val message: String
    )

    private val observers = mutableListOf<(ProductionAndroidAppChatTaskState<T>) -> Unit>()
    private var nextRequestId = 1L
    private var active: Active? = null
    private var terminal: ProductionAndroidAppChatTaskState.Terminal<T>? = null

    @Synchronized
    fun submit(
        message: String,
        operation: () -> T
    ): ProductionAndroidAppChatSubmitResult {
        val normalized = message.trim()
        require(normalized.isNotEmpty()) { "message must not be blank" }

        currentStateUnsafe()?.let { state ->
            return ProductionAndroidAppChatSubmitResult.Busy(
                requestId = state.requestId,
                message = state.message
            )
        }

        val request = Active(
            requestId = nextRequestId++,
            message = normalized
        )
        active = request

        try {
            executor.execute {
                val result = try {
                    ProductionAndroidAppChatExecutionResult.Completed(operation())
                } catch (_: Exception) {
                    ProductionAndroidAppChatExecutionResult.Failed
                }
                complete(request, result)
            }
        } catch (_: Exception) {
            complete(request, ProductionAndroidAppChatExecutionResult.Failed)
        }

        return ProductionAndroidAppChatSubmitResult.Started(request.requestId)
    }

    @Synchronized
    fun observe(
        observer: (ProductionAndroidAppChatTaskState<T>) -> Unit
    ): ProductionAndroidAppChatTaskState<T>? {
        if (!observers.contains(observer)) observers += observer
        return currentStateUnsafe()
    }

    @Synchronized
    fun removeObserver(observer: (ProductionAndroidAppChatTaskState<T>) -> Unit) {
        observers.remove(observer)
    }

    @Synchronized
    fun currentState(): ProductionAndroidAppChatTaskState<T>? = currentStateUnsafe()

    @Synchronized
    fun consumeTerminal(requestId: Long): ProductionAndroidAppChatTaskState.Terminal<T>? {
        val current = terminal ?: return null
        if (current.requestId != requestId) return null
        terminal = null
        return current
    }

    private fun complete(
        request: Active,
        result: ProductionAndroidAppChatExecutionResult<T>
    ) {
        val state: ProductionAndroidAppChatTaskState.Terminal<T>
        val listeners: List<(ProductionAndroidAppChatTaskState<T>) -> Unit>
        synchronized(this) {
            val current = active ?: return
            if (current.requestId != request.requestId) return

            state = ProductionAndroidAppChatTaskState.Terminal(
                requestId = request.requestId,
                message = request.message,
                result = result
            )
            active = null
            terminal = state
            listeners = observers.toList()
        }

        listeners.forEach { observer ->
            try {
                observer(state)
            } catch (_: Exception) {
                // One stale Activity observer must not block delivery or terminal handoff.
            }
        }
    }

    private fun currentStateUnsafe(): ProductionAndroidAppChatTaskState<T>? =
        terminal ?: active?.let {
            ProductionAndroidAppChatTaskState.InFlight(
                requestId = it.requestId,
                message = it.message
            )
        }

    private companion object {
        fun productionExecutor(): ProductionAndroidAppChatExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidAppChatExecutor { block -> executor.execute(block) }
        }
    }
}
