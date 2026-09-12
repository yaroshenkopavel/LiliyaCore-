package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidProductAuthImportTaskSnapshot {
    data object Idle : ProductionAndroidProductAuthImportTaskSnapshot

    data class InFlight(val requestId: Long) : ProductionAndroidProductAuthImportTaskSnapshot

    data class Completed(
        val requestId: Long,
        val result: ProductionAndroidProductAuthImportResult
    ) : ProductionAndroidProductAuthImportTaskSnapshot
}

internal sealed interface ProductionAndroidProductAuthImportTaskRequestResult {
    data class Started(val requestId: Long) : ProductionAndroidProductAuthImportTaskRequestResult
    data object Busy : ProductionAndroidProductAuthImportTaskRequestResult
}

internal fun interface ProductionAndroidProductAuthImportExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope owner for one explicit Product Auth credential import.
 *
 * Import Task != Credential Storage.
 * Import Task != License Entitlement.
 * Import Task != Product Profile.
 *
 * The task retains only the typed terminal result and request identity. URI ownership stays with
 * the caller and plaintext credential bytes stay inside the bounded importer/encrypted store path.
 */
internal class ProductionAndroidProductAuthImportTask(
    private val executor: ProductionAndroidProductAuthImportExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var snapshot: ProductionAndroidProductAuthImportTaskSnapshot =
        ProductionAndroidProductAuthImportTaskSnapshot.Idle
    private val listeners = mutableListOf<
        (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
    >()

    @Synchronized
    fun request(
        importCredential: () -> ProductionAndroidProductAuthImportResult,
        listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidProductAuthImportTaskRequestResult {
        if (snapshot !is ProductionAndroidProductAuthImportTaskSnapshot.Idle) {
            return ProductionAndroidProductAuthImportTaskRequestResult.Busy
        }
        val requestId = nextRequestId++
        snapshot = ProductionAndroidProductAuthImportTaskSnapshot.InFlight(requestId)
        listeners += listener

        try {
            executor.execute {
                val result = try {
                    importCredential()
                } catch (_: Exception) {
                    ProductionAndroidProductAuthImportResult.Failed
                }
                complete(requestId, result)
            }
        } catch (_: Exception) {
            complete(requestId, ProductionAndroidProductAuthImportResult.Failed)
        }
        return ProductionAndroidProductAuthImportTaskRequestResult.Started(requestId)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidProductAuthImportTaskSnapshot {
        val current = snapshot
        if (current is ProductionAndroidProductAuthImportTaskSnapshot.InFlight) listeners += listener
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = snapshot as? ProductionAndroidProductAuthImportTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false
        snapshot = ProductionAndroidProductAuthImportTaskSnapshot.Idle
        return true
    }

    private fun complete(requestId: Long, result: ProductionAndroidProductAuthImportResult) {
        val completed = ProductionAndroidProductAuthImportTaskSnapshot.Completed(requestId, result)
        val callbacks = synchronized(this) {
            val current = snapshot as? ProductionAndroidProductAuthImportTaskSnapshot.InFlight
            if (current == null || current.requestId != requestId) return
            snapshot = completed
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { listener -> runCatching { listener(completed) } }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidProductAuthImportExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidProductAuthImportExecutor { block -> executor.execute(block) }
        }
    }
}
