package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverLargeIndependentContractTest {
    @Test
    fun independent_services_resolve_in_stable_lexical_order() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val ids = listOf("zeta", "gamma", "alpha", "delta", "beta")

        val result = ServiceDependencyResolver().resolve(ids.map(::service))

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertEquals(ids.sorted(), result.services.map { it.descriptor.id })
    }
}
