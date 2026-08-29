package pro.liliya.core.decision

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface DecisionRegistration {
    val decision: DecisionRecord
    val generation: DecisionGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface DecisionRegistrationResult {
    data class Registered(val registration: DecisionRegistration) : DecisionRegistrationResult
    data class Rejected(val reason: String) : DecisionRegistrationResult
}

internal class DecisionStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: DecisionGeneration,
        val decision: DecisionRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val decisions = ConcurrentHashMap<DecisionId, Entry>()

    fun register(
        decision: DecisionRecord,
        context: LogContext
    ): DecisionRegistrationResult {
        val entry = Entry(
            generation = DecisionGeneration(nextGeneration.incrementAndGet()),
            decision = decision
        )
        val previous = decisions.putIfAbsent(decision.id, entry)
        if (previous != null) {
            val reason = "decision id is already registered"
            observability.record(
                DiagnosticSeverity.WARNING,
                "DECISION_REGISTRATION_REJECTED",
                reason,
                context,
                metadata(decision, previous.generation) + ("rejectionReason" to reason)
            )
            return DecisionRegistrationResult.Rejected(reason)
        }

        observability.record(
            DiagnosticSeverity.INFO,
            "DECISION_REGISTERED",
            "decision registered",
            context,
            metadata(decision, entry.generation)
        )

        return DecisionRegistrationResult.Registered(
            object : DecisionRegistration {
                override val decision: DecisionRecord = decision
                override val generation: DecisionGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = decisions.remove(decision.id, entry)
                    observability.record(
                        if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        if (removed) "DECISION_REMOVED" else "DECISION_REMOVAL_REJECTED",
                        if (removed) "decision removed" else "decision registration is no longer current",
                        context,
                        metadata(decision, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: DecisionId): DecisionRecord? = decisions[id]?.decision

    fun inspect(id: DecisionId): DecisionSnapshot? = decisions[id]?.let { entry ->
        DecisionSnapshot(entry.decision, entry.generation)
    }

    fun contains(id: DecisionId): Boolean = decisions.containsKey(id)

    fun snapshot(): List<DecisionRecord> = snapshotEntries().map { it.decision }

    fun snapshotEntries(): List<DecisionSnapshot> = decisions.values
        .map { DecisionSnapshot(it.decision, it.generation) }
        .sortedWith(compareBy<DecisionSnapshot> { it.decision.createdAt }.thenBy { it.decision.id.value })

    private fun metadata(
        decision: DecisionRecord,
        generation: DecisionGeneration
    ): Map<String, String> = buildMap {
        put("decisionId", decision.id.value)
        put("decisionGeneration", generation.value.toString())
        put("decisionInputCount", decision.inputs.size.toString())
        put("decisionPlanningInputCount", decision.inputs.count { it is DecisionInputReference.Planning }.toString())
        put("decisionReasoningInputCount", decision.inputs.count { it is DecisionInputReference.Reasoning }.toString())
        put("decisionOptionCount", decision.options.size.toString())
        put("selectedDecisionOptionId", decision.selectedOptionId.value)
        put("createdAt", decision.createdAt.toString())
    }
}
