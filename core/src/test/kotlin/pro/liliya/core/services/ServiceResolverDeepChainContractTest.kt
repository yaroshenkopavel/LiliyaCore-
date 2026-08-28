package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverDeepChainContractTest {
    @Test
    fun deep_dependency_chain_resolves_from_root_to_leaf() {
        fun service(id: String, dependency: String? = null) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependency?.let(::setOf) ?: emptySet())
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("d", "c"),
                service("b", "a"),
                service("c", "b"),
                service("a")
            )
        )

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertEquals(listOf("a", "b", "c", "d"), result.services.map { it.descriptor.id })
    }
}
