package pro.liliya.core.modules

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.services.CoreService
import pro.liliya.core.services.ServiceDependencyResolver
import pro.liliya.core.services.ServiceDescriptor
import pro.liliya.core.services.ServiceLifecycleResult
import pro.liliya.core.services.ServiceManager
import pro.liliya.core.services.ServiceRegistrationResult
import pro.liliya.core.services.ServiceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModuleServiceInstallerContractTest {
    private class TestService(
        id: String,
        private val calls: MutableList<String>? = null
    ) : CoreService {
        override val descriptor = ServiceDescriptor(id)
        override fun start(context: LogContext) {
            calls?.add("start:${descriptor.id}")
        }
        override fun stop(context: LogContext) {
            calls?.add("stop:${descriptor.id}")
        }
    }

    private class TestModule(
        id: String,
        override val services: Collection<CoreService>,
        dependencies: Set<String> = emptySet()
    ) : CoreModule {
        override val descriptor = ModuleDescriptor(id, dependencies)
    }

    private data class Fixture(
        val modules: ModuleRegistry,
        val services: ServiceRegistry,
        val manager: ServiceManager,
        val diagnostics: InMemoryDiagnosticSink,
        val logs: InMemoryLogWriter,
        val installer: ModuleServiceInstaller,
        val context: LogContext
    )

    private fun fixture(): Fixture {
        val modules = ModuleRegistry()
        val services = ServiceRegistry()
        val diagnostics = InMemoryDiagnosticSink()
        val logs = InMemoryLogWriter()
        val recorder = DiagnosticRecorder(diagnostics)
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = recorder
        )
        val manager = ServiceManager(
            registry = services,
            resolver = ServiceDependencyResolver(),
            diagnostics = recorder,
            observability = observability
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "ModuleServiceInstaller",
            operation = "contract",
            generator = CorrelationIdGenerator { "module-service-contract" }
        )
        return Fixture(
            modules = modules,
            services = services,
            manager = manager,
            diagnostics = diagnostics,
            logs = logs,
            installer = ModuleServiceInstaller(
                moduleRegistry = modules,
                moduleResolver = ModuleDependencyResolver(),
                serviceRegistry = services,
                serviceManager = manager,
                observability = observability
            ),
            context = context
        )
    }

    @Test
    fun install_owns_module_and_all_services_then_uninstall_releases_exact_ownership() {
        val f = fixture()
        val module = TestModule("memory-module", listOf(TestService("storage"), TestService("memory")))

        val installed = assertIs<ModuleInstallResult.Installed>(f.installer.install(module, f.context))
        assertEquals(listOf("storage", "memory"), installed.serviceIds)
        assertTrue(f.modules.contains("memory-module"))
        assertTrue(f.services.contains("storage"))
        assertTrue(f.services.contains("memory"))
        assertTrue(f.installer.isInstalled("memory-module"))

        val removed = assertIs<ModuleUninstallResult.Uninstalled>(f.installer.uninstall("memory-module", f.context))
        assertEquals(listOf("memory", "storage"), removed.serviceIds)
        assertFalse(f.modules.contains("memory-module"))
        assertFalse(f.services.contains("storage"))
        assertFalse(f.services.contains("memory"))
        assertFalse(f.installer.isInstalled("memory-module"))
    }

    @Test
    fun service_conflict_rolls_back_only_registrations_owned_by_install_attempt() {
        val f = fixture()
        val external = TestService("shared")
        f.services.register(external)
        val module = TestModule("conflicting-module", listOf(TestService("owned"), TestService("shared")))

        assertIs<ModuleInstallResult.Rejected>(f.installer.install(module, f.context))

        assertFalse(f.modules.contains("conflicting-module"))
        assertFalse(f.services.contains("owned"))
        assertTrue(f.services.find("shared") === external)
        assertFalse(f.installer.isInstalled("conflicting-module"))
        assertEquals(
            listOf("MODULE_INSTALL_ROLLED_BACK"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            setOf("module-service-contract"),
            f.diagnostics.snapshot().map { it.context.correlationId }.toSet()
        )
    }

    @Test
    fun repeated_install_is_rejected_without_losing_existing_ownership() {
        val f = fixture()
        val module = TestModule("model-module", listOf(TestService("model")))

        assertIs<ModuleInstallResult.Installed>(f.installer.install(module, f.context))
        assertIs<ModuleInstallResult.Rejected>(f.installer.install(module, f.context))

        assertTrue(f.modules.contains("model-module"))
        assertTrue(f.services.contains("model"))
        assertTrue(f.installer.isInstalled("model-module"))
    }

    @Test
    fun missing_module_dependency_is_rejected_before_any_registration() {
        val f = fixture()
        val module = TestModule(
            id = "memory-module",
            services = listOf(TestService("memory")),
            dependencies = setOf("storage-module")
        )

        assertIs<ModuleInstallResult.Rejected>(f.installer.install(module, f.context))
        assertFalse(f.modules.contains("memory-module"))
        assertFalse(f.services.contains("memory"))
        assertEquals("MODULE_INSTALL_REJECTED", f.logs.snapshot().single().marker)
    }

    @Test
    fun uninstall_is_rejected_while_owned_service_is_started() {
        val f = fixture()
        val module = TestModule("model-module", listOf(TestService("model")))
        assertIs<ModuleInstallResult.Installed>(f.installer.install(module, f.context))
        assertIs<ServiceLifecycleResult.Applied>(f.manager.startAll(f.context))

        assertIs<ModuleUninstallResult.Rejected>(f.installer.uninstall("model-module", f.context))
        assertTrue(f.modules.contains("model-module"))
        assertTrue(f.services.contains("model"))
        assertTrue(f.installer.isInstalled("model-module"))

        assertIs<ServiceLifecycleResult.Applied>(f.manager.stopAll(f.context))
        assertIs<ModuleUninstallResult.Uninstalled>(f.installer.uninstall("model-module", f.context))
    }

    @Test
    fun service_manager_stops_exact_started_instance_after_registry_unregistration() {
        val f = fixture()
        val calls = mutableListOf<String>()
        val service = TestService("detached", calls)
        val registration = assertIs<ServiceRegistrationResult.Registered>(f.services.register(service)).registration

        assertIs<ServiceLifecycleResult.Applied>(f.manager.startAll(f.context))
        assertTrue(registration.unregister())
        assertFalse(f.services.contains("detached"))

        assertIs<ServiceLifecycleResult.Applied>(f.manager.stopAll(f.context))
        assertEquals(listOf("start:detached", "stop:detached"), calls)
        assertFalse(f.manager.isStarted("detached"))
    }
}
