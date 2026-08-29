package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class LearningConsolidationReference(
    val consolidationId: LearningConsolidationId,
    val generation: LearningConsolidationGeneration
)

data class LearningConsolidationCandidateProjectionRequest(
    val consolidation: LearningConsolidationReference,
    val candidateId: LearningCandidateId,
    val createdAt: Instant
)

data class LearningConsolidationCandidateProjectionReceipt(
    val consolidation: LearningConsolidationReference,
    val candidate: LearningCandidateReference
)

enum class LearningConsolidationCandidateProjectionRejection {
    CONSOLIDATION_MISSING,
    CONSOLIDATION_GENERATION_MISMATCH,
    CONSOLIDATION_ALREADY_CLAIMED,
    ALREADY_PROJECTED_DIFFERENT_REQUEST
}

sealed interface LearningConsolidationCandidateProjectionResult {
    data class Projected(
        val receipt: LearningConsolidationCandidateProjectionReceipt
    ) : LearningConsolidationCandidateProjectionResult

    data class AlreadyProjected(
        val receipt: LearningConsolidationCandidateProjectionReceipt
    ) : LearningConsolidationCandidateProjectionResult

    data class Rejected(
        val reason: LearningConsolidationCandidateProjectionRejection
    ) : LearningConsolidationCandidateProjectionResult

    data class CandidateRejected(
        val reason: String
    ) : LearningConsolidationCandidateProjectionResult

    data class PartialFailure(
        val candidate: LearningCandidateReference
    ) : LearningConsolidationCandidateProjectionResult
}

