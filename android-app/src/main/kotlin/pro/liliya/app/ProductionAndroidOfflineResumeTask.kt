package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidOfflineResumeTaskSnapshot {
    data object Idle : ProductionAndroidOfflineResumeTaskSnapshot
    data class InFlight(val requestId: Long) : ProductionAndroidOfflineResumeTaskSnapshot
    data class Completed(
        val requestId: Long,
        val result: ProductionAndroidOfflineResumeBootstrapResult
    ) : ProductionAndroidOfflineResumeTaskSnapshot
}

internal sealed interface ProductionAndroidOfflineResumeTaskRequestResult {
    data class Started(val requestId: Long) : ProductionAndroidOfflineResumeTaskRequestResult
    data object Busy : ProductionAndroidOfflineResumeTaskRequestResult
}

internal fun interface ProductionAndroidOfflineResumeExecutor {
    fun execute(block: () -> Unit)
}

/** Process-scoped, single-flight cold resume. Never hash a GGUF on the launcher UI thread. */
internal class ProductionAndroidOfflineResumeTask(
    private val executor: ProductionAndroidOfflineResumeExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var snapshot: ProductionAndroidOfflineResumeTaskSnapshot =
        ProductionAndroidOfflineResumeTaskSnapshot.Idle
    private val listeners = mutableListOf<
        (ProductionAndroidOfflineResumeTaskSnapshot.Completed) -> Unit
    >()

    @Synchronized
    fun request(
        resume: () -> ProductionAndroidOfflineResumeBootstrapResult,
        listener: (ProductionAndroidOfflineResumeTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidOfflineResumeTaskRequestResult {
        if (snapshot !is ProductionAndroidOfflineResumeTaskSnapshot.Idle) {
            return ProductionAndroidOfflineResumeTaskRequestResult.Busy
        }
        val id = nextRequestId++
        snapshot = ProductionAndroidOfflineResumeTaskSnapshot.InFlight(id)
        listeners += listener
        try {
            executor.execute {
                val result = try {
                    resume()
                } catch (_: Throwable) {
                    ProductionAndroidOfflineResumeBootstrapResult.Rejected(
                        ProductionAndroidOfflineResumeBootstrapFailure.FAILED
                    )
                }
                complete(id, result)
            }
        } catch (_: Throwable) {
            complete(
                id,
                ProductionAndroidOfflineResumeBootstrapResult.Rejected(
                    ProductionAndroidOfflineResumeBootstrapFailure.FAILED
                )
            )
        }
        return ProductionAndroidOfflineResumeTaskRequestResult.Started(id)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidOfflineResumeTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidOfflineResumeTaskSnapshot {
        val current = snapshot
        if (current is ProductionAndroidOfflineResumeTaskSnapshot.InFlight) {
            listeners += listener
        }
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = snapshot as? ProductionAndroidOfflineResumeTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false
        snapshot = ProductionAndroidOfflineResumeTaskSnapshot.Idle
        return true
    }

    private fun complete(id: Long, result: ProductionAndroidOfflineResumeBootstrapResult) {
        val completed = ProductionAndroidOfflineResumeTaskSnapshot.Completed(id, result)
        val callbacks = synchronized(this) {
            val current = snapshot as? ProductionAndroidOfflineResumeTaskSnapshot.InFlight
            if (current == null || current.requestId != id) return
            snapshot = completed
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(completed) } }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidOfflineResumeExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidOfflineResumeExecutor { block -> executor.execute(block) }
        }
    }
}
