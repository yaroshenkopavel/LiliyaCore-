package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveGovernedLearningFailure
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveGovernedLearningTerminalStatus
import pro.liliya.core.cognitive.CognitiveLearningReference

/**
 * Product-facing evidence adapter for one exact finalized learning candidate.
 *
 * This reference is evidence only. It is not policy, Authority, target selection or mutation
 * permission. The exact production governed-learning path remains authoritative.
 */
class ProductLearningFollowUpReference internal constructor(
    internal val cognitive: CognitiveLearningReference
) {
    val generation: Long get() = cognitive.generation.value

    override fun toString(): String =
        "ProductLearningFollowUpReference(id=<redacted>,generation=$generation)"
}

fun ProductTurnResult.Completed.learningFollowUpReference(): ProductLearningFollowUpReference =
    ProductLearningFollowUpReference(finalization.learning)

enum class ProductLearningSemanticStatus {
    SYNCHRONIZED,
    RECOVERY_REQUIRED
}

enum class ProductLearningTerminalStatus {
    GOVERNANCE_REJECTED,
    APPLIED,
    COMPLETION_COMPENSATED,
    REJECTED,
    PARTIAL_FAILURE
}

enum class ProductLearningRejection {
    ATTEMPT_IN_PROGRESS,
    TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
    CANDIDATE_MISSING_OR_MISMATCH,
    POLICY_MISSING_OR_MISMATCH,
    GOVERNANCE_FAILED,
    GOVERNANCE_LIMIT_REJECTED,
    GOVERNANCE_TARGET_REJECTED,
    MATERIALIZER_FAILED,
    MATERIALIZER_REJECTED,
    MATERIALIZER_LIMIT_REJECTED,
    ARTIFACT_ID_OR_TIME_FAILED,
    ARTIFACT_ID_COLLISION,
    DECISION_INSTALL_FAILED,
    APPLICATION_INSTALL_FAILED,
    MUTATION_PREPARE_FAILED,
    MUTATION_APPLY_REJECTED,
    COMPENSATION_FAILED,
    COORDINATOR_PARTIAL_FAILURE
}

sealed interface ProductLearningFollowUpResult {
    data class Applied(
        val semantic: ProductLearningSemanticStatus
    ) : ProductLearningFollowUpResult

    data object GovernanceRejected : ProductLearningFollowUpResult

    data class AlreadyProcessed(
        val status: ProductLearningTerminalStatus
    ) : ProductLearningFollowUpResult

    data class Rejected(
        val reason: ProductLearningRejection
    ) : ProductLearningFollowUpResult

    data object CompletionCompensated : ProductLearningFollowUpResult

    data object PartialFailure : ProductLearningFollowUpResult

    data object NotReady : ProductLearningFollowUpResult

    data object InternalFailure : ProductLearningFollowUpResult
}

internal fun interface ProductLearningFollowUpPort {
    fun process(
        reference: CognitiveLearningReference
    ): AndroidHeartProductionGovernedLearningProcessResult
}

/**
 * Thin explicit product adapter over Production Governed Learning Composition.
 *
 * This host owns no learning state, policy, governance, Authority, target, persistence,
 * semantic synchronization, retry queue or terminal registry.
 */
