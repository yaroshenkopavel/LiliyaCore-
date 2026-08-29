package pro.liliya.core.learning

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

interface LearningOwnership {
    val candidate: LearningCandidate
    val generation: LearningGeneration
    fun remove(): Boolean
}

sealed interface LearningInstallResult {
    data class Installed(val ownership: LearningOwnership) : LearningInstallResult
    data class Rejected(val reason: String) : LearningInstallResult
}

class LearningComposition(
    private val foundation: FoundationComposition
) {
    private val store = LearningCandidateStore(foundation.observability)

    fun install(candidate: LearningCandidate): LearningInstallResult {
        if (candidate.origin is LearningOrigin.Consolidation) {
            val reason = "consolidation-origin candidates must be installed through the consolidation bridge"
            foundation.observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_CANDIDATE_CONSOLIDATION_ORIGIN_REJECTED",
                message = reason,
                context = foundation.rootContext(
                    operation = "installLearningCandidate",
                    component = "Learning",
                    metadata = candidateMetadata(candidate)
                )
            )
            return LearningInstallResult.Rejected(reason)
        }
        return installTrusted(
            candidate,
            foundation.rootContext(
                operation = "installLearningCandidate",
                component = "Learning",
                metadata = candidateMetadata(candidate)
            )
        )
    }

    internal fun installFromConsolidation(
        candidate: LearningCandidate,
        context: LogContext
    ): LearningInstallResult {
        require(candidate.origin is LearningOrigin.Consolidation) {
            "consolidation bridge candidate must have consolidation origin"
        }
        return installTrusted(candidate, context)
    }

    private fun installTrusted(candidate: LearningCandidate, context: LogContext): LearningInstallResult {
        val installContext = context.copy(metadata = (context.metadata + candidateMetadata(candidate)).toMap())
        return when (val result = store.register(candidate, installContext)) {
            is LearningCandidateRegistrationResult.Registered -> {
                val registration = result.registration
                LearningInstallResult.Installed(
                    ownership = object : LearningOwnership {
                        override val candidate: LearningCandidate = registration.candidate
                        override val generation: LearningGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Learning",
                                operation = "removeLearningCandidate",
                                metadata = candidateMetadata(candidate) +
                                    ("learningGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningCandidateRegistrationResult.Rejected -> LearningInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningCandidateId): LearningCandidate? = store.find(id)
    fun inspect(id: LearningCandidateId): LearningCandidateSnapshot? = store.inspect(id)
    fun contains(id: LearningCandidateId): Boolean = store.contains(id)
    fun snapshot(): List<LearningCandidate> = store.snapshot()
    fun snapshotEntries(): List<LearningCandidateSnapshot> = store.snapshotEntries()

    private fun candidateMetadata(candidate: LearningCandidate): Map<String, String> = buildMap {
        put("learningCandidateId", candidate.id.value)
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
