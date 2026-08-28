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
import pro.liliya.core.services.ServiceDescriptor
import pro.liliya.core.services.ServiceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModuleServiceInstallerContractTest {
    private class TestService(id: String) : CoreService {
        override val descriptor = ServiceDescriptor(id)
        override fun start(context: LogContext) = Unit
        override fun stop(context: LogContext) = Unit
    }

    private class TestModule(
        id: String,
        override val services: Collection<CoreService>
    ) : CoreModule {
        override val descriptor = ModuleDescriptor(id)
    }

    private data class Fixture(
        val modules: ModuleRegistry,
        val services: ServiceRegistry,
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
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "ModuleServiceInstaller",
            operation = "contract",
            generator = CorrelationIdGenerator { "module-service-contract" }
        )
        return Fixture(
            modules = modules,
            services = services,
            diagnostics = diagnostics,
            logs = logs,
            installer = ModuleServiceInstaller(modules, services, observability),
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
}
