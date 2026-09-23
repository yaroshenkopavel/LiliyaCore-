package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidActivationTaskSnapshot {
    data object Idle : ProductionAndroidActivationTaskSnapshot
    data class InFlight(val requestId: Long) : ProductionAndroidActivationTaskSnapshot
    data class Completed(
        val requestId: Long,
        val result: ProductionAndroidActivationResult
    ) : ProductionAndroidActivationTaskSnapshot
}

internal sealed interface ProductionAndroidActivationTaskRequestResult {
    data class Started(val requestId: Long) : ProductionAndroidActivationTaskRequestResult
    data object Busy : ProductionAndroidActivationTaskRequestResult
}

internal fun interface ProductionAndroidActivationExecutor {
    fun execute(block: () -> Unit)
}

internal class ProductionAndroidActivationTask(
    private val executor: ProductionAndroidActivationExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var snapshot: ProductionAndroidActivationTaskSnapshot =
        ProductionAndroidActivationTaskSnapshot.Idle
    private val listeners = mutableListOf<
        (ProductionAndroidActivationTaskSnapshot.Completed) -> Unit
    >()

    @Synchronized
    fun request(
        activate: () -> ProductionAndroidActivationResult,
        listener: (ProductionAndroidActivationTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidActivationTaskRequestResult {
        if (snapshot !is ProductionAndroidActivationTaskSnapshot.Idle) {
            return ProductionAndroidActivationTaskRequestResult.Busy
        }
        val requestId = nextRequestId++
        snapshot = ProductionAndroidActivationTaskSnapshot.InFlight(requestId)
        listeners += listener

        try {
            executor.execute {
                val result = try {
                    activate()
                } catch (_: Exception) {
                    ProductionAndroidActivationResult.Failed
                }
                complete(requestId, result)
            }
        } catch (_: Exception) {
            complete(requestId, ProductionAndroidActivationResult.Failed)
        }
        return ProductionAndroidActivationTaskRequestResult.Started(requestId)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidActivationTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidActivationTaskSnapshot {
        val current = snapshot
        if (current is ProductionAndroidActivationTaskSnapshot.InFlight) listeners += listener
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = snapshot as? ProductionAndroidActivationTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false
        snapshot = ProductionAndroidActivationTaskSnapshot.Idle
        return true
    }

    private fun complete(requestId: Long, result: ProductionAndroidActivationResult) {
        val completed = ProductionAndroidActivationTaskSnapshot.Completed(requestId, result)
        val callbacks = synchronized(this) {
            val current = snapshot as? ProductionAndroidActivationTaskSnapshot.InFlight
            if (current == null || current.requestId != requestId) return
            snapshot = completed
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { listener -> runCatching { listener(completed) } }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidActivationExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidActivationExecutor { block -> executor.execute(block) }
        }
    }
}
