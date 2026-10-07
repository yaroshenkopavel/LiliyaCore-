package pro.liliya.android.runtime

import java.time.Instant
import pro.liliya.core.asf.AgentAdmissionPolicy
import pro.liliya.core.asf.AgentAggregateBudget
import pro.liliya.core.asf.AgentAuditLedger
import pro.liliya.core.asf.AgentBlueprint
import pro.liliya.core.asf.AgentBlueprintRegistry
import pro.liliya.core.asf.AgentCognitiveRuntimeRegistration
import pro.liliya.core.asf.AgentCognitiveScope
import pro.liliya.core.asf.AgentCoordinator
import pro.liliya.core.asf.AgentFactory
import pro.liliya.core.asf.AgentFactoryBounds
import pro.liliya.core.asf.AgentHeterogeneousRuntimeFabric
import pro.liliya.core.asf.AgentWorkBudget
import pro.liliya.core.asf.AgentWorkerAdmissionPolicy
import pro.liliya.core.asf.AgentWorkerFactory
import pro.liliya.core.asf.AgentWorkerProfileSet

enum class AndroidProductRuntimeAdvisoryAgentCompositionFailure {
    EMPTY_BLUEPRINTS,
    EMPTY_RUNTIME_REGISTRATIONS,
    INVALID_CONFIGURATION
}

sealed interface AndroidProductRuntimeAdvisoryAgentCompositionResult {
    data class Ready(
        val host: AndroidProductRuntimeAdvisoryAgentHost
    ) : AndroidProductRuntimeAdvisoryAgentCompositionResult

    data class Rejected(
        val reason: AndroidProductRuntimeAdvisoryAgentCompositionFailure
    ) : AndroidProductRuntimeAdvisoryAgentCompositionResult
}

/**
 * Production composition for the advisory ASF surface.
 *
 * This builder only composes the existing bounded ASF policies and runtime adapters. It does not
 * mint Authority, own Execution, authorize tools, or perform autonomous actions. Runtime
 * registrations are exact-identity/fail-closed through AgentHeterogeneousRuntimeFabric.
 */
object AndroidProductRuntimeAdvisoryAgentComposition {
    fun create(
        blueprints: Collection<AgentBlueprint>,
        globalScope: AgentCognitiveScope,
        globalBudget: AgentWorkBudget,
        workerProfiles: AgentWorkerProfileSet,
        aggregateBudget: AgentAggregateBudget,
        registrations: Collection<AgentCognitiveRuntimeRegistration>,
        auditLedger: AgentAuditLedger,
        bounds: AgentFactoryBounds = AgentFactoryBounds.PROTOTYPE,
        timeSource: () -> Instant = { Instant.now() }
    ): AndroidProductRuntimeAdvisoryAgentCompositionResult {
        if (blueprints.isEmpty()) {
            return rejected(
                AndroidProductRuntimeAdvisoryAgentCompositionFailure.EMPTY_BLUEPRINTS
            )
        }
        if (registrations.isEmpty()) {
            return rejected(
                AndroidProductRuntimeAdvisoryAgentCompositionFailure.EMPTY_RUNTIME_REGISTRATIONS
            )
        }

        return try {
            val registry = AgentBlueprintRegistry(blueprints)
            val fabric = AgentHeterogeneousRuntimeFabric(registrations)
            val factory = AgentFactory(
                registry = registry,
                admissionPolicy = AgentAdmissionPolicy(
                    bounds = bounds,
                    globalScope = globalScope,
                    globalBudget = globalBudget
                ),
                runtimeAdapter = fabric,
                auditLedger = auditLedger,
                timeSource = timeSource
            )
            val workerFactory = AgentWorkerFactory(
                admissionPolicy = AgentWorkerAdmissionPolicy(workerProfiles),
                delegate = factory
            )
            val coordinator = AgentCoordinator(
                factory = factory,
                aggregateBudget = aggregateBudget,
                workerFactory = workerFactory
            )
            AndroidProductRuntimeAdvisoryAgentCompositionResult.Ready(
                AndroidProductRuntimeAdvisoryAgentHost(coordinator)
            )
        } catch (_: IllegalArgumentException) {
            rejected(
                AndroidProductRuntimeAdvisoryAgentCompositionFailure.INVALID_CONFIGURATION
            )
        } catch (_: IllegalStateException) {
            rejected(
                AndroidProductRuntimeAdvisoryAgentCompositionFailure.INVALID_CONFIGURATION
            )
        }
    }

    private fun rejected(
        reason: AndroidProductRuntimeAdvisoryAgentCompositionFailure
    ) = AndroidProductRuntimeAdvisoryAgentCompositionResult.Rejected(reason)
}
