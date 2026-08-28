package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningDecisionRegistration {
    val decision: LearningDecision
    val generation: LearningDecisionGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningDecisionRegistrationResult {
    data class Registered(val registration: LearningDecisionRegistration) : LearningDecisionRegistrationResult
    data class Rejected(val reason: String) : LearningDecisionRegistrationResult
}

internal class LearningDecisionStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningDecisionGeneration,
        val decision: LearningDecision
    )

    private val nextGeneration = AtomicLong(0)
    private val decisions = ConcurrentHashMap<LearningDecisionId, Entry>()

    fun register(decision: LearningDecision, context: LogContext): LearningDecisionRegistrationResult {
        val entry = Entry(
            generation = LearningDecisionGeneration(nextGeneration.incrementAndGet()),
            decision = decision
        )
        val previous = decisions.putIfAbsent(decision.id, entry)
        if (previous != null) {
            val reason = "learning decision id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_DECISION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(decision, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningDecisionRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_DECISION_REGISTERED",
            message = "learning decision registered",
            context = context,
            metadata = metadata(decision, entry.generation)
        )

        return LearningDecisionRegistrationResult.Registered(
            registration = object : LearningDecisionRegistration {
                override val decision: LearningDecision = decision
                override val generation: LearningDecisionGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = decisions.remove(decision.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "LEARNING_DECISION_REMOVED" else "LEARNING_DECISION_REMOVAL_REJECTED",
                        message = if (removed) "learning decision removed" else "learning decision registration is no longer current",
                        context = context,
                        metadata = metadata(decision, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningDecisionId): LearningDecision? = decisions[id]?.decision

    fun inspect(id: LearningDecisionId): LearningDecisionSnapshot? = decisions[id]?.let { entry ->
        LearningDecisionSnapshot(entry.decision, entry.generation)
    }

    fun contains(id: LearningDecisionId): Boolean = decisions.containsKey(id)

    fun snapshot(): List<LearningDecision> = snapshotEntries().map { it.decision }

    fun snapshotEntries(): List<LearningDecisionSnapshot> = decisions.values
        .map { LearningDecisionSnapshot(it.decision, it.generation) }
        .sortedWith(compareBy<LearningDecisionSnapshot> { it.decision.createdAt }.thenBy { it.decision.id.value })

    private fun metadata(
        decision: LearningDecision,
        generation: LearningDecisionGeneration
    ): Map<String, String> = mapOf(
        "learningDecisionId" to decision.id.value,
        "learningDecisionGeneration" to generation.value.toString(),
        "learningCandidateId" to decision.candidate.candidateId.value,
        "learningCandidateGeneration" to decision.candidate.generation.value.toString(),
        "learningDecisionDisposition" to decision.disposition.name.lowercase(),
        "createdAt" to decision.createdAt.toString()
    )
}
