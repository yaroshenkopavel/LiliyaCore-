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
}

class LearningConsolidationCandidateProjector(
    private val foundation: FoundationComposition,
    private val consolidations: LearningConsolidationComposition,
    private val learning: LearningComposition
) {
    private data class CompletedProjection(
        val request: LearningConsolidationCandidateProjectionRequest,
        val receipt: LearningConsolidationCandidateProjectionReceipt,
        val candidateOwnership: LearningOwnership
    )

    private val lock = Any()
    private val completed = mutableMapOf<LearningConsolidationReference, CompletedProjection>()

    fun project(
        request: LearningConsolidationCandidateProjectionRequest
    ): LearningConsolidationCandidateProjectionResult = synchronized(lock) {
        completed[request.consolidation]?.let { existing ->
            return@synchronized if (existing.request == request) {
                observeReplay(request, existing.receipt)
                LearningConsolidationCandidateProjectionResult.AlreadyProjected(existing.receipt)
            } else {
                observeRejected(
                    request,
                    LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST
                )
                LearningConsolidationCandidateProjectionResult.Rejected(
                    LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST
                )
            }
        }

        val snapshot = consolidations.inspect(request.consolidation.consolidationId)
            ?: return@synchronized reject(
                request,
                LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_MISSING
            )
        if (snapshot.generation != request.consolidation.generation) {
            return@synchronized reject(
                request,
                LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_GENERATION_MISMATCH
            )
        }

        val root = foundation.rootContext(
            operation = "projectLearningConsolidationCandidate",
            component = "LearningConsolidation",
            metadata = requestMetadata(request)
        )
        val candidate = LearningCandidate(
            id = request.candidateId,
            origin = LearningOrigin.Consolidation(
                consolidationId = request.consolidation.consolidationId,
                generation = request.consolidation.generation
            ),
            proposal = snapshot.proposal.proposal,
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
                    consolidation = request.consolidation,
                    candidate = LearningCandidateReference(
                        candidateId = result.ownership.candidate.id,
                        generation = result.ownership.generation
                    )
                )
                completed[request.consolidation] = CompletedProjection(
                    request = request,
                    receipt = receipt,
                    candidateOwnership = result.ownership
                )
                foundation.observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "LEARNING_CONSOLIDATION_CANDIDATE_PROJECTED",
                    message = "learning consolidation proposal projected into learning candidate",
                    context = root,
                    metadata = receiptMetadata(receipt) + ("resultType" to "projected")
                )
                LearningConsolidationCandidateProjectionResult.Projected(receipt)
            }
        }
    }

    fun completedProjection(
        reference: LearningConsolidationReference
    ): LearningConsolidationCandidateProjectionReceipt? = synchronized(lock) {
        completed[reference]?.receipt
    }

    private fun reject(
        request: LearningConsolidationCandidateProjectionRequest,
        reason: LearningConsolidationCandidateProjectionRejection
    ): LearningConsolidationCandidateProjectionResult.Rejected {
        observeRejected(request, reason)
        return LearningConsolidationCandidateProjectionResult.Rejected(reason)
    }

    private fun observeRejected(
        request: LearningConsolidationCandidateProjectionRequest,
        reason: LearningConsolidationCandidateProjectionRejection
    ) {
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
