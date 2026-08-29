package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

interface LearningConsolidationOwnership {
    val proposal: LearningConsolidationProposal
    val generation: LearningConsolidationGeneration
    fun remove(): Boolean
}

sealed interface LearningConsolidationInstallResult {
    data class Installed(
        val ownership: LearningConsolidationOwnership
    ) : LearningConsolidationInstallResult

    data class Rejected(val reason: String) : LearningConsolidationInstallResult
}

internal class LearningConsolidationClaim(
    val proposal: LearningConsolidationProposal,
    val reference: LearningConsolidationReference,
    private val releaseAction: () -> Boolean
) {
    fun release(): Boolean = releaseAction()
}

internal sealed interface LearningConsolidationClaimResult {
    data class Claimed(val claim: LearningConsolidationClaim) : LearningConsolidationClaimResult
    data class Rejected(val reason: LearningConsolidationClaimRejection) : LearningConsolidationClaimResult
}

class LearningConsolidationComposition(
    private val foundation: FoundationComposition,
    private val completedMutations: LearningApplicationMutationComposition
) {
    private val store = LearningConsolidationStore(foundation.observability)

    fun install(proposal: LearningConsolidationProposal): LearningConsolidationInstallResult {
        val invalidSource = proposal.sources.firstOrNull { source ->
            completedMutations.completedOutcomeByMutationId(source.mutation.mutationId) != source
        }
        if (invalidSource != null) {
            val reason = "learning consolidation source is not an exact completed mutation outcome"
            foundation.observability.record(
                severity = pro.liliya.core.diagnostics.DiagnosticSeverity.WARNING,
                code = "LEARNING_CONSOLIDATION_SOURCE_REJECTED",
                message = reason,
                context = foundation.rootContext(
                    operation = "installLearningConsolidation",
                    component = "LearningConsolidation",
                    metadata = proposalMetadata(proposal) + mapOf(
                        "rejectedMutationId" to invalidSource.mutation.mutationId.value,
                        "rejectedMutationGeneration" to invalidSource.mutation.generation.value.toString()
                    )
                )
            )
            return LearningConsolidationInstallResult.Rejected(reason)
        }

        val context = foundation.rootContext(
            operation = "installLearningConsolidation",
            component = "LearningConsolidation",
            metadata = proposalMetadata(proposal)
        )
        return when (val result = store.register(proposal, context)) {
            is LearningConsolidationRegistrationResult.Registered -> {
                val registration = result.registration
                LearningConsolidationInstallResult.Installed(
                    ownership = object : LearningConsolidationOwnership {
                        override val proposal: LearningConsolidationProposal = registration.proposal
                        override val generation: LearningConsolidationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningConsolidation",
                                component = "LearningConsolidation",
                                metadata = proposalMetadata(proposal) +
                                    ("learningConsolidationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningConsolidationRegistrationResult.Rejected ->
                LearningConsolidationInstallResult.Rejected(result.reason)
        }
    }

    internal fun claim(
        reference: LearningConsolidationReference,
        context: LogContext
    ): LearningConsolidationClaimResult {
        val claimContext = context.copy(
            metadata = (context.metadata + mapOf(
                "learningConsolidationId" to reference.consolidationId.value,
                "learningConsolidationGeneration" to reference.generation.value.toString()
            )).toMap()
        )
        return when (val result = store.claim(reference, claimContext)) {
            is LearningConsolidationClaimRegistrationResult.Claimed -> {
                val registration = result.claim
                LearningConsolidationClaimResult.Claimed(
                    LearningConsolidationClaim(
                        proposal = registration.proposal,
                        reference = LearningConsolidationReference(
                            registration.proposal.id,
                            registration.generation
                        ),
                        releaseAction = {
                            registration.release(
                                foundation.childContext(
                                    parent = claimContext,
                                    component = "LearningConsolidation",
                                    operation = "releaseLearningConsolidationClaim",
                                    metadata = proposalMetadata(registration.proposal) +
                                        ("learningConsolidationGeneration" to registration.generation.value.toString())
                                )
                            )
                        }
                    )
                )
            }

            is LearningConsolidationClaimRegistrationResult.Rejected ->
                LearningConsolidationClaimResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningConsolidationId): LearningConsolidationProposal? = store.find(id)

    fun inspect(id: LearningConsolidationId): LearningConsolidationSnapshot? = store.inspect(id)

    fun contains(id: LearningConsolidationId): Boolean = store.contains(id)

    fun snapshot(): List<LearningConsolidationProposal> = store.snapshot()

    fun snapshotEntries(): List<LearningConsolidationSnapshot> = store.snapshotEntries()

    private fun proposalMetadata(proposal: LearningConsolidationProposal): Map<String, String> = mapOf(
        "learningConsolidationId" to proposal.id.value,
        "sourceCount" to proposal.sources.size.toString(),
        "sourceMutationIds" to proposal.sources.joinToString(",") { it.mutation.mutationId.value },
        "createdAt" to proposal.createdAt.toString()
    )
}
