package pro.liliya.core.closedloop

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion
import pro.liliya.core.reliability.ReliabilityResultId
import pro.liliya.core.reliability.ReliabilityVersion
import pro.liliya.core.strategy.StrategyCandidateId
import pro.liliya.core.strategy.StrategyVersion

@JvmInline
value class ClosedLoopId(val value: String) {
    init {
        require(value.isNotBlank()) { "closed-loop id must not be blank" }
        require(value.length <= 96) { "closed-loop id is too long" }
    }
}

@JvmInline
value class ClosedLoopVersion(val value: Long) {
    init { require(value > 0L) { "closed-loop version must be positive" } }
}

@JvmInline
value class ClosedLoopIterationId(val value: String) {
    init {
        require(value.isNotBlank()) { "closed-loop iteration id must not be blank" }
        require(value.length <= 96) { "closed-loop iteration id is too long" }
    }
}

@JvmInline
value class ClosedLoopIterationNumber(val value: Int) {
    init { require(value > 0) { "closed-loop iteration number must be positive" } }
}

data class ClosedLoopBudget(
    val maxIterations: Int,
    val maxEvidencePerIteration: Int,
    val maxTotalEvidenceReferences: Int,
    val maxElapsedSeconds: Long
) {
    init {
        require(maxIterations in 1..MAX_ITERATIONS) { "closed-loop iteration budget is out of bounds" }
        require(maxEvidencePerIteration in 1..MAX_EVIDENCE_PER_ITERATION) {
            "closed-loop per-iteration evidence budget is out of bounds"
        }
        require(maxTotalEvidenceReferences in 1..MAX_TOTAL_EVIDENCE) {
            "closed-loop total evidence budget is out of bounds"
        }
        require(maxTotalEvidenceReferences >= maxEvidencePerIteration) {
            "closed-loop total evidence budget must cover one iteration"
        }
        require(maxElapsedSeconds in 1..MAX_ELAPSED_SECONDS) {
            "closed-loop elapsed-time budget is out of bounds"
        }
    }

    companion object {
        const val MAX_ITERATIONS = 64
        const val MAX_EVIDENCE_PER_ITERATION = 128
        const val MAX_TOTAL_EVIDENCE = 4096
        const val MAX_ELAPSED_SECONDS = 24L * 60L * 60L
    }
}

data class ClosedLoopOrigin(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration
)

data class ClosedLoopDefinition(
    val id: ClosedLoopId,
    val version: ClosedLoopVersion,
    val origin: ClosedLoopOrigin,
    val budget: ClosedLoopBudget,
    val createdAt: Instant
) {
    init {
        require(id == deterministicId(version, origin, budget, createdAt)) {
            "closed-loop definition id does not match deterministic immutable identity"
        }
    }

    companion object {
        fun create(
            version: ClosedLoopVersion,
            origin: ClosedLoopOrigin,
            budget: ClosedLoopBudget,
            createdAt: Instant
        ): ClosedLoopDefinition = ClosedLoopDefinition(
            id = deterministicId(version, origin, budget, createdAt),
            version = version,
            origin = origin,
            budget = budget,
            createdAt = createdAt
        )

        private fun deterministicId(
            version: ClosedLoopVersion,
            origin: ClosedLoopOrigin,
            budget: ClosedLoopBudget,
            createdAt: Instant
        ): ClosedLoopId {
            val d = MessageDigest.getInstance("SHA-256")
            ClosedLoopIteration.put(d, "closed-loop-definition-v1")
            ClosedLoopIteration.put(d, version.value.toString())
            ClosedLoopIteration.put(d, origin.deliberationRequestId.value)
            ClosedLoopIteration.put(d, origin.deliberationGeneration.value.toString())
            ClosedLoopIteration.put(d, budget.maxIterations.toString())
            ClosedLoopIteration.put(d, budget.maxEvidencePerIteration.toString())
            ClosedLoopIteration.put(d, budget.maxTotalEvidenceReferences.toString())
            ClosedLoopIteration.put(d, budget.maxElapsedSeconds.toString())
            ClosedLoopIteration.put(d, createdAt.epochSecond.toString())
            ClosedLoopIteration.put(d, createdAt.nano.toString())
            return ClosedLoopId(
                "closed-loop-" + d.digest().joinToString("") { "%02x".format(it) }
            )
        }
    }
}

enum class ClosedLoopState {
    ACTIVE,
    STOPPED
}

enum class ClosedLoopStopReason {
    OBJECTIVE_SATISFIED,
    BUDGET_EXHAUSTED,
    STALE_STATE,
    GOVERNANCE_REJECTED,
    EXECUTION_FAILED,
    EVIDENCE_INSUFFICIENT,
    SOURCE_INCOMPATIBLE,
    MANUAL_STOP
}

