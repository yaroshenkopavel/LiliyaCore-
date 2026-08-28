package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceRegistryCaseSensitivityContractTest {
    @Test
    fun service_ids_are_exact_case_sensitive_identifiers() {
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()

        assertIs<ServiceRegistrationResult.Registered>(registry.register(service("Memory")))
        assertIs<ServiceRegistrationResult.Registered>(registry.register(service("memory")))
        assertTrue(registry.contains("Memory"))
        assertTrue(registry.contains("memory"))
    }
}
