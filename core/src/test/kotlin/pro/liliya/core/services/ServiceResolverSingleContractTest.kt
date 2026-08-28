package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverSingleContractTest {
    @Test
    fun single_service_resolves_as_the_only_entry() {
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("storage")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service))

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertEquals(listOf("storage"), result.services.map { it.descriptor.id })
    }
}
