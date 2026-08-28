package pro.liliya.core.modules

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.services.ServiceManager
import pro.liliya.core.services.ServiceRegistration
import pro.liliya.core.services.ServiceRegistrationResult
import pro.liliya.core.services.ServiceRegistry

sealed interface ModuleInstallResult {
    data class Installed(
        val moduleId: String,
        val serviceIds: List<String>
    ) : ModuleInstallResult

    data class Rejected(
        val moduleId: String,
        val reason: String
    ) : ModuleInstallResult
}

sealed interface ModuleUninstallResult {
    data class Uninstalled(
        val moduleId: String,
        val serviceIds: List<String>
    ) : ModuleUninstallResult

    data class Rejected(
        val moduleId: String,
        val reason: String
    ) : ModuleUninstallResult

    data class NotInstalled(val moduleId: String) : ModuleUninstallResult
}

class ModuleServiceInstaller(
    private val moduleRegistry: ModuleRegistry,
    private val moduleResolver: ModuleDependencyResolver,
    private val serviceRegistry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val observability: CoreObservability
) {
    private data class InstalledModule(
        val moduleRegistration: ModuleRegistration,
        val serviceRegistrations: List<ServiceRegistration>
    )

    private val lock = Any()
    private val installed = LinkedHashMap<String, InstalledModule>()

    fun install(module: CoreModule, context: LogContext): ModuleInstallResult = synchronized(lock) {
        val moduleId = module.descriptor.id
        if (moduleId in installed) {
            return@synchronized reject(moduleId, "module already installed", context)
        }

        when (val resolution = moduleResolver.resolve(moduleRegistry.snapshot().values + module)) {
            is ModuleResolutionResult.Resolved -> Unit
            is ModuleResolutionResult.DuplicateModule -> {
                return@synchronized reject(moduleId, "duplicate module ${resolution.moduleId}", context)
            }
            is ModuleResolutionResult.MissingDependency -> {
                return@synchronized reject(
                    moduleId,
                    "missing module dependency ${resolution.dependencyId} for ${resolution.moduleId}",
                    context
                )
            }
            is ModuleResolutionResult.CycleDetected -> {
                return@synchronized reject(
                    moduleId,
                    "module dependency cycle: ${resolution.moduleIds.sorted().joinToString(",")}",
                    context
                )
            }
        }

        val moduleRegistration = when (val result = moduleRegistry.register(module)) {
            is ModuleRegistrationResult.Registered -> result.registration
            is ModuleRegistrationResult.Rejected -> {
                return@synchronized reject(moduleId, result.reason, context)
            }
        }

        val serviceRegistrations = mutableListOf<ServiceRegistration>()
        for (service in module.services) {
            when (val result = serviceRegistry.register(service)) {
                is ServiceRegistrationResult.Registered -> {
                    serviceRegistrations += result.registration
                }

                is ServiceRegistrationResult.Rejected -> {
                    serviceRegistrations.asReversed().forEach { it.unregister() }
                    moduleRegistration.unregister()
                    observability.record(
                        severity = DiagnosticSeverity.WARNING,
                        code = "MODULE_INSTALL_ROLLED_BACK",
                        message = "module installation rolled back",
                        context = context,
                        metadata = mapOf(
                            "moduleId" to moduleId,
                            "serviceId" to service.descriptor.id,
                            "reason" to result.reason
                        )
                    )
                    return@synchronized ModuleInstallResult.Rejected(moduleId, result.reason)
                }
            }
        }

        installed[moduleId] = InstalledModule(
            moduleRegistration = moduleRegistration,
            serviceRegistrations = serviceRegistrations.toList()
        )
        val serviceIds = serviceRegistrations.map { it.serviceId }
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "MODULE_INSTALLED",
            message = "module installed with owned services",
            context = context,
            metadata = mapOf(
                "moduleId" to moduleId,
                "serviceIds" to serviceIds.joinToString(",")
            )
        )
        ModuleInstallResult.Installed(moduleId, serviceIds)
    }

    fun uninstall(moduleId: String, context: LogContext): ModuleUninstallResult = synchronized(lock) {
        val ownership = installed[moduleId]
            ?: return@synchronized ModuleUninstallResult.NotInstalled(moduleId)

        val startedService = ownership.serviceRegistrations
            .map { it.serviceId }
            .firstOrNull(serviceManager::isStarted)
        if (startedService != null) {
            return@synchronized rejectUninstall(
                moduleId,
                "module service is still started: $startedService",
                context
            )
        }

        val dependentModule = moduleRegistry.snapshot().values
            .asSequence()
            .filter { it.descriptor.id != moduleId }
            .filter { moduleId in it.descriptor.dependencies }
            .map { it.descriptor.id }
            .sorted()
            .firstOrNull()
        if (dependentModule != null) {
            return@synchronized rejectUninstall(
                moduleId,
                "module is required by $dependentModule",
                context
            )
        }

        installed.remove(moduleId)

        val removedServices = mutableListOf<String>()
        ownership.serviceRegistrations.asReversed().forEach { registration ->
            if (registration.unregister()) {
                removedServices += registration.serviceId
            }
        }
        ownership.moduleRegistration.unregister()

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "MODULE_UNINSTALLED",
            message = "module and owned services unregistered",
            context = context,
            metadata = mapOf(
                "moduleId" to moduleId,
                "serviceIds" to removedServices.joinToString(",")
            )
        )
        ModuleUninstallResult.Uninstalled(moduleId, removedServices)
    }

    fun isInstalled(moduleId: String): Boolean = synchronized(lock) {
        moduleId in installed
    }

    private fun reject(
        moduleId: String,
        reason: String,
        context: LogContext
    ): ModuleInstallResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "MODULE_INSTALL_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("moduleId" to moduleId)
        )
        return ModuleInstallResult.Rejected(moduleId, reason)
    }

    private fun rejectUninstall(
        moduleId: String,
        reason: String,
        context: LogContext
    ): ModuleUninstallResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "MODULE_UNINSTALL_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("moduleId" to moduleId)
        )
        return ModuleUninstallResult.Rejected(moduleId, reason)
    }
}