class LearningConsolidationCandidateProjector(
    private val foundation: FoundationComposition,
    private val consolidations: LearningConsolidationComposition,
    private val learning: LearningComposition
) {
    // Serializes calls through one projector instance. Cross-projector ownership lives in the consolidation composition.
    private val lock = Any()

    fun project(
        request: LearningConsolidationCandidateProjectionRequest
    ): LearningConsolidationCandidateProjectionResult = synchronized(lock) {
        completedResult(request)?.let { return@synchronized it }

        val root = foundation.rootContext(
            operation = "projectLearningConsolidationCandidate",
            component = "LearningConsolidation",
            metadata = requestMetadata(request)
        )
        val sourceClaim = when (
            val result = consolidations.claim(
                request.consolidation,
                foundation.childContext(
                    parent = root,
                    component = "LearningConsolidation",
                    operation = "claimLearningConsolidationForCandidateProjection"
                )
            )
        ) {
            is LearningConsolidationClaimResult.Claimed -> result.claim
            is LearningConsolidationClaimResult.Rejected -> {
                val rejection = when (result.reason) {
                    LearningConsolidationClaimRejection.CONSOLIDATION_MISSING ->
                        LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_MISSING
                    LearningConsolidationClaimRejection.CONSOLIDATION_GENERATION_MISMATCH ->
                        LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_GENERATION_MISMATCH
                    LearningConsolidationClaimRejection.ALREADY_CLAIMED ->
                        LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_ALREADY_CLAIMED
                }
                return@synchronized reject(request, rejection)
            }
        }

        try {
            // Another projector may have completed after the first lookup but before this exact claim was acquired.
            completedResult(request)?.let { return@synchronized it }

            val candidate = LearningCandidate(
                id = request.candidateId,
                origin = LearningOrigin.Consolidation(
                    consolidationId = sourceClaim.reference.consolidationId,
                    generation = sourceClaim.reference.generation
                ),
                proposal = sourceClaim.proposal.proposal,
                createdAt = request.createdAt
            )

            when (
                val result = learning.install(
                    candidate = candidate,
                    context = foundation.childContext(
                        parent = root,
                        component = "Learning",
                        operation = "installConsolidationLearningCandidate",
                        metadata = mapOf("learningCandidateId" to request.candidateId.value)
                    )
                )
            ) {
                is LearningInstallResult.Rejected -> {
                    foundation.observability.record(
                        severity = DiagnosticSeverity.WARNING,
                        code = "LEARNING_CONSOLIDATION_CANDIDATE_REJECTED",
                        message = "learning consolidation candidate projection was rejected by candidate store",
                        context = root,
                        metadata = mapOf("resultType" to "candidate_rejected")
                    )
                    LearningConsolidationCandidateProjectionResult.CandidateRejected(result.reason)
                }

                is LearningInstallResult.Installed -> {
                    val receipt = LearningConsolidationCandidateProjectionReceipt(
                        consolidation = sourceClaim.reference,
                        candidate = LearningCandidateReference(
                            candidateId = result.ownership.candidate.id,
                            generation = result.ownership.generation
                        )
                    )
                    val completion = LearningConsolidationCandidateProjectionCompletion(
                        request = request,
                        receipt = receipt,
                        candidateOwnership = result.ownership
                    )
                    val existing = consolidations.recordCandidateProjection(completion)
                    if (existing == null) {
                        foundation.observability.record(
                            severity = DiagnosticSeverity.INFO,
                            code = "LEARNING_CONSOLIDATION_CANDIDATE_PROJECTED",
                            message = "learning consolidation proposal projected into learning candidate",
                            context = root,
                            metadata = receiptMetadata(receipt) + ("resultType" to "projected")
                        )
                        LearningConsolidationCandidateProjectionResult.Projected(receipt)
                    } else {
                        resolveUnexpectedCompletionConflict(
                            request = request,
                            existing = existing,
                            newlyCreated = result.ownership,
                            root = root
                        )
                    }
                }
            }
        } finally {
            sourceClaim.release()
        }
    }

    fun completedProjection(
        reference: LearningConsolidationReference
    ): LearningConsolidationCandidateProjectionReceipt? =
        consolidations.completedCandidateProjection(reference)?.receipt

    private fun completedResult(
        request: LearningConsolidationCandidateProjectionRequest
    ): LearningConsolidationCandidateProjectionResult? {
        val existing = consolidations.completedCandidateProjection(request.consolidation) ?: return null
        return if (existing.request == request) {
            observeReplay(request, existing.receipt)
            LearningConsolidationCandidateProjectionResult.AlreadyProjected(existing.receipt)
        } else {
            reject(
                request,
                LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST
            )
        }
    }

    private fun resolveUnexpectedCompletionConflict(
        request: LearningConsolidationCandidateProjectionRequest,
        existing: LearningConsolidationCandidateProjectionCompletion,
        newlyCreated: LearningOwnership,
        root: pro.liliya.core.logging.LogContext
    ): LearningConsolidationCandidateProjectionResult {
        val newReference = LearningCandidateReference(
            newlyCreated.candidate.id,
            newlyCreated.generation
        )
        if (!newlyCreated.remove()) {
            foundation.observability.record(
                severity = DiagnosticSeverity.ERROR,
                code = "LEARNING_CONSOLIDATION_CANDIDATE_PROJECTION_PARTIAL_FAILURE",
                message = "projection completion conflicted and exact candidate compensation failed",
                context = root,
                metadata = mapOf(
                    "resultType" to "partial_failure",
                    "learningCandidateId" to newReference.candidateId.value,
                    "learningGeneration" to newReference.generation.value.toString()
                )
            )
            return LearningConsolidationCandidateProjectionResult.PartialFailure(newReference)
        }

        return if (existing.request == request) {
            observeReplay(request, existing.receipt)
            LearningConsolidationCandidateProjectionResult.AlreadyProjected(existing.receipt)
        } else {
            reject(
                request,
                LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST
            )
        }
    }

    private fun reject(
        request: LearningConsolidationCandidateProjectionRequest,
        reason: LearningConsolidationCandidateProjectionRejection
    ): LearningConsolidationCandidateProjectionResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LEARNING_CONSOLIDATION_CANDIDATE_PROJECTION_REJECTED",
            message = "learning consolidation candidate projection rejected",
            context = foundation.rootContext(
                operation = "projectLearningConsolidationCandidate",
                component = "LearningConsolidation",
                metadata = requestMetadata(request) +
                    ("rejectionReason" to reason.name.lowercase())
            )
        )
        return LearningConsolidationCandidateProjectionResult.Rejected(reason)
    }

    private fun observeReplay(
        request: LearningConsolidationCandidateProjectionRequest,
        receipt: LearningConsolidationCandidateProjectionReceipt
    ) {
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_CONSOLIDATION_CANDIDATE_ALREADY_PROJECTED",
            message = "learning consolidation candidate projection already completed",
            context = foundation.rootContext(
                operation = "projectLearningConsolidationCandidate",
                component = "LearningConsolidation",
                metadata = requestMetadata(request) + receiptMetadata(receipt) +
                    ("resultType" to "already_projected")
            )
        )
    }

    private fun requestMetadata(
        request: LearningConsolidationCandidateProjectionRequest
    ): Map<String, String> = mapOf(
        "learningConsolidationId" to request.consolidation.consolidationId.value,
        "learningConsolidationGeneration" to request.consolidation.generation.value.toString(),
        "learningCandidateId" to request.candidateId.value,
        "createdAt" to request.createdAt.toString()
    )

    private fun receiptMetadata(
        receipt: LearningConsolidationCandidateProjectionReceipt
    ): Map<String, String> = mapOf(
        "learningCandidateId" to receipt.candidate.candidateId.value,
        "learningGeneration" to receipt.candidate.generation.value.toString()
    )
}
