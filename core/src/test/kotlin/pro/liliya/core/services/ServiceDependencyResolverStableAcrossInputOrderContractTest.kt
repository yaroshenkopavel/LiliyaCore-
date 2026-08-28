package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceDependencyResolverStableAcrossInputOrderContractTest {
    @Test
    fun equivalent_graphs_resolve_to_same_order_regardless_of_input_order() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val storage = service("storage")
        val model = service("model")
        val memory = service("memory", setOf("storage"))
        val resolver = ServiceDependencyResolver()

        val first = resolver.resolve(listOf(memory, model, storage))
        val second = resolver.resolve(listOf(storage, memory, model))

        assertIs<ServiceResolutionResult.Resolved>(first)
        assertIs<ServiceResolutionResult.Resolved>(second)
        assertEquals(
            first.services.map { it.descriptor.id },
            second.services.map { it.descriptor.id }
        )
    }
}
