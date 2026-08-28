package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverResolvedListIsolationContractTest {
    @Test
    fun mutating_input_collection_after_resolution_does_not_change_resolved_order() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val input = mutableListOf(service("storage"), service("memory"))
        val result = ServiceDependencyResolver().resolve(input)
        assertIs<ServiceResolutionResult.Resolved>(result)

        input.clear()

        assertEquals(listOf("memory", "storage"), result.services.map { it.descriptor.id })
    }
}