data class ClosedLoopActionAttemptReference(
    val deliberationRequestId: AutonomyDeliberationRequestId,
    val deliberationGeneration: AutonomyDeliberationGeneration,
    val autonomyProposalId: AutonomyProposalId,
    val autonomyGeneration: AutonomyGeneration,
    val attemptNumber: Int,
    val planningProposalId: PlanningProposalId,
    val planningGeneration: PlanningGeneration,
    val reasoningArtifactId: ReasoningArtifactId,
    val reasoningGeneration: ReasoningGeneration,
    val decisionId: DecisionId,
    val decisionGeneration: DecisionGeneration,
    val orchestrationIntentId: OrchestrationIntentId,
    val orchestrationGeneration: OrchestrationGeneration,
    val actionId: ExecutionActionId
) {
    init {
        require(attemptNumber > 0) { "closed-loop autonomy attempt number must be positive" }
    }
}

data class ClosedLoopOutcomeReference(
    val id: OutcomeEvaluationId,
    val version: OutcomeEvaluationVersion
)

data class ClosedLoopReflectionReference(
    val id: ReflectionResultId,
    val version: ReflectionVersion
)

data class ClosedLoopStrategyReference(
    val id: StrategyCandidateId,
    val version: StrategyVersion
)

data class ClosedLoopReliabilityReference(
    val id: ReliabilityResultId,
    val version: ReliabilityVersion
)

