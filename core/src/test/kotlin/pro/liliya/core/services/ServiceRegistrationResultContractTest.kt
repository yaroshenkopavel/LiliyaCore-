package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceRegistrationResultContractTest {
    @Test
    fun duplicate_registration_result_identifies_conflicting_service() {
        fun service() = object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()

        assertIs<ServiceRegistrationResult.Registered>(registry.register(service()))
        val duplicate = registry.register(service())

        assertIs<ServiceRegistrationResult.Rejected>(duplicate)
        assertEquals("service already registered: memory", duplicate.reason)
    }
}