class ProductLearningFollowUpHost internal constructor(
    private val downstream: ProductLearningFollowUpPort
) {
    constructor(
        governed: AndroidHeartProductionGovernedLearningComposition
    ) : this(
        downstream = ProductLearningFollowUpPort { reference ->
            governed.process(reference)
        }
    )

    fun process(
        reference: ProductLearningFollowUpReference
    ): ProductLearningFollowUpResult =
        try {
            when (val result = downstream.process(reference.cognitive)) {
                is AndroidHeartProductionGovernedLearningProcessResult.Processed ->
                    mapProcessed(result.result)

                AndroidHeartProductionGovernedLearningProcessResult.NotReady ->
                    ProductLearningFollowUpResult.NotReady

                AndroidHeartProductionGovernedLearningProcessResult.Failed ->
                    ProductLearningFollowUpResult.InternalFailure
            }
        } catch (_: Exception) {
            ProductLearningFollowUpResult.InternalFailure
        }

    override fun toString(): String =
        "ProductLearningFollowUpHost(downstream=<redacted>)"

    private fun mapProcessed(
        result: AndroidHeartGovernedLearningResult
    ): ProductLearningFollowUpResult =
        when (val governed = result.governed) {
            is CognitiveGovernedLearningResult.Applied ->
                when (result.semanticSync) {
                    AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED ->
                        ProductLearningFollowUpResult.Applied(
                            ProductLearningSemanticStatus.SYNCHRONIZED
                        )

                    AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED ->
                        ProductLearningFollowUpResult.Applied(
                            ProductLearningSemanticStatus.RECOVERY_REQUIRED
                        )

                    AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE ->
                        ProductLearningFollowUpResult.InternalFailure
                }

            is CognitiveGovernedLearningResult.GovernanceRejected ->
                ProductLearningFollowUpResult.GovernanceRejected

            is CognitiveGovernedLearningResult.AlreadyProcessed ->
                ProductLearningFollowUpResult.AlreadyProcessed(
                    mapTerminal(governed.status)
                )

            is CognitiveGovernedLearningResult.Rejected ->
                ProductLearningFollowUpResult.Rejected(
                    mapRejection(governed.reason)
                )

            is CognitiveGovernedLearningResult.CompletionCompensated ->
                ProductLearningFollowUpResult.CompletionCompensated

            is CognitiveGovernedLearningResult.PartialFailure ->
                ProductLearningFollowUpResult.PartialFailure
        }

    private fun mapTerminal(
        status: CognitiveGovernedLearningTerminalStatus
    ): ProductLearningTerminalStatus =
        when (status) {
            CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED ->
                ProductLearningTerminalStatus.GOVERNANCE_REJECTED
            CognitiveGovernedLearningTerminalStatus.APPLIED ->
                ProductLearningTerminalStatus.APPLIED
            CognitiveGovernedLearningTerminalStatus.COMPLETION_COMPENSATED ->
                ProductLearningTerminalStatus.COMPLETION_COMPENSATED
            CognitiveGovernedLearningTerminalStatus.REJECTED ->
                ProductLearningTerminalStatus.REJECTED
            CognitiveGovernedLearningTerminalStatus.PARTIAL_FAILURE ->
                ProductLearningTerminalStatus.PARTIAL_FAILURE
        }

    private fun mapRejection(
        reason: CognitiveGovernedLearningFailure
    ): ProductLearningRejection =
        when (reason) {
            CognitiveGovernedLearningFailure.ATTEMPT_IN_PROGRESS ->
                ProductLearningRejection.ATTEMPT_IN_PROGRESS
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED ->
                ProductLearningRejection.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED
            CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH ->
                ProductLearningRejection.CANDIDATE_MISSING_OR_MISMATCH
            CognitiveGovernedLearningFailure.POLICY_MISSING_OR_MISMATCH ->
                ProductLearningRejection.POLICY_MISSING_OR_MISMATCH
            CognitiveGovernedLearningFailure.GOVERNANCE_FAILED ->
                ProductLearningRejection.GOVERNANCE_FAILED
            CognitiveGovernedLearningFailure.GOVERNANCE_LIMIT_REJECTED ->
                ProductLearningRejection.GOVERNANCE_LIMIT_REJECTED
            CognitiveGovernedLearningFailure.GOVERNANCE_TARGET_REJECTED ->
                ProductLearningRejection.GOVERNANCE_TARGET_REJECTED
            CognitiveGovernedLearningFailure.MATERIALIZER_FAILED ->
                ProductLearningRejection.MATERIALIZER_FAILED
            CognitiveGovernedLearningFailure.MATERIALIZER_REJECTED ->
                ProductLearningRejection.MATERIALIZER_REJECTED
            CognitiveGovernedLearningFailure.MATERIALIZER_LIMIT_REJECTED ->
                ProductLearningRejection.MATERIALIZER_LIMIT_REJECTED
            CognitiveGovernedLearningFailure.ARTIFACT_ID_OR_TIME_FAILED ->
                ProductLearningRejection.ARTIFACT_ID_OR_TIME_FAILED
            CognitiveGovernedLearningFailure.ARTIFACT_ID_COLLISION ->
                ProductLearningRejection.ARTIFACT_ID_COLLISION
            CognitiveGovernedLearningFailure.DECISION_INSTALL_FAILED ->
                ProductLearningRejection.DECISION_INSTALL_FAILED
            CognitiveGovernedLearningFailure.APPLICATION_INSTALL_FAILED ->
                ProductLearningRejection.APPLICATION_INSTALL_FAILED
            CognitiveGovernedLearningFailure.MUTATION_PREPARE_FAILED ->
                ProductLearningRejection.MUTATION_PREPARE_FAILED
            CognitiveGovernedLearningFailure.MUTATION_APPLY_REJECTED ->
                ProductLearningRejection.MUTATION_APPLY_REJECTED
            CognitiveGovernedLearningFailure.COMPENSATION_FAILED ->
                ProductLearningRejection.COMPENSATION_FAILED
            CognitiveGovernedLearningFailure.COORDINATOR_PARTIAL_FAILURE ->
                ProductLearningRejection.COORDINATOR_PARTIAL_FAILURE
        }
}
