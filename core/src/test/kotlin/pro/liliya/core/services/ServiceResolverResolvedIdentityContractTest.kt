package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ServiceResolverResolvedIdentityContractTest {
    @Test
    fun resolved_order_contains_exact_input_service_instances() {
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

        val result = ServiceDependencyResolver().resolve(listOf(memory, storage))

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertSame(storage, result.services[0])
        assertSame(memory, result.services[1])
    }
}
