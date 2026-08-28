package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition

interface LearningDecisionOwnership {
    val decision: LearningDecision
    val generation: LearningDecisionGeneration
    fun remove(): Boolean
}

sealed interface LearningDecisionInstallResult {
    data class Installed(val ownership: LearningDecisionOwnership) : LearningDecisionInstallResult
    data class Rejected(val reason: String) : LearningDecisionInstallResult
}

class LearningDecisionComposition(
    private val foundation: FoundationComposition
) {
    private val store = LearningDecisionStore(foundation.observability)

    fun install(decision: LearningDecision): LearningDecisionInstallResult {
        val context = foundation.rootContext(
            operation = "installLearningDecision",
            component = "LearningDecision",
            metadata = decisionMetadata(decision)
        )
        return when (val result = store.register(decision, context)) {
            is LearningDecisionRegistrationResult.Registered -> {
                val registration = result.registration
                LearningDecisionInstallResult.Installed(
                    ownership = object : LearningDecisionOwnership {
                        override val decision: LearningDecision = registration.decision
                        override val generation: LearningDecisionGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningDecision",
                                component = "LearningDecision",
                                metadata = decisionMetadata(decision) +
                                    ("learningDecisionGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningDecisionRegistrationResult.Rejected ->
                LearningDecisionInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningDecisionId): LearningDecision? = store.find(id)

    fun inspect(id: LearningDecisionId): LearningDecisionSnapshot? = store.inspect(id)

    fun contains(id: LearningDecisionId): Boolean = store.contains(id)

    fun snapshot(): List<LearningDecision> = store.snapshot()

    fun snapshotEntries(): List<LearningDecisionSnapshot> = store.snapshotEntries()

    private fun decisionMetadata(decision: LearningDecision): Map<String, String> = mapOf(
        "learningDecisionId" to decision.id.value,
        "learningCandidateId" to decision.candidate.candidateId.value,
        "learningCandidateGeneration" to decision.candidate.generation.value.toString(),
        "learningDecisionDisposition" to decision.disposition.name.lowercase(),
        "createdAt" to decision.createdAt.toString()
    )
}
