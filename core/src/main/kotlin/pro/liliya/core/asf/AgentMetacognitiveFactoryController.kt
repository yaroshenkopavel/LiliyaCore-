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
    val lowValueSimplifyThreshold: Int = 2,
    val contradictionRecomposeThreshold: Int = 2
) {
    init {
        require(maxRecompositions in 0..8)
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

enum class AgentFactoryControlReason {
    CANCELLED,
    EXPIRED,
    TERMINAL,
    BUDGET_EXHAUSTED,
    CRITICAL_PRESSURE,
    RECOMPOSITION_LIMIT,
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
    val requiresFreshAdmission: Boolean,
    val observationReferences: List<String>
) {
    init {
        require(observationReferences.isNotEmpty())
        when (kind) {
            AgentFactoryControlDecisionKind.RECOMPOSE -> {
                require(nextBudget != null)
                require(nextCapacity != null)
                require(requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.SIMPLIFY -> {
                require(nextBudget != null)
                require(nextCapacity != null)
                require(!requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.CONTINUE -> {
                require(nextBudget != null)
                require(nextCapacity != null)
                require(!requiresFreshAdmission)
            }
            AgentFactoryControlDecisionKind.STOP,
            AgentFactoryControlDecisionKind.ABANDON -> {
                require(nextBudget == null)
                require(nextCapacity == null)
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
                observationReferences = observation.observationReferences
            )
        )
        return decision
    }

    private fun decideInternal(
        observation: AgentFactoryControlObservation
    ): AgentFactoryControlDecision {
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
                requiresFreshAdmission = true,
                observationReferences = observation.observationReferences
            )
        }

        if (
            observation.pressure == AgentFactoryControlPressure.ELEVATED ||
            observation.consecutiveLowValueSteps >= policy.lowValueSimplifyThreshold
        ) {
            return AgentFactoryControlDecision(
                kind = AgentFactoryControlDecisionKind.SIMPLIFY,
                reason = AgentFactoryControlReason.LOW_VALUE_BRANCH,
                nextBudget = remaining,
                nextCapacity = reducedCapacity(observation, remaining),
                requiresFreshAdmission = false,
                observationReferences = observation.observationReferences
            )
        }

        return AgentFactoryControlDecision(
            kind = AgentFactoryControlDecisionKind.CONTINUE,
            reason = AgentFactoryControlReason.CONTINUE_WITHIN_BOUNDS,
            nextBudget = remaining,
            nextCapacity = currentOrReducedCapacity(observation, remaining),
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
            .ifEmpty { setOf(AgentWorkerClass.NANO) }
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
        requiresFreshAdmission = false,
        observationReferences = observation.observationReferences
    )
}
