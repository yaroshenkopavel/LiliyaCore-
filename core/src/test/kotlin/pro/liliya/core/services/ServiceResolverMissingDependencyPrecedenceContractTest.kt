package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverMissingDependencyPrecedenceContractTest {
    @Test
    fun missing_dependency_is_reported_before_cycle_analysis() {
        fun service(id: String, dependencies: Set<String>) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("a", setOf("b")),
                service("b", setOf("a")),
                service("memory", setOf("missing"))
            )
        )

        assertIs<ServiceResolutionResult.MissingDependency>(result)
        assertEquals("memory", result.serviceId)
        assertEquals("missing", result.dependencyId)
    }
}
