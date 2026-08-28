package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertSame

class ServiceRegistryIdentityContractTest {
    @Test
    fun registry_returns_exact_registered_service_instance() {
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("model")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val registry = ServiceRegistry()
        registry.register(service)

        assertSame(service, registry.find("model"))
        assertSame(service, registry.snapshot().getValue("model"))
    }
}
