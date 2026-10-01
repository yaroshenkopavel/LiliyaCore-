package pro.liliya.core.asf

import java.nio.charset.StandardCharsets
import java.time.Instant

enum class AgentFactoryControlPressure {
    NONE,
    ELEVATED,
    CRITICAL
}

data class AgentFactoryControlObservation(
    val rootTaskId: AgentRootTaskId,
    val observedAt: Instant,
    val expiresAt: Instant,
    val cancelled: Boolean,
    val terminal: Boolean,
    val aggregateBudget: AgentAggregateBudget,
    val aggregateUsage: AgentAggregateUsage,
    val activeWorkerClasses: List<AgentWorkerClass>,
    val remainingRequirements: List<AgentWorkerRequirement>,
    val pressure: AgentFactoryControlPressure,
    val consecutiveLowValueSteps: Int,
    val repeatedContradictionCount: Int,
    val recompositionCount: Int,
    val controlDecisionCount: Int,
    val deterministicResolutionAvailable: Boolean,
    val observationReferences: List<String>
) {
    init {
        require(!expiresAt.isBefore(observedAt)) {
            "factory control expiry cannot precede observation"
        }
        require(activeWorkerClasses == activeWorkerClasses.distinct().sortedBy { it.order }) {
            "factory control worker classes must be unique and canonical"
        }
        require(remainingRequirements == remainingRequirements.distinct().sortedBy { it.ordinal }) {
            "factory control requirements must be unique and canonical"
        }
        require(consecutiveLowValueSteps >= 0)
        require(repeatedContradictionCount >= 0)
        require(recompositionCount >= 0)
        require(controlDecisionCount >= 0)
        require(observationReferences.isNotEmpty()) {
            "factory control observation requires provenance"
        }
        require(observationReferences.size <= 64) {
            "factory control observation provenance exceeds bounded count"
        }
        require(observationReferences == observationReferences.distinct().sorted()) {
            "factory control observation provenance must be unique and canonical"
        }
        observationReferences.forEach {
            require(it.isNotBlank())
            require(it.toByteArray(StandardCharsets.UTF_8).size <= 256)
        }
    }
}

data class AgentFactoryControlPolicy(
    val maxRecompositions: Int = 2,
    val maxControlDecisions: Int = 8,
    val lowValueSimplifyThreshold: Int = 2,
    val contradictionRecomposeThreshold: Int = 2
) {
    init {
        require(maxRecompositions in 0..8)
        require(maxControlDecisions in 1..32)
        require(lowValueSimplifyThreshold > 0)
        require(contradictionRecomposeThreshold > 0)
    }
}

enum class AgentFactoryControlDecisionKind {
    STOP,
    CONTINUE,
    SIMPLIFY,
    RECOMPOSE,
    ABANDON
}

enum class AgentFactoryControlSimplificationTarget {
    DETERMINISTIC_FALLBACK,
    SINGLE_WORKER
}

enum class AgentFactoryControlReason {
    CANCELLED,
    EXPIRED,
    TERMINAL,
    BUDGET_EXHAUSTED,
    CRITICAL_PRESSURE,
    RECOMPOSITION_LIMIT,
    CONTROL_DECISION_LIMIT,
    LOW_VALUE_BRANCH,
    REPEATED_CONTRADICTION,
    NO_REMAINING_REQUIREMENTS,
    CONTINUE_WITHIN_BOUNDS
}

