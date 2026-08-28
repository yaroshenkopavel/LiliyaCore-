package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverDeterminismContractTest {
    @Test
    fun independent_services_have_stable_id_order() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(service("zeta"), service("alpha"), service("beta"))
        )

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertEquals(listOf("alpha", "beta", "zeta"), result.services.map { it.descriptor.id })
    }
}
