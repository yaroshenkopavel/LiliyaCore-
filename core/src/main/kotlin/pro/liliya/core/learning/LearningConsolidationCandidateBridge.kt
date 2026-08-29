package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity

sealed interface LearningConsolidationCandidateBridgeResult {
    data class Converted(
        val consolidation: LearningConsolidationReference,
        val candidate: LearningCandidateReference
    ) : LearningConsolidationCandidateBridgeResult

    data class AlreadyConverted(
        val consolidation: LearningConsolidationReference,
        val candidate: LearningCandidateReference
    ) : LearningConsolidationCandidateBridgeResult

    data class ConsolidationRejected(
        val reason: LearningConsolidationConversionRejection
    ) : LearningConsolidationCandidateBridgeResult

    data class CandidateRejected(val reason: String) : LearningConsolidationCandidateBridgeResult

    data class CompletionFailedCompensated(
        val consolidation: LearningConsolidationReference
    ) : LearningConsolidationCandidateBridgeResult

    data class PartialFailure(
        val consolidation: LearningConsolidationReference,
        val candidate: LearningCandidateReference
    ) : LearningConsolidationCandidateBridgeResult
}

class LearningConsolidationCandidateBridge(
    private val consolidations: LearningConsolidationComposition,
    private val learning: LearningComposition
) {
    private val foundation = consolidations.foundation

    fun convert(
        consolidation: LearningConsolidationReference,
        candidateId: LearningCandidateId,
        createdAt: Instant
    ): LearningConsolidationCandidateBridgeResult {
        val root = foundation.rootContext(
            operation = "convertLearningConsolidationToCandidate",
            component = "LearningConsolidation",
            metadata = mapOf(
                "learningConsolidationId" to consolidation.consolidationId.value,
                "learningConsolidationGeneration" to consolidation.generation.value.toString(),
                "learningCandidateId" to candidateId.value,
                "createdAt" to createdAt.toString()
            )
        )

        val claim = when (
            val result = consolidations.claimCandidateConversion(
                consolidation,
                foundation.childContext(root, "LearningConsolidation", "claimLearningConsolidationCandidateConversion")
            )
        ) {
            is LearningConsolidationConversionResult.Claimed -> result.claim
            is LearningConsolidationConversionResult.AlreadyConverted -> {
                return observe(
                    root,
                    LearningConsolidationCandidateBridgeResult.AlreadyConverted(consolidation, result.candidate)
                )
            }
            is LearningConsolidationConversionResult.Rejected -> {
                return observe(root, LearningConsolidationCandidateBridgeResult.ConsolidationRejected(result.reason))
            }
        }

        val candidate = LearningCandidate(
            id = candidateId,
            origin = LearningOrigin.Consolidation(
                consolidationId = consolidation.consolidationId,
                generation = consolidation.generation
            ),
            proposal = claim.proposal.proposal,
            createdAt = createdAt
        )

        val ownership = when (
            val result = learning.installFromConsolidation(
                candidate,
                foundation.childContext(root, "Learning", "installLearningCandidateFromConsolidation")
            )
        ) {
            is LearningInstallResult.Installed -> result.ownership
            is LearningInstallResult.Rejected -> {
                claim.release()
                return observe(root, LearningConsolidationCandidateBridgeResult.CandidateRejected(result.reason))
            }
        }

        val candidateReference = LearningCandidateReference(ownership.candidate.id, ownership.generation)
        if (claim.complete(candidateReference)) {
            return observe(
                root,
                LearningConsolidationCandidateBridgeResult.Converted(consolidation, candidateReference)
            )
        }

        return if (ownership.remove()) {
            observe(root, LearningConsolidationCandidateBridgeResult.CompletionFailedCompensated(consolidation))
        } else {
            observe(root, LearningConsolidationCandidateBridgeResult.PartialFailure(consolidation, candidateReference))
        }
    }

    private fun observe(
        root: pro.liliya.core.logging.LogContext,
        result: LearningConsolidationCandidateBridgeResult
    ): LearningConsolidationCandidateBridgeResult {
        val severity = when (result) {
            is LearningConsolidationCandidateBridgeResult.Converted,
            is LearningConsolidationCandidateBridgeResult.AlreadyConverted -> DiagnosticSeverity.INFO
            is LearningConsolidationCandidateBridgeResult.PartialFailure -> DiagnosticSeverity.ERROR
            else -> DiagnosticSeverity.WARNING
        }
        foundation.observability.record(
            severity = severity,
            code = when (result) {
                is LearningConsolidationCandidateBridgeResult.Converted -> "LEARNING_CONSOLIDATION_CANDIDATE_CONVERTED"
                is LearningConsolidationCandidateBridgeResult.AlreadyConverted -> "LEARNING_CONSOLIDATION_CANDIDATE_ALREADY_CONVERTED"
                is LearningConsolidationCandidateBridgeResult.ConsolidationRejected -> "LEARNING_CONSOLIDATION_CANDIDATE_SOURCE_REJECTED"
                is LearningConsolidationCandidateBridgeResult.CandidateRejected -> "LEARNING_CONSOLIDATION_CANDIDATE_INSTALL_REJECTED"
                is LearningConsolidationCandidateBridgeResult.CompletionFailedCompensated -> "LEARNING_CONSOLIDATION_CANDIDATE_COMPLETION_COMPENSATED"
                is LearningConsolidationCandidateBridgeResult.PartialFailure -> "LEARNING_CONSOLIDATION_CANDIDATE_PARTIAL_FAILURE"
            },
            message = "learning consolidation candidate conversion result",
            context = root,
            metadata = mapOf("resultType" to result::class.simpleName.orEmpty())
        )
        return result
    }
}
