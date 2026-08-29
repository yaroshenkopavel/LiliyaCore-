package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

interface LearningConsolidationOwnership {
    val proposal: LearningConsolidationProposal
    val generation: LearningConsolidationGeneration
    fun remove(): Boolean
}

sealed interface LearningConsolidationInstallResult {
    data class Installed(val ownership: LearningConsolidationOwnership) : LearningConsolidationInstallResult
    data class Rejected(val reason: String) : LearningConsolidationInstallResult
}

internal class LearningConsolidationConversionClaim(
    val proposal: LearningConsolidationProposal,
    val reference: LearningConsolidationReference,
    private val releaseAction: () -> Boolean,
    private val completeAction: (LearningCandidateReference) -> Boolean
) {
    fun release(): Boolean = releaseAction()
    fun complete(candidate: LearningCandidateReference): Boolean = completeAction(candidate)
}

internal sealed interface LearningConsolidationConversionResult {
    data class Claimed(val claim: LearningConsolidationConversionClaim) : LearningConsolidationConversionResult
    data class AlreadyConverted(val candidate: LearningCandidateReference) : LearningConsolidationConversionResult
    data class Rejected(val reason: LearningConsolidationConversionRejection) : LearningConsolidationConversionResult
}

class LearningConsolidationComposition(
    internal val foundation: FoundationComposition,
    private val completedMutations: LearningApplicationMutationComposition
) {
    private val store = LearningConsolidationStore(foundation.observability)

    fun install(proposal: LearningConsolidationProposal): LearningConsolidationInstallResult {
        val invalidSource = proposal.sources.firstOrNull {
            completedMutations.completedOutcomeByMutationId(it.mutation.mutationId) != it
        }
        if (invalidSource != null) {
            val reason = "learning consolidation source is not an exact completed mutation outcome"
            foundation.observability.record(
                severity = pro.liliya.core.diagnostics.DiagnosticSeverity.WARNING,
                code = "LEARNING_CONSOLIDATION_SOURCE_REJECTED",
                message = reason,
                context = foundation.rootContext(
                    "installLearningConsolidation",
                    "LearningConsolidation",
                    proposalMetadata(proposal) + mapOf(
                        "rejectedMutationId" to invalidSource.mutation.mutationId.value,
                        "rejectedMutationGeneration" to invalidSource.mutation.generation.value.toString()
                    )
                )
            )
            return LearningConsolidationInstallResult.Rejected(reason)
        }
        val context = foundation.rootContext("installLearningConsolidation", "LearningConsolidation", proposalMetadata(proposal))
        return when (val result = store.register(proposal, context)) {
            is LearningConsolidationRegistrationResult.Registered -> {
                val registration = result.registration
                LearningConsolidationInstallResult.Installed(object : LearningConsolidationOwnership {
                    override val proposal = registration.proposal
                    override val generation = registration.generation
                    override fun remove(): Boolean = registration.remove(
                        foundation.rootContext(
                            "removeLearningConsolidation",
                            "LearningConsolidation",
                            proposalMetadata(proposal) + ("learningConsolidationGeneration" to generation.value.toString())
                        )
                    )
                })
            }
            is LearningConsolidationRegistrationResult.Rejected -> LearningConsolidationInstallResult.Rejected(result.reason)
        }
    }

    internal fun claimCandidateConversion(
        reference: LearningConsolidationReference,
        context: LogContext
    ): LearningConsolidationConversionResult {
        val claimContext = context.copy(metadata = (context.metadata + referenceMetadata(reference)).toMap())
        return when (val result = store.claimConversion(reference, claimContext)) {
            is LearningConsolidationConversionClaimResult.Claimed -> {
                val registration = result.claim
                LearningConsolidationConversionResult.Claimed(
                    LearningConsolidationConversionClaim(
                        registration.proposal,
                        registration.reference,
                        releaseAction = {
                            registration.release(
                                foundation.childContext(claimContext, "LearningConsolidation", "releaseLearningConsolidationCandidateConversion")
                            )
                        },
                        completeAction = { candidate ->
                            registration.complete(
                                candidate,
                                foundation.childContext(
                                    claimContext,
                                    "LearningConsolidation",
                                    "completeLearningConsolidationCandidateConversion",
                                    mapOf(
                                        "learningCandidateId" to candidate.candidateId.value,
                                        "learningGeneration" to candidate.generation.value.toString()
                                    )
                                )
                            )
                        }
                    )
                )
            }
            is LearningConsolidationConversionClaimResult.AlreadyConverted ->
                LearningConsolidationConversionResult.AlreadyConverted(result.candidate)
            is LearningConsolidationConversionClaimResult.Rejected ->
                LearningConsolidationConversionResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningConsolidationId): LearningConsolidationProposal? = store.find(id)
    fun inspect(id: LearningConsolidationId): LearningConsolidationSnapshot? = store.inspect(id)
    fun contains(id: LearningConsolidationId): Boolean = store.contains(id)
    fun snapshot(): List<LearningConsolidationProposal> = store.snapshot()
    fun snapshotEntries(): List<LearningConsolidationSnapshot> = store.snapshotEntries()

    private fun referenceMetadata(reference: LearningConsolidationReference) = mapOf(
        "learningConsolidationId" to reference.consolidationId.value,
        "learningConsolidationGeneration" to reference.generation.value.toString()
    )

    private fun proposalMetadata(proposal: LearningConsolidationProposal) = mapOf(
        "learningConsolidationId" to proposal.id.value,
        "sourceCount" to proposal.sources.size.toString(),
        "sourceMutationIds" to proposal.sources.joinToString(",") { it.mutation.mutationId.value },
        "createdAt" to proposal.createdAt.toString()
    )
}
