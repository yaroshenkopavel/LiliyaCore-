package pro.liliya.core.modules

import pro.liliya.core.logging.LogContext
import pro.liliya.core.services.CoreService
import pro.liliya.core.services.ServiceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModuleFoundationContractTest {
    private fun service(id: String) = object : CoreService {
        override val descriptor = ServiceDescriptor(id)
        override fun start(context: LogContext) = Unit
        override fun stop(context: LogContext) = Unit
    }

    private fun module(
        id: String,
        dependencies: Set<String> = emptySet(),
        services: Collection<CoreService> = emptyList()
    ) = object : CoreModule {
        override val descriptor = ModuleDescriptor(id, dependencies)
        override val services = services
    }

    @Test
    fun descriptor_enforces_identity_and_dependency_invariants() {
        assertFailsWith<IllegalArgumentException> { ModuleDescriptor(" ") }
        assertFailsWith<IllegalArgumentException> { ModuleDescriptor("memory", setOf(" ")) }
        assertFailsWith<IllegalArgumentException> { ModuleDescriptor("memory", setOf("memory")) }

        val source = mutableSetOf("storage")
        val descriptor = ModuleDescriptor("memory", source)
        source += "model"
        assertEquals(setOf("storage"), descriptor.dependencies)
    }

    @Test
    fun registry_has_single_owner_for_module_identity() {
        val registry = ModuleRegistry()
        val first = module("memory")
        val second = module("memory")

        assertIs<ModuleRegistrationResult.Registered>(registry.register(first))
        val duplicate = registry.register(second)

        assertIs<ModuleRegistrationResult.Rejected>(duplicate)
        assertSame(first, registry.find("memory"))
        assertEquals("module already registered: memory", duplicate.reason)
    }

    @Test
    fun resolver_orders_dependencies_before_dependents_deterministically() {
        val resolver = ModuleDependencyResolver()
        val result = resolver.resolve(
            listOf(
                module("assistant", setOf("memory", "model")),
                module("memory", setOf("storage")),
                module("storage"),
                module("model")
            )
        )

        assertIs<ModuleResolutionResult.Resolved>(result)
        val ids = result.modules.map { it.descriptor.id }
        assertTrue(ids.indexOf("storage") < ids.indexOf("memory"))
        assertTrue(ids.indexOf("memory") < ids.indexOf("assistant"))
        assertTrue(ids.indexOf("model") < ids.indexOf("assistant"))

        val independent = resolver.resolve(
            listOf(module("zeta"), module("alpha"), module("beta"))
        )
        assertIs<ModuleResolutionResult.Resolved>(independent)
        assertEquals(listOf("alpha", "beta", "zeta"), independent.modules.map { it.descriptor.id })
    }

    @Test
    fun resolver_rejects_missing_dependencies_and_cycles_without_execution_semantics() {
        val resolver = ModuleDependencyResolver()

        val missing = resolver.resolve(listOf(module("memory", setOf("storage"))))
        assertIs<ModuleResolutionResult.MissingDependency>(missing)
        assertEquals("memory", missing.moduleId)
        assertEquals("storage", missing.dependencyId)

        val cycle = resolver.resolve(
            listOf(
                module("a", setOf("b")),
                module("b", setOf("a"))
            )
        )
        assertIs<ModuleResolutionResult.CycleDetected>(cycle)
        assertEquals(setOf("a", "b"), cycle.moduleIds)
    }

    @Test
    fun module_declares_services_without_owning_service_lifecycle() {
        val storage = service("storage")
        val memory = service("memory")
        val module = module("memory-module", services = listOf(storage, memory))

        assertEquals(listOf("storage", "memory"), module.services.map { it.descriptor.id })
    }
}
