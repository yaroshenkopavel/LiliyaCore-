package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition

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
        val context = foundation.rootContext(
            operation = "installLearningCandidate",
            component = "Learning",
            metadata = candidateMetadata(candidate)
        )
        return when (val result = store.register(candidate, context)) {
            is LearningCandidateRegistrationResult.Registered -> {
                val registration = result.registration
                LearningInstallResult.Installed(
                    ownership = object : LearningOwnership {
                        override val candidate: LearningCandidate = registration.candidate
                        override val generation: LearningGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningCandidate",
                                component = "Learning",
                                metadata = candidateMetadata(candidate) +
                                    ("learningGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningCandidateRegistrationResult.Rejected ->
                LearningInstallResult.Rejected(result.reason)
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
                origin.sourceReference?.let { reference ->
                    put("learningSourceReference", reference.value)
                }
            }
        }
    }
}
