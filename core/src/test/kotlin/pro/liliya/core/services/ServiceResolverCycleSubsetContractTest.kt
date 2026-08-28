package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverCycleSubsetContractTest {
    @Test
    fun cycle_result_contains_only_unresolved_cycle_after_ready_services_are_removed() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("storage"),
                service("a", setOf("b")),
                service("b", setOf("a"))
            )
        )

        assertIs<ServiceResolutionResult.CycleDetected>(result)
        assertEquals(setOf("a", "b"), result.serviceIds)
    }
}
