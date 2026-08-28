package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverDuplicateDeterminismContractTest {
    @Test
    fun multiple_duplicate_ids_report_lexically_first_identity() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(service("zeta"), service("alpha"), service("zeta"), service("alpha"))
        )

        assertIs<ServiceResolutionResult.DuplicateService>(result)
        assertEquals("alpha", result.serviceId)
    }
}
