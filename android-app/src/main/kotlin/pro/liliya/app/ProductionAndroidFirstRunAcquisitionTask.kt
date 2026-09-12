package pro.liliya.app

import java.util.concurrent.Executors

internal sealed interface ProductionAndroidFirstRunAcquisitionTaskSnapshot {
    data object Idle : ProductionAndroidFirstRunAcquisitionTaskSnapshot

    data class InFlight(
        val requestId: Long
    ) : ProductionAndroidFirstRunAcquisitionTaskSnapshot

    data class Completed(
        val requestId: Long,
        val result: ProductionAndroidFirstRunAcquisitionResult
    ) : ProductionAndroidFirstRunAcquisitionTaskSnapshot
}

internal sealed interface ProductionAndroidFirstRunAcquisitionTaskRequestResult {
    data class Started(
        val requestId: Long
    ) : ProductionAndroidFirstRunAcquisitionTaskRequestResult

    data object Busy : ProductionAndroidFirstRunAcquisitionTaskRequestResult
}

internal fun interface ProductionAndroidFirstRunAcquisitionExecutor {
    fun execute(block: () -> Unit)
}

/**
 * Application-scope owner for one configured first-run acquisition/install attempt.
 *
 * Task Ownership != Product Configuration.
 * Task Ownership != License Issuance or Verification.
 * Task Ownership != Trust or Authority Policy.
 * Task Ownership != Model Selection.
 *
 * The task owns only execution lifetime and terminal delivery. It delegates every attempt to the
 * already accepted configured-acquisition boundary, retains the terminal result until exact
 * request-id consumption, and never replays an attempt after Activity recreation.
 */
internal class ProductionAndroidFirstRunAcquisitionTask(
    private val executor: ProductionAndroidFirstRunAcquisitionExecutor = productionExecutor()
) {
    private var nextRequestId = 1L
    private var snapshot: ProductionAndroidFirstRunAcquisitionTaskSnapshot =
        ProductionAndroidFirstRunAcquisitionTaskSnapshot.Idle
    private val listeners = mutableListOf<
        (ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed) -> Unit
    >()

    @Synchronized
    fun request(
        acquireAndInstall: () -> ProductionAndroidFirstRunAcquisitionResult,
        listener: (ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidFirstRunAcquisitionTaskRequestResult {
        if (snapshot !is ProductionAndroidFirstRunAcquisitionTaskSnapshot.Idle) {
            return ProductionAndroidFirstRunAcquisitionTaskRequestResult.Busy
        }

        val requestId = nextRequestId++
        snapshot = ProductionAndroidFirstRunAcquisitionTaskSnapshot.InFlight(requestId)
        listeners += listener

        try {
            executor.execute {
                val result = try {
                    acquireAndInstall()
                } catch (_: Exception) {
                    ProductionAndroidFirstRunAcquisitionResult.Failed
                }
                complete(requestId, result)
            }
        } catch (_: Exception) {
            complete(requestId, ProductionAndroidFirstRunAcquisitionResult.Failed)
        }

        return ProductionAndroidFirstRunAcquisitionTaskRequestResult.Started(requestId)
    }

    @Synchronized
    fun observe(
        listener: (ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidFirstRunAcquisitionTaskSnapshot {
        val current = snapshot
        if (current is ProductionAndroidFirstRunAcquisitionTaskSnapshot.InFlight) {
            listeners += listener
        }
        return current
    }

    @Synchronized
    fun consume(requestId: Long): Boolean {
        val current = snapshot as? ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed
            ?: return false
        if (current.requestId != requestId) return false
        snapshot = ProductionAndroidFirstRunAcquisitionTaskSnapshot.Idle
        return true
    }

    private fun complete(
        requestId: Long,
        result: ProductionAndroidFirstRunAcquisitionResult
    ) {
        val completed = ProductionAndroidFirstRunAcquisitionTaskSnapshot.Completed(
            requestId = requestId,
            result = result
        )
        val callbacks = synchronized(this) {
            val current = snapshot as? ProductionAndroidFirstRunAcquisitionTaskSnapshot.InFlight
            if (current == null || current.requestId != requestId) return
            snapshot = completed
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { listener ->
            runCatching { listener(completed) }
        }
    }

    private companion object {
        fun productionExecutor(): ProductionAndroidFirstRunAcquisitionExecutor {
            val executor = Executors.newSingleThreadExecutor()
            return ProductionAndroidFirstRunAcquisitionExecutor { block -> executor.execute(block) }
        }
    }
}
