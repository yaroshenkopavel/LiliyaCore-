package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverCycleWithDependentContractTest {
    @Test
    fun cycle_result_includes_services_that_cannot_resolve_because_they_depend_on_cycle() {
        fun service(id: String, dependencies: Set<String>) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("a", setOf("b")),
                service("b", setOf("a")),
                service("dependent", setOf("a"))
            )
        )

        assertIs<ServiceResolutionResult.CycleDetected>(result)
        assertEquals(setOf("a", "b", "dependent"), result.serviceIds)
    }
}
