package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidLocalModelImportTaskSnapshot {
    data object Idle : ProductionAndroidLocalModelImportTaskSnapshot

    data class InFlight(
        val requestId: Long
    ) : ProductionAndroidLocalModelImportTaskSnapshot

    data class Completed(
        val requestId: Long,
        val result: ProductionAndroidLocalModelSelectionResult
    ) : ProductionAndroidLocalModelImportTaskSnapshot
}

internal sealed interface ProductionAndroidLocalModelImportTaskRequestResult {
    data class Started(val requestId: Long) : ProductionAndroidLocalModelImportTaskRequestResult
    data object Busy : ProductionAndroidLocalModelImportTaskRequestResult
}

internal fun interface ProductionAndroidLocalModelImportExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope owner for one local-model import.
 *
 * Import Task != Model Trust.
 * Import Task != Model Compatibility Policy.
 * Import Task != Runtime Provisioning.
 *
 * The task retains a terminal result until exact request-id consumption so Activity recreation
 * cannot interrupt an import or lose its outcome. It never replays an import.
 */
internal class ProductionAndroidLocalModelImportTask(
    private val executor: ProductionAndroidLocalModelImportExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var snapshot: ProductionAndroidLocalModelImportTaskSnapshot =
        ProductionAndroidLocalModelImportTaskSnapshot.Idle
    private val listeners = mutableListOf<(ProductionAndroidLocalModelImportTaskSnapshot.Completed) -> Unit>()

    @Synchronized
    fun request(
        importModel: () -> ProductionAndroidLocalModelSelectionResult,
        listener: (ProductionAndroidLocalModelImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidLocalModelImportTaskRequestResult {
        if (snapshot !is ProductionAndroidLocalModelImportTaskSnapshot.Idle) {
            return ProductionAndroidLocalModelImportTaskRequestResult.Busy
        }

        val requestId = nextRequestId++
        snapshot = ProductionAndroidLocalModelImportTaskSnapshot.InFlight(requestId)
        listeners += listener

        try {
            executor.execute {
                val result = try {
                    importModel()
                } catch (_: Exception) {
                    ProductionAndroidLocalModelSelectionResult.Failed
                }
                complete(requestId, result)
            }
        } catch (_: Exception) {
            complete(requestId, ProductionAndroidLocalModelSelectionResult.Failed)
        }

        return ProductionAndroidLocalModelImportTaskRequestResult.Started(requestId)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidLocalModelImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidLocalModelImportTaskSnapshot {
        val current = snapshot
        if (current is ProductionAndroidLocalModelImportTaskSnapshot.InFlight) {
            listeners += listener
        }
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = snapshot as? ProductionAndroidLocalModelImportTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false
        snapshot = ProductionAndroidLocalModelImportTaskSnapshot.Idle
        return true
    }

    private fun complete(
        requestId: Long,
        result: ProductionAndroidLocalModelSelectionResult
    ) {
        val completed = ProductionAndroidLocalModelImportTaskSnapshot.Completed(requestId, result)
        val callbacks = synchronized(this) {
            val current = snapshot as? ProductionAndroidLocalModelImportTaskSnapshot.InFlight
            if (current == null || current.requestId != requestId) return
            snapshot = completed
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { listener ->
            runCatching { listener(completed) }
        }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidLocalModelImportExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidLocalModelImportExecutor { block -> executor.execute(block) }
        }
    }
}
