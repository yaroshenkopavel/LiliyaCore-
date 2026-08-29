package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningCandidateRegistration {
    val candidate: LearningCandidate
    val generation: LearningGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningCandidateRegistrationResult {
    data class Registered(val registration: LearningCandidateRegistration) : LearningCandidateRegistrationResult
    data class Rejected(val reason: String) : LearningCandidateRegistrationResult
}

internal class LearningCandidateStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningGeneration,
        val candidate: LearningCandidate
    )

    private val nextGeneration = AtomicLong(0)
    private val candidates = ConcurrentHashMap<LearningCandidateId, Entry>()

    fun register(candidate: LearningCandidate, context: LogContext): LearningCandidateRegistrationResult {
        val entry = Entry(LearningGeneration(nextGeneration.incrementAndGet()), candidate)
        val previous = candidates.putIfAbsent(candidate.id, entry)
        if (previous != null) {
            val reason = "learning candidate id is already registered"
            observability.record(
                DiagnosticSeverity.WARNING,
                "LEARNING_CANDIDATE_REGISTRATION_REJECTED",
                reason,
                context,
                metadata(candidate, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningCandidateRegistrationResult.Rejected(reason)
        }

        observability.record(
            DiagnosticSeverity.INFO,
            "LEARNING_CANDIDATE_REGISTERED",
            "learning candidate registered",
            context,
            metadata(candidate, entry.generation)
        )

        return LearningCandidateRegistrationResult.Registered(
            object : LearningCandidateRegistration {
                override val candidate = candidate
                override val generation = entry.generation
                override fun remove(context: LogContext): Boolean {
                    val removed = candidates.remove(candidate.id, entry)
                    observability.record(
                        if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        if (removed) "LEARNING_CANDIDATE_REMOVED" else "LEARNING_CANDIDATE_REMOVAL_REJECTED",
                        if (removed) "learning candidate removed" else "learning candidate registration is no longer current",
                        context,
                        metadata(candidate, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningCandidateId): LearningCandidate? = candidates[id]?.candidate
    fun inspect(id: LearningCandidateId): LearningCandidateSnapshot? = candidates[id]?.let { LearningCandidateSnapshot(it.candidate, it.generation) }
    fun contains(id: LearningCandidateId): Boolean = candidates.containsKey(id)
    fun snapshot(): List<LearningCandidate> = snapshotEntries().map { it.candidate }
    fun snapshotEntries(): List<LearningCandidateSnapshot> = candidates.values
        .map { LearningCandidateSnapshot(it.candidate, it.generation) }
        .sortedWith(compareBy<LearningCandidateSnapshot> { it.candidate.createdAt }.thenBy { it.candidate.id.value })

    private fun metadata(candidate: LearningCandidate, generation: LearningGeneration): Map<String, String> = buildMap {
        put("learningCandidateId", candidate.id.value)
        put("learningGeneration", generation.value.toString())
        put("createdAt", candidate.createdAt.toString())
        when (val origin = candidate.origin) {
            is LearningOrigin.Reflection -> {
                put("learningOriginType", "reflection")
                put("reflectionRecordId", origin.recordId.value)
                put("reflectionGeneration", origin.generation.value.toString())
            }
            is LearningOrigin.Consolidation -> {
                put("learningOriginType", "consolidation")
                put("learningConsolidationId", origin.consolidationId.value)
                put("learningConsolidationGeneration", origin.generation.value.toString())
            }
            is LearningOrigin.Declared -> {
                put("learningOriginType", "declared")
                put("learningSourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("learningSourceReference", it.value) }
            }
        }
    }
}
