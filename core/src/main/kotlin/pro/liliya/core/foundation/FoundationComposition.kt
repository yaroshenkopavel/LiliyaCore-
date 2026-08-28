package pro.liliya.core.foundation

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.events.EventBus
import pro.liliya.core.lifecycle.LifecycleController
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContext
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.UuidCorrelationIdGenerator
import pro.liliya.core.modules.ModuleDependencyResolver
import pro.liliya.core.modules.ModuleRegistry
import pro.liliya.core.modules.ModuleServiceInstaller
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.recovery.RecoveryCoordinator
import pro.liliya.core.recovery.RecoveryPolicy
import pro.liliya.core.runtime.RuntimeStateController
import pro.liliya.core.runtime.RuntimeStateHolder
import pro.liliya.core.runtime.RuntimeTransitionPolicy
import pro.liliya.core.services.ServiceDependencyResolver
import pro.liliya.core.services.ServiceManager
import pro.liliya.core.services.ServiceRegistry

class FoundationComposition(
    val diagnostics: DiagnosticRecorder,
    loggerProvider: LoggerProvider,
    private val correlationIds: CorrelationIdGenerator = UuidCorrelationIdGenerator
) {
    val observability = CoreObservability(
        loggerProvider = loggerProvider,
        diagnostics = diagnostics
    )

    val runtimeStateHolder = RuntimeStateHolder()
    val runtimeTransitionPolicy = RuntimeTransitionPolicy()
    val runtime = RuntimeStateController(
        stateHolder = runtimeStateHolder,
        transitionPolicy = runtimeTransitionPolicy,
        diagnostics = diagnostics,
        observability = observability
    )

    val lifecycle = LifecycleController(
        runtime = runtime,
        diagnostics = diagnostics,
        observability = observability
    )

    val recovery = RecoveryCoordinator(
        policy = RecoveryPolicy(),
        diagnostics = diagnostics,
        observability = observability
    )

    val events = EventBus(
        diagnostics = diagnostics,
        observability = observability
    )

    val services = ServiceRegistry()
    val serviceDependencyResolver = ServiceDependencyResolver()
    val serviceManager = ServiceManager(
        registry = services,
        resolver = serviceDependencyResolver,
        diagnostics = diagnostics,
        observability = observability
    )

    val modules = ModuleRegistry()
    val moduleDependencyResolver = ModuleDependencyResolver()
    val moduleServiceInstaller = ModuleServiceInstaller(
        moduleRegistry = modules,
        moduleResolver = moduleDependencyResolver,
        serviceRegistry = services,
        serviceManager = serviceManager,
        observability = observability
    )

    fun rootContext(
        operation: String,
        component: String = "Foundation",
        metadata: Map<String, String> = emptyMap()
    ): LogContext = LogContextPropagation.root(
        module = "CORE",
        component = component,
        operation = operation,
        metadata = metadata,
        generator = correlationIds
    )

    fun childContext(
        parent: LogContext,
        component: String,
        operation: String,
        metadata: Map<String, String> = emptyMap()
    ): LogContext = LogContextPropagation.child(
        parent = parent,
        component = component,
        operation = operation,
        metadata = metadata,
        generator = correlationIds
    )
}
