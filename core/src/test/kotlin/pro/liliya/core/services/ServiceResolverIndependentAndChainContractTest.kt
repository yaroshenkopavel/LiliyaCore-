package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverIndependentAndChainContractTest {
    @Test
    fun mixed_independent_and_dependent_services_resolve_deterministically() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("zeta"),
                service("memory", setOf("storage")),
                service("storage"),
                service("alpha")
            )
        )

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertEquals(listOf("alpha", "storage", "zeta", "memory"), result.services.map { it.descriptor.id })
    }
}
