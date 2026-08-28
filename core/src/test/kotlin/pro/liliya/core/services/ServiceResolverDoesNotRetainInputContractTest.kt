package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceResolverDoesNotRetainInputContractTest {
    @Test
    fun later_resolution_uses_only_current_input_graph() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val resolver = ServiceDependencyResolver()
        assertIs<ServiceResolutionResult.Resolved>(resolver.resolve(listOf(service("storage"))))

        val second = resolver.resolve(emptyList())

        assertIs<ServiceResolutionResult.Resolved>(second)
        assertTrue(second.services.isEmpty())
    }
}
