package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceRegistryConcurrentSnapshotContractTest {
    @Test
    fun snapshots_can_be_taken_while_services_are_registered() {
        val registry = ServiceRegistry()
        val pool = Executors.newFixedThreadPool(4)
        val writer = pool.submit {
            repeat(100) { index ->
                registry.register(object : CoreService {
                    override val descriptor = ServiceDescriptor("service-$index")
                    override fun start(context: LogContext) = Unit
                    override fun stop(context: LogContext) = Unit
                })
            }
        }
        val readers = (1..3).map {
            pool.submit {
                repeat(100) {
                    val snapshot = registry.snapshot()
                    snapshot.keys.forEach { id -> require(id.isNotBlank()) }
                }
            }
        }

        writer.get()
        readers.forEach { it.get() }
        pool.shutdown()
        assertTrue(registry.snapshot().isNotEmpty())
    }
}
