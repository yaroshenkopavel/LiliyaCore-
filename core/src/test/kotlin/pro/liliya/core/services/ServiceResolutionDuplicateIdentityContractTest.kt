package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolutionDuplicateIdentityContractTest {
    @Test
    fun duplicate_resolution_result_identifies_duplicate_id() {
        fun service() = object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service(), service()))

        assertIs<ServiceResolutionResult.DuplicateService>(result)
        assertEquals("memory", result.serviceId)
    }
}
