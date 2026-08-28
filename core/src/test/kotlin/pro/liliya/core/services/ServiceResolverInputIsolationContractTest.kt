package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverInputIsolationContractTest {
    @Test
    fun resolution_does_not_mutate_service_dependency_descriptors() {
        val storage = object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val memory = object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val resolver = ServiceDependencyResolver()

        assertIs<ServiceResolutionResult.Resolved>(resolver.resolve(listOf(memory, storage)))
        assertIs<ServiceResolutionResult.Resolved>(resolver.resolve(listOf(memory, storage)))

        assertEquals(setOf("storage"), memory.descriptor.dependencies)
    }
}
