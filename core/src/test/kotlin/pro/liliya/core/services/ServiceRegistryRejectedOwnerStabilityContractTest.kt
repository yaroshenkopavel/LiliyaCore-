package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ServiceRegistryRejectedOwnerStabilityContractTest {
    @Test
    fun repeated_rejected_registrations_never_replace_original_owner() {
        fun service() = object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()
        val owner = service()
        assertIs<ServiceRegistrationResult.Registered>(registry.register(owner))

        repeat(10) {
            assertIs<ServiceRegistrationResult.Rejected>(registry.register(service()))
            assertSame(owner, registry.find("memory"))
        }
    }
}
