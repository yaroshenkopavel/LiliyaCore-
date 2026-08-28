package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverTransitiveMissingContractTest {
    @Test
    fun missing_dependency_is_detected_anywhere_in_multi_level_graph() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("assistant", setOf("memory")),
                service("memory", setOf("storage"))
            )
        )

        assertIs<ServiceResolutionResult.MissingDependency>(result)
        assertEquals("memory", result.serviceId)
        assertEquals("storage", result.dependencyId)
    }
}