data class ClosedLoopIteration(
    val id: ClosedLoopIterationId,
    val loopId: ClosedLoopId,
    val number: ClosedLoopIterationNumber,
    val previousIterationId: ClosedLoopIterationId?,
    val action: ClosedLoopActionAttemptReference,
    val evidence: List<RawEvidenceReference>,
    val outcome: ClosedLoopOutcomeReference,
    val reflection: ClosedLoopReflectionReference,
    val strategy: ClosedLoopStrategyReference?,
    val reliability: ClosedLoopReliabilityReference,
    val startedAt: Instant,
    val completedAt: Instant
) {
    init {
        require(
            (number.value == 1 && previousIterationId == null) ||
                (number.value > 1 && previousIterationId != null)
        ) { "closed-loop predecessor reference must match iteration number" }
        require(evidence.isNotEmpty()) { "closed-loop iteration must contain observation evidence" }
        require(evidence.size <= ClosedLoopBudget.MAX_EVIDENCE_PER_ITERATION) {
            "closed-loop iteration evidence exceeds hard bound"
        }
        require(evidence.distinct().size == evidence.size) {
            "closed-loop iteration evidence must be unique"
        }
        require(evidence == canonicalEvidence(evidence)) {
            "closed-loop iteration evidence must use canonical order"
        }
        require(completedAt >= startedAt) {
            "closed-loop iteration completion cannot predate start"
        }
        require(
            id == deterministicId(
                loopId, number, previousIterationId, action, evidence, outcome, reflection, strategy, reliability, startedAt, completedAt
            )
        ) { "closed-loop iteration id does not match deterministic content identity" }
    }

    companion object {
        fun create(
            loopId: ClosedLoopId,
            number: ClosedLoopIterationNumber,
            previousIterationId: ClosedLoopIterationId? = null,
            action: ClosedLoopActionAttemptReference,
            evidence: List<RawEvidenceReference>,
            outcome: ClosedLoopOutcomeReference,
            reflection: ClosedLoopReflectionReference,
            strategy: ClosedLoopStrategyReference?,
            reliability: ClosedLoopReliabilityReference,
            startedAt: Instant,
            completedAt: Instant
        ): ClosedLoopIteration {
            val canonical = canonicalEvidence(evidence)
            return ClosedLoopIteration(
                id = deterministicId(
                    loopId, number, previousIterationId, action, canonical, outcome, reflection, strategy, reliability, startedAt, completedAt
                ),
                loopId = loopId,
                number = number,
                previousIterationId = previousIterationId,
                action = action,
                evidence = canonical,
                outcome = outcome,
                reflection = reflection,
                strategy = strategy,
                reliability = reliability,
                startedAt = startedAt,
                completedAt = completedAt
            )
        }

        private fun canonicalEvidence(values: List<RawEvidenceReference>) =
            values.sortedWith(compareBy<RawEvidenceReference>({ it.namespace.value }, { it.id.value }))

        private fun deterministicId(
            loopId: ClosedLoopId,
            number: ClosedLoopIterationNumber,
            previousIterationId: ClosedLoopIterationId?,
            action: ClosedLoopActionAttemptReference,
            evidence: List<RawEvidenceReference>,
            outcome: ClosedLoopOutcomeReference,
            reflection: ClosedLoopReflectionReference,
            strategy: ClosedLoopStrategyReference?,
            reliability: ClosedLoopReliabilityReference,
            startedAt: Instant,
            completedAt: Instant
        ): ClosedLoopIterationId {
            val d = MessageDigest.getInstance("SHA-256")
            put(d, "closed-loop-iteration-v1")
            put(d, loopId.value)
            put(d, number.value.toString())
            put(d, previousIterationId?.value ?: "")
            put(d, action.deliberationRequestId.value)
            put(d, action.deliberationGeneration.value.toString())
            put(d, action.autonomyProposalId.value)
            put(d, action.autonomyGeneration.value.toString())
            put(d, action.attemptNumber.toString())
            put(d, action.planningProposalId.value)
            put(d, action.planningGeneration.value.toString())
            put(d, action.reasoningArtifactId.value)
            put(d, action.reasoningGeneration.value.toString())
            put(d, action.decisionId.value)
            put(d, action.decisionGeneration.value.toString())
            put(d, action.orchestrationIntentId.value)
            put(d, action.orchestrationGeneration.value.toString())
            put(d, action.actionId.value)
            evidence.forEach {
                put(d, it.namespace.value)
                put(d, it.id.value)
            }
            put(d, outcome.id.value)
            put(d, outcome.version.value.toString())
            put(d, reflection.id.value)
            put(d, reflection.version.value.toString())
            put(d, strategy?.id?.value ?: "")
            put(d, strategy?.version?.value?.toString() ?: "")
            put(d, reliability.id.value)
            put(d, reliability.version.value.toString())
            put(d, startedAt.epochSecond.toString())
            put(d, startedAt.nano.toString())
            put(d, completedAt.epochSecond.toString())
            put(d, completedAt.nano.toString())
            return ClosedLoopIterationId(
                "closed-loop-iteration-" + d.digest().joinToString("") { "%02x".format(it) }
            )
        }

        internal fun put(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
}

data class ClosedLoopRecord(
    val loop: ClosedLoopDefinition,
    val iterations: List<ClosedLoopIteration>,
    val state: ClosedLoopState,
    val stopReason: ClosedLoopStopReason?,
    val updatedAt: Instant
) {
    init {
        require(iterations.all { it.loopId == loop.id }) {
            "closed-loop iteration belongs to a different loop"
        }
        if (iterations.isNotEmpty()) {
            require(
                iterations.first().action.deliberationRequestId == loop.origin.deliberationRequestId &&
                    iterations.first().action.deliberationGeneration == loop.origin.deliberationGeneration
            ) {
                "closed-loop first action does not match immutable loop origin"
            }
        }
        require(iterations.size <= loop.budget.maxIterations) { "closed-loop iteration budget exceeded" }
        require(iterations.map { it.id }.distinct().size == iterations.size) {
            "closed-loop iterations must be unique"
        }
        require(iterations.map { it.number.value } == (1..iterations.size).toList()) {
            "closed-loop iteration numbers must be contiguous from one"
        }
        iterations.forEachIndexed { index, iteration ->
            val expectedPrevious = if (index == 0) null else iterations[index - 1].id
            require(iteration.previousIterationId == expectedPrevious) {
                "closed-loop predecessor chain is broken"
            }
        }
        require(iterations.zipWithNext().all { (a, b) -> b.startedAt >= a.completedAt }) {
            "closed-loop iterations must not overlap or move backward in time"
        }
        val totalEvidence = iterations.sumOf { it.evidence.size }
        require(totalEvidence <= loop.budget.maxTotalEvidenceReferences) {
            "closed-loop total evidence budget exceeded"
        }
        require(updatedAt >= loop.createdAt) { "closed-loop update cannot predate creation" }
        if (iterations.isNotEmpty()) {
            require(iterations.first().startedAt >= loop.createdAt) {
                "closed-loop first iteration cannot predate loop creation"
            }
            require(iterations.last().completedAt <= updatedAt) {
                "closed-loop update time cannot predate final iteration"
            }
        }
        val elapsedSeconds = updatedAt.epochSecond - loop.createdAt.epochSecond
        require(elapsedSeconds <= loop.budget.maxElapsedSeconds) {
            "closed-loop elapsed-time budget exceeded"
        }
        val budgetExhausted =
            iterations.size >= loop.budget.maxIterations ||
                totalEvidence >= loop.budget.maxTotalEvidenceReferences ||
                elapsedSeconds >= loop.budget.maxElapsedSeconds
        require(
            (state == ClosedLoopState.ACTIVE && stopReason == null) ||
                (state == ClosedLoopState.STOPPED && stopReason != null)
        ) { "closed-loop stop reason must match loop state" }
        require(state != ClosedLoopState.ACTIVE || !budgetExhausted) {
            "closed-loop cannot remain active after a hard budget is exhausted"
        }
        require(stopReason != ClosedLoopStopReason.BUDGET_EXHAUSTED || budgetExhausted) {
            "closed-loop budget-exhausted stop reason requires an exhausted hard budget"
        }
    }
}
