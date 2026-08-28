package pro.liliya.core.services

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider

sealed interface ServiceLifecycleResult {
    data class Applied(val serviceIds: List<String>) : ServiceLifecycleResult
    data class Rejected(val reason: String) : ServiceLifecycleResult
    data class Failed(val serviceId: String, val cause: Throwable) : ServiceLifecycleResult
}

class ServiceManager(
    private val registry: ServiceRegistry,
    private val resolver: ServiceDependencyResolver,
    diagnostics: DiagnosticRecorder,
    private val observability: CoreObservability = CoreObservability(
        loggerProvider = LoggerProvider { context -> LoggerFactory.create(context) },
        diagnostics = diagnostics
    )
) {
    private val lock = Any()
    private val started = LinkedHashSet<String>()

    fun startAll(context: LogContext): ServiceLifecycleResult = synchronized(lock) {
        when (val resolution = resolver.resolve(registry.snapshot().values)) {
            is ServiceResolutionResult.DuplicateService -> reject("duplicate service ${resolution.serviceId}", context)
            is ServiceResolutionResult.MissingDependency -> reject(
                "missing dependency ${resolution.dependencyId} for ${resolution.serviceId}",
                context
            )
            is ServiceResolutionResult.CycleDetected -> reject(
                "dependency cycle: ${resolution.serviceIds.sorted().joinToString(",")}",
                context
            )
            is ServiceResolutionResult.Resolved -> {
                val newlyStarted = mutableListOf<String>()
                for (service in resolution.services) {
                    val id = service.descriptor.id
                    if (id in started) continue
                    try {
                        service.start(context)
                        started += id
                        newlyStarted += id
                        observability.record(
                            severity = DiagnosticSeverity.INFO,
                            code = "SERVICE_STARTED",
                            message = "service started",
                            context = context,
                            metadata = mapOf("serviceId" to id)
                        )
                    } catch (error: Throwable) {
                        observability.record(
                            severity = DiagnosticSeverity.ERROR,
                            code = "SERVICE_START_FAILED",
                            message = "service start failed",
                            context = context,
                            metadata = mapOf("serviceId" to id),
                            throwable = error
                        )
                        rollback(newlyStarted, context)
                        return@synchronized ServiceLifecycleResult.Failed(id, error)
                    }
                }
                ServiceLifecycleResult.Applied(newlyStarted)
            }
        }
    }

    fun stopAll(context: LogContext): ServiceLifecycleResult = synchronized(lock) {
        val resolution = resolver.resolve(registry.snapshot().values)
        if (resolution !is ServiceResolutionResult.Resolved) {
            return@synchronized reject("service graph is not resolvable", context)
        }

        val stopped = mutableListOf<String>()
        for (service in resolution.services.asReversed()) {
            val id = service.descriptor.id
            if (id !in started) continue
            try {
                service.stop(context)
                started.remove(id)
                stopped += id
                observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "SERVICE_STOPPED",
                    message = "service stopped",
                    context = context,
                    metadata = mapOf("serviceId" to id)
                )
            } catch (error: Throwable) {
                observability.record(
                    severity = DiagnosticSeverity.ERROR,
                    code = "SERVICE_STOP_FAILED",
                    message = "service stop failed",
                    context = context,
                    metadata = mapOf("serviceId" to id),
                    throwable = error
                )
                return@synchronized ServiceLifecycleResult.Failed(id, error)
            }
        }
        ServiceLifecycleResult.Applied(stopped)
    }

    fun isStarted(serviceId: String): Boolean = synchronized(lock) { serviceId in started }

    private fun rollback(serviceIds: List<String>, context: LogContext) {
        for (id in serviceIds.asReversed()) {
            val service = registry.find(id) ?: continue
            try {
                service.stop(context)
                started.remove(id)
                observability.record(
                    severity = DiagnosticSeverity.WARNING,
                    code = "SERVICE_START_ROLLED_BACK",
                    message = "service start rolled back",
                    context = context,
                    metadata = mapOf("serviceId" to id)
                )
            } catch (error: Throwable) {
                observability.record(
                    severity = DiagnosticSeverity.ERROR,
                    code = "SERVICE_ROLLBACK_FAILED",
                    message = "service rollback failed",
                    context = context,
                    metadata = mapOf("serviceId" to id),
                    throwable = error
                )
            }
        }
    }

    private fun reject(reason: String, context: LogContext): ServiceLifecycleResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "SERVICE_LIFECYCLE_REJECTED",
            message = reason,
            context = context
        )
        return ServiceLifecycleResult.Rejected(reason)
    }
}
