package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidAppStartupTaskResult {
    data class Completed(
        val outcome: ProductionAndroidAppStartupOutcome
    ) : ProductionAndroidAppStartupTaskResult

    data object Failed : ProductionAndroidAppStartupTaskResult
}

internal fun interface ProductionAndroidAppStartupExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope execution owner for the potentially expensive product startup path.
 *
 * It deduplicates only concurrently in-flight startup requests. Completed outcomes are not cached,
 * so a later request can re-evaluate configuration while the existing startup coordinator/runtime
 * owner keep their own accepted one-shot and terminal-state semantics.
 */
internal class ProductionAndroidAppStartupTask(
    private val executor: ProductionAndroidAppStartupExecutor = productionExecutor()
) {
    private val callbacks = mutableListOf<(ProductionAndroidAppStartupTaskResult) -> Unit>()
    private var inFlight = false

    @Synchronized
    fun request(
        startup: () -> ProductionAndroidAppStartupOutcome,
        callback: (ProductionAndroidAppStartupTaskResult) -> Unit
    ) {
        callbacks += callback
        if (inFlight) return
        inFlight = true

        try {
            executor.execute {
                val result = try {
                    ProductionAndroidAppStartupTaskResult.Completed(startup())
                } catch (_: Exception) {
                    ProductionAndroidAppStartupTaskResult.Failed
                }
                complete(result)
            }
        } catch (_: Exception) {
            complete(ProductionAndroidAppStartupTaskResult.Failed)
        }
    }

    private fun complete(result: ProductionAndroidAppStartupTaskResult) {
        val listeners = synchronized(this) {
            inFlight = false
            callbacks.toList().also { callbacks.clear() }
        }
        listeners.forEach { listener ->
            try {
                listener(result)
            } catch (_: Exception) {
                // One Activity callback must not prevent delivery to a recreated Activity.
            }
        }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidAppStartupExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidAppStartupExecutor { block -> executor.execute(block) }
        }
    }
}