data class AgentFactoryControlDecision(
    val kind: AgentFactoryControlDecisionKind,
    val reason: AgentFactoryControlReason,
    val nextBudget: AgentAggregateBudget?,
    val nextCapacity: AgentTeamCapacityEnvelope?,
    val simplificationTarget: AgentFactoryControlSimplificationTarget?,
    val requiresFreshAdmission: Boolean,
    val observationReferences: List<String>
) {
    init {
        require(observationReferences.isNotEmpty())
        when (kind) {
            AgentFactoryControlDecisionKind.RECOMPOSE -> {
                require(nextBudget != null)
                require(nextCapacity != null)
                require(simplificationTarget == null)
                require(requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.SIMPLIFY -> {
                require(nextBudget != null)
                require(simplificationTarget != null)
                if (simplificationTarget == AgentFactoryControlSimplificationTarget.SINGLE_WORKER) {
                    require(nextCapacity != null)
                    require(nextCapacity.maxWorkers == 1)
                } else {
                    require(nextCapacity == null)
                }
                require(!requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.CONTINUE -> {
                require(nextBudget != null)
                require(nextCapacity != null)
                require(simplificationTarget == null)
                require(!requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.STOP,
            AgentFactoryControlDecisionKind.ABANDON -> {
                require(nextBudget == null)
                require(nextCapacity == null)
                require(simplificationTarget == null)
                require(!requiresFreshAdmission)
            }
        }
    }
}

data class AgentFactoryControlAuditRecord(
    val rootTaskId: AgentRootTaskId,
    val observedAt: Instant,
    val kind: AgentFactoryControlDecisionKind,
    val reason: AgentFactoryControlReason,
    val recompositionCount: Int,
    val controlDecisionCount: Int,
    val observationReferences: List<String>
)

fun interface AgentFactoryControlAuditLedger {
    fun append(record: AgentFactoryControlAuditRecord)
}

class AgentMetacognitiveFactoryController(
    private val policy: AgentFactoryControlPolicy = AgentFactoryControlPolicy(),
    private val auditLedger: AgentFactoryControlAuditLedger = AgentFactoryControlAuditLedger { }
) {
    fun decide(observation: AgentFactoryControlObservation): AgentFactoryControlDecision {
        val decision = decideInternal(observation)
        auditLedger.append(
            AgentFactoryControlAuditRecord(
                rootTaskId = observation.rootTaskId,
                observedAt = observation.observedAt,
                kind = decision.kind,
                reason = decision.reason,
                recompositionCount = observation.recompositionCount,
                controlDecisionCount = observation.controlDecisionCount,
                observationReferences = observation.observationReferences
            )
        )
        return decision
    }

    private fun decideInternal(
        observation: AgentFactoryControlObservation
    ): AgentFactoryControlDecision {
        if (observation.controlDecisionCount >= policy.maxControlDecisions) {
            return terminal(
                observation,
                AgentFactoryControlDecisionKind.ABANDON,
                AgentFactoryControlReason.CONTROL_DECISION_LIMIT
            )
        }
        if (observation.cancelled) {
            return terminal(observation, AgentFactoryControlDecisionKind.STOP, AgentFactoryControlReason.CANCELLED)
        }
        if (!observation.observedAt.isBefore(observation.expiresAt)) {
            return terminal(observation, AgentFactoryControlDecisionKind.STOP, AgentFactoryControlReason.EXPIRED)
        }
        if (observation.terminal) {
            return terminal(observation, AgentFactoryControlDecisionKind.STOP, AgentFactoryControlReason.TERMINAL)
        }
        if (!observation.aggregateBudget.allows(observation.aggregateUsage)) {
            return terminal(observation, AgentFactoryControlDecisionKind.STOP, AgentFactoryControlReason.BUDGET_EXHAUSTED)
        }
        val remaining = remainingBudget(observation) ?: return terminal(
            observation,
            AgentFactoryControlDecisionKind.STOP,
            AgentFactoryControlReason.BUDGET_EXHAUSTED
        )
        if (observation.pressure == AgentFactoryControlPressure.CRITICAL) {
            return terminal(observation, AgentFactoryControlDecisionKind.ABANDON, AgentFactoryControlReason.CRITICAL_PRESSURE)
        }
        if (observation.remainingRequirements.isEmpty()) {
            return terminal(observation, AgentFactoryControlDecisionKind.STOP, AgentFactoryControlReason.NO_REMAINING_REQUIREMENTS)
        }

        if (observation.repeatedContradictionCount >= policy.contradictionRecomposeThreshold) {
            if (observation.recompositionCount >= policy.maxRecompositions) {
                return terminal(
                    observation,
                    AgentFactoryControlDecisionKind.ABANDON,
                    AgentFactoryControlReason.RECOMPOSITION_LIMIT
                )
            }
            val capacity = reducedCapacity(observation, remaining)
            return AgentFactoryControlDecision(
                kind = AgentFactoryControlDecisionKind.RECOMPOSE,
                reason = AgentFactoryControlReason.REPEATED_CONTRADICTION,
                nextBudget = remaining,
                nextCapacity = capacity,
                simplificationTarget = null,
                requiresFreshAdmission = true,
                observationReferences = observation.observationReferences
            )
        }

        if (
            observation.pressure == AgentFactoryControlPressure.ELEVATED ||
            observation.consecutiveLowValueSteps >= policy.lowValueSimplifyThreshold
        ) {
            val target = if (observation.deterministicResolutionAvailable) {
                AgentFactoryControlSimplificationTarget.DETERMINISTIC_FALLBACK
            } else {
                AgentFactoryControlSimplificationTarget.SINGLE_WORKER
            }
            return AgentFactoryControlDecision(
                kind = AgentFactoryControlDecisionKind.SIMPLIFY,
                reason = AgentFactoryControlReason.LOW_VALUE_BRANCH,
                nextBudget = remaining,
                nextCapacity = if (
                    target == AgentFactoryControlSimplificationTarget.SINGLE_WORKER
                ) {
                    singleWorkerCapacity(observation, remaining)
                } else {
                    null
                },
                simplificationTarget = target,
                requiresFreshAdmission = false,
                observationReferences = observation.observationReferences
            )
        }

        return AgentFactoryControlDecision(
            kind = AgentFactoryControlDecisionKind.CONTINUE,
            reason = AgentFactoryControlReason.CONTINUE_WITHIN_BOUNDS,
            nextBudget = remaining,
            nextCapacity = currentOrReducedCapacity(observation, remaining),
            simplificationTarget = null,
            requiresFreshAdmission = false,
            observationReferences = observation.observationReferences
        )
    }

    private fun remainingBudget(
        observation: AgentFactoryControlObservation
    ): AgentAggregateBudget? {
        val budget = observation.aggregateBudget
        val usage = observation.aggregateUsage
        val wall = budget.maxWallClockMillis - usage.wallClockMillis
        val inference = budget.maxInferenceUnits - usage.inferenceUnits
        val context = budget.maxContextBytes - usage.contextBytes
        val retrieval = budget.maxRetrievalItems - usage.retrievalItems
        val artifacts = budget.maxArtifacts - usage.artifactCount
        val agents = budget.maxAgents - usage.agentsStarted
        if (
            wall <= 0L ||
            inference <= 0L ||
            context <= 0 ||
            retrieval < 0 ||
            artifacts <= 0 ||
            agents <= 0
        ) return null
        return AgentAggregateBudget(
            maxWallClockMillis = wall,
            maxInferenceUnits = inference,
            maxContextBytes = context,
            maxRetrievalItems = retrieval,
            maxArtifacts = artifacts,
            maxAgents = agents
        )
    }

    private fun singleWorkerCapacity(
        observation: AgentFactoryControlObservation,
        budget: AgentAggregateBudget
    ): AgentTeamCapacityEnvelope {
        val active = observation.activeWorkerClasses
        require(active.isNotEmpty()) {
            "single-worker simplification requires an already active worker class"
        }
        val selected = active.minBy { it.order }
        return AgentTeamCapacityEnvelope(
            allowedWorkerClasses = setOf(selected),
            maxWorkers = 1.coerceAtMost(budget.maxAgents)
        )
    }

    private fun reducedCapacity(
        observation: AgentFactoryControlObservation,
        budget: AgentAggregateBudget
    ): AgentTeamCapacityEnvelope {
        val allowed = observation.activeWorkerClasses
            .ifEmpty { AgentWorkerClass.entries.toList() }
            .filter { workerClass ->
                when (observation.pressure) {
                    AgentFactoryControlPressure.CRITICAL -> false
                    AgentFactoryControlPressure.ELEVATED ->
                        workerClass != AgentWorkerClass.FULL
                    AgentFactoryControlPressure.NONE -> true
                }
            }
            .toSet()
        require(allowed.isNotEmpty()) {
            "recomposition cannot invent a worker class under pressure"
        }
        return AgentTeamCapacityEnvelope(
            allowedWorkerClasses = allowed,
            maxWorkers = minOf(budget.maxAgents, allowed.size)
        )
    }

    private fun currentOrReducedCapacity(
        observation: AgentFactoryControlObservation,
        budget: AgentAggregateBudget
    ): AgentTeamCapacityEnvelope {
        val allowed = observation.activeWorkerClasses
            .ifEmpty { AgentWorkerClass.entries.toList() }
            .toSet()
        return AgentTeamCapacityEnvelope(
            allowedWorkerClasses = allowed,
            maxWorkers = minOf(budget.maxAgents, allowed.size)
        )
    }

    private fun terminal(
        observation: AgentFactoryControlObservation,
        kind: AgentFactoryControlDecisionKind,
        reason: AgentFactoryControlReason
    ) = AgentFactoryControlDecision(
        kind = kind,
        reason = reason,
        nextBudget = null,
        nextCapacity = null,
        simplificationTarget = null,
        requiresFreshAdmission = false,
        observationReferences = observation.observationReferences
    )
}
