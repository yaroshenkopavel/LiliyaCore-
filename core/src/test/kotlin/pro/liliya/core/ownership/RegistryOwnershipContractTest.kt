package pro.liliya.core.ownership

import pro.liliya.core.logging.LogContext
import pro.liliya.core.modules.CoreModule
import pro.liliya.core.modules.ModuleDescriptor
import pro.liliya.core.modules.ModuleRegistrationResult
import pro.liliya.core.modules.ModuleRegistry
import pro.liliya.core.services.CoreService
import pro.liliya.core.services.ServiceDescriptor
import pro.liliya.core.services.ServiceRegistrationResult
import pro.liliya.core.services.ServiceRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegistryOwnershipContractTest {
    private class TestService(
        override val descriptor: ServiceDescriptor
    ) : CoreService {
        override fun start(context: LogContext) = Unit
        override fun stop(context: LogContext) = Unit
    }

    private class TestModule(
        override val descriptor: ModuleDescriptor
    ) : CoreModule {
        override val services: Collection<CoreService> = emptyList()
    }

    @Test
    fun service_registration_handle_is_idempotent_and_cannot_remove_replacement_owner() {
        val registry = ServiceRegistry()
        val first = TestService(ServiceDescriptor("memory"))
        val replacement = TestService(ServiceDescriptor("memory"))

        val firstResult = assertIs<ServiceRegistrationResult.Registered>(registry.register(first))
        assertTrue(firstResult.registration.unregister())
        assertFalse(firstResult.registration.unregister())

        val replacementResult = assertIs<ServiceRegistrationResult.Registered>(registry.register(replacement))
        assertFalse(firstResult.registration.unregister())
        assertSame(replacement, registry.find("memory"))

        assertTrue(replacementResult.registration.unregister())
        assertFalse(registry.contains("memory"))
    }

    @Test
    fun module_registration_handle_is_idempotent_and_cannot_remove_replacement_owner() {
        val registry = ModuleRegistry()
        val first = TestModule(ModuleDescriptor("cognition"))
        val replacement = TestModule(ModuleDescriptor("cognition"))

        val firstResult = assertIs<ModuleRegistrationResult.Registered>(registry.register(first))
        assertTrue(firstResult.registration.unregister())
        assertFalse(firstResult.registration.unregister())

        val replacementResult = assertIs<ModuleRegistrationResult.Registered>(registry.register(replacement))
        assertFalse(firstResult.registration.unregister())
        assertSame(replacement, registry.find("cognition"))

        assertTrue(replacementResult.registration.unregister())
        assertFalse(registry.contains("cognition"))
    }
}
