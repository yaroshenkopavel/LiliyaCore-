package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionInstallResult
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionOwnership
import pro.liliya.core.decision.DecisionRecord
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningInstallResult
import pro.liliya.core.planning.PlanningOrigin
import pro.liliya.core.planning.PlanningOwnership
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningSourceReference
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId
import pro.liliya.core.reasoning.ReasoningArtifact
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reasoning.ReasoningInstallResult
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningOwnership
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId
import pro.liliya.core.reasoning.ReasoningSourceReference

sealed interface CognitiveGenerationResult {
    data class Succeeded(
        val turn: CognitiveTurnReference,
        val planning: PlanningReference,
        val reasoning: ReasoningReference,
        val decision: DecisionReference
    ) : CognitiveGenerationResult

    data object Stale : CognitiveGenerationResult
    data class Rejected(val reason: CognitiveGenerationFailure) : CognitiveGenerationResult
}

data class PlanningReference(
    val id: PlanningProposalId,
    val generation: PlanningGeneration
)

data class ReasoningReference(
    val id: ReasoningArtifactId,
    val generation: ReasoningGeneration
)

data class DecisionReference(
    val id: DecisionId,
    val generation: DecisionGeneration
)

enum class CognitiveGenerationFailure {
    DEPENDENCIES_UNAVAILABLE,
    INFERENCE_PROVIDER_FAILED,
    INFERENCE_PROVIDER_REJECTED,
    FOREIGN_INFERENCE_RESULT,
    INFERENCE_OUTPUT_LIMIT_REJECTED,
    MATERIALIZER_FAILED,
    MATERIALIZER_REJECTED,
    CANDIDATE_REJECTED,
    ARTIFACT_ID_OR_TIME_FAILED,
    PLANNING_INSTALL_FAILED,
    REASONING_INSTALL_FAILED,
    DECISION_INPUT_MISMATCH,
    DECISION_INSTALL_FAILED,
    FINAL_PUBLICATION_FAILED,
    COMPENSATION_FAILED
}

internal class CognitiveGenerationCoordinator(
    private val turns: CognitiveTurnRegistry,
    private val scope: CognitiveRuntimeScopeId,
    private val inference: CognitiveInferencePort,
    private val materialization: CognitiveMaterializationPort,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decision: DecisionComposition,
    private val artifactIds: CognitiveArtifactIdSource,
    private val timestamps: CognitiveTimestampSource,
    private val limits: CognitiveRuntimeLimits
) {
    private data class CompensationResult(val complete: Boolean)

    fun generate(reference: CognitiveTurnReference): CognitiveGenerationResult {
        when (turns.beginGeneratingIfCurrent(reference)) {
            CognitiveTurnTransitionResult.Transitioned -> Unit
            CognitiveTurnTransitionResult.Stale -> return CognitiveGenerationResult.Stale
            is CognitiveTurnTransitionResult.Failed -> return CognitiveGenerationResult.Stale
        }

        val input = turns.inputIfCurrent(reference) ?: return CognitiveGenerationResult.Stale
        val context = turns.contextIfCurrent(reference)
            ?: return rejectCurrent(reference, CognitiveGenerationFailure.INFERENCE_PROVIDER_FAILED)
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return CognitiveGenerationResult.Stale
        }

        val inferenceResult = try {
            inference.infer(
                CognitiveInferenceRequest(
                    turn = reference,
                    input = input,
                    context = context,
                    maxOutputChars = limits.maxInferenceOutputChars
                )
            )
        } catch (_: Exception) {
            return rejectCurrent(reference, CognitiveGenerationFailure.INFERENCE_PROVIDER_FAILED)
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return CognitiveGenerationResult.Stale
        }
        if (inferenceResult.turn != reference) {
            return rejectCurrent(reference, CognitiveGenerationFailure.FOREIGN_INFERENCE_RESULT)
        }

        val succeededInference = when (inferenceResult) {
            is CognitiveInferenceResult.Rejected ->
                return rejectCurrent(reference, CognitiveGenerationFailure.INFERENCE_PROVIDER_REJECTED)
            is CognitiveInferenceResult.Succeeded -> inferenceResult
        }
        if (succeededInference.output.length > limits.maxInferenceOutputChars) {
            return rejectCurrent(reference, CognitiveGenerationFailure.INFERENCE_OUTPUT_LIMIT_REJECTED)
        }

        val candidateResult = try {
            materialization.materialize(
                CognitiveMaterializationRequest(
                    turn = reference,
                    inferenceOutput = succeededInference.output,
                    budgets = CognitiveMaterializationBudgets.from(limits)
                )
            )
        } catch (_: Exception) {
            return rejectCurrent(reference, CognitiveGenerationFailure.MATERIALIZER_FAILED)
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return CognitiveGenerationResult.Stale
        }

        val candidate = when (candidateResult) {
            is CognitiveMaterializationResult.Rejected ->
                return rejectCurrent(reference, CognitiveGenerationFailure.MATERIALIZER_REJECTED)
            is CognitiveMaterializationResult.Succeeded -> candidateResult.candidate
        }
        if (!candidateWithinLimits(candidate)) {
            return rejectCurrent(reference, CognitiveGenerationFailure.CANDIDATE_REJECTED)
        }

        val allocatedIds = mutableSetOf<String>()
        val planningProposal = try {
            buildPlanning(reference, candidate, allocatedIds)
        } catch (_: Exception) {
            return rejectCurrent(reference, CognitiveGenerationFailure.ARTIFACT_ID_OR_TIME_FAILED)
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return CognitiveGenerationResult.Stale
        }

        val planningOwnership = when (val installed = planning.install(planningProposal)) {
            is PlanningInstallResult.Installed -> installed.ownership
            is PlanningInstallResult.Rejected ->
                return rejectCurrent(reference, CognitiveGenerationFailure.PLANNING_INSTALL_FAILED)
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return compensateAndStale(planning = planningOwnership)
        }

        val reasoningArtifact = try {
            buildReasoning(reference, candidate, allocatedIds)
        } catch (_: Exception) {
            return compensateAndReject(
                reference = reference,
                failure = CognitiveGenerationFailure.ARTIFACT_ID_OR_TIME_FAILED,
                planning = planningOwnership
            )
        }

        val reasoningOwnership = when (val installed = reasoning.install(reasoningArtifact)) {
            is ReasoningInstallResult.Installed -> installed.ownership
            is ReasoningInstallResult.Rejected ->
                return compensateAndReject(
                    reference = reference,
                    failure = CognitiveGenerationFailure.REASONING_INSTALL_FAILED,
                    planning = planningOwnership
                )
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return compensateAndStale(
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )
        }

        if (!planningStillExact(planningOwnership) || !reasoningStillExact(reasoningOwnership)) {
            return compensateAndReject(
                reference = reference,
                failure = CognitiveGenerationFailure.DECISION_INPUT_MISMATCH,
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )
        }

        val decisionRecord = try {
            buildDecision(candidate, planningOwnership, reasoningOwnership, allocatedIds)
        } catch (_: Exception) {
            return compensateAndReject(
                reference = reference,
                failure = CognitiveGenerationFailure.ARTIFACT_ID_OR_TIME_FAILED,
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )
        }

        val decisionOwnership = when (val installed = decision.install(decisionRecord)) {
            is DecisionInstallResult.Installed -> installed.ownership
            is DecisionInstallResult.Rejected ->
                return compensateAndReject(
                    reference = reference,
                    failure = CognitiveGenerationFailure.DECISION_INSTALL_FAILED,
                    reasoning = reasoningOwnership,
                    planning = planningOwnership
                )
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            return compensateAndStale(
                decision = decisionOwnership,
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )
        }

        val planningReference = PlanningReference(planningOwnership.proposal.id, planningOwnership.generation)
        val reasoningReference = ReasoningReference(reasoningOwnership.artifact.id, reasoningOwnership.generation)
        val decisionReference = DecisionReference(decisionOwnership.decision.id, decisionOwnership.generation)
        val receipt = AcceptedCognitionReceipt(
            turn = reference,
            planning = planningReference,
            reasoning = reasoningReference,
            decision = decisionReference
        )

        return when (
            turns.publishAcceptedCognitionIfCurrent(
                reference = reference,
                inference = succeededInference,
                receipt = receipt
            )
        ) {
            CognitiveTurnPublicationResult.Published -> CognitiveGenerationResult.Succeeded(
                turn = reference,
                planning = planningReference,
                reasoning = reasoningReference,
                decision = decisionReference
            )

            CognitiveTurnPublicationResult.Stale -> compensateAndStale(
                decision = decisionOwnership,
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )

            is CognitiveTurnPublicationResult.Rejected,
            is CognitiveTurnPublicationResult.Failed -> compensateAndReject(
                reference = reference,
                failure = CognitiveGenerationFailure.FINAL_PUBLICATION_FAILED,
                decision = decisionOwnership,
                reasoning = reasoningOwnership,
                planning = planningOwnership
            )
        }
    }

    private fun buildPlanning(
        reference: CognitiveTurnReference,
        candidate: CognitiveMaterializationCandidate,
        allocatedIds: MutableSet<String>
    ): PlanningProposal = PlanningProposal(
        id = PlanningProposalId(nextId(CognitiveArtifactIdKind.PLANNING_PROPOSAL, allocatedIds)),
        origin = PlanningOrigin(
            sourceId = PlanningSourceId(COGNITIVE_RUNTIME_SOURCE_ID),
            sourceReference = PlanningSourceReference(turnSourceReference(reference))
        ),
        goal = candidate.planningGoal,
        steps = candidate.planningSteps.map { description ->
            PlanningStep(
                id = PlanningStepId(nextId(CognitiveArtifactIdKind.PLANNING_STEP, allocatedIds)),
                description = description
            )
        },
        createdAt = timestamps.now()
    )

    private fun buildReasoning(
        reference: CognitiveTurnReference,
        candidate: CognitiveMaterializationCandidate,
        allocatedIds: MutableSet<String>
    ): ReasoningArtifact = ReasoningArtifact(
        id = ReasoningArtifactId(nextId(CognitiveArtifactIdKind.REASONING_ARTIFACT, allocatedIds)),
        origin = ReasoningOrigin(
            sourceId = ReasoningSourceId(COGNITIVE_RUNTIME_SOURCE_ID),
            sourceReference = ReasoningSourceReference(turnSourceReference(reference))
        ),
        premises = candidate.reasoningPremises.map { statement ->
            ReasoningPremise(
                id = ReasoningPremiseId(nextId(CognitiveArtifactIdKind.REASONING_PREMISE, allocatedIds)),
                statement = statement
            )
        },
        analysis = candidate.reasoningAnalysis,
        conclusion = candidate.reasoningConclusion,
        createdAt = timestamps.now()
    )

    private fun buildDecision(
        candidate: CognitiveMaterializationCandidate,
        planningOwnership: PlanningOwnership,
        reasoningOwnership: ReasoningOwnership,
        allocatedIds: MutableSet<String>
    ): DecisionRecord {
        val optionIds = candidate.decisionOptions.map {
            DecisionOptionId(nextId(CognitiveArtifactIdKind.DECISION_OPTION, allocatedIds))
        }
        return DecisionRecord(
            id = DecisionId(nextId(CognitiveArtifactIdKind.DECISION, allocatedIds)),
            inputs = listOf(
                DecisionInputReference.Planning(
                    planningOwnership.proposal.id,
                    planningOwnership.generation
                ),
                DecisionInputReference.Reasoning(
                    reasoningOwnership.artifact.id,
                    reasoningOwnership.generation
                )
            ),
            options = candidate.decisionOptions.mapIndexed { index, description ->
                DecisionOption(optionIds[index], description)
            },
            selectedOptionId = optionIds[candidate.selectedDecisionOptionIndex],
            rationale = candidate.decisionRationale,
            createdAt = timestamps.now()
        )
    }

    private fun nextId(kind: CognitiveArtifactIdKind, allocatedIds: MutableSet<String>): String {
        val value = artifactIds.next(kind)
        require(value.isNotBlank()) { "cognitive artifact id source returned blank id" }
        require(allocatedIds.add(value)) { "cognitive artifact id source returned duplicate id in one attempt" }
        return value
    }

    private fun planningStillExact(ownership: PlanningOwnership): Boolean =
        planning.inspect(ownership.proposal.id)?.let { snapshot ->
            snapshot.generation == ownership.generation && snapshot.proposal == ownership.proposal
        } == true

    private fun reasoningStillExact(ownership: ReasoningOwnership): Boolean =
        reasoning.inspect(ownership.artifact.id)?.let { snapshot ->
            snapshot.generation == ownership.generation && snapshot.artifact == ownership.artifact
        } == true

    private fun candidateWithinLimits(candidate: CognitiveMaterializationCandidate): Boolean {
        if (candidate.planningGoal.isBlank() || candidate.planningGoal.length > limits.maxPlanningGoalChars) return false
        if (!boundedStrings(candidate.planningSteps, limits.maxPlanningSteps, limits.maxPlanningStepChars)) return false
        if (!boundedStrings(candidate.reasoningPremises, limits.maxReasoningPremises, limits.maxReasoningPremiseChars)) return false
        if (candidate.reasoningAnalysis.isBlank() || candidate.reasoningAnalysis.length > limits.maxReasoningAnalysisChars) return false
        if (candidate.reasoningConclusion.isBlank() || candidate.reasoningConclusion.length > limits.maxReasoningConclusionChars) return false
        if (!boundedStrings(candidate.decisionOptions, limits.maxDecisionOptions, limits.maxDecisionOptionChars)) return false
        if (candidate.selectedDecisionOptionIndex !in candidate.decisionOptions.indices) return false
        if (candidate.decisionRationale.isBlank() || candidate.decisionRationale.length > limits.maxDecisionRationaleChars) return false
        return true
    }

    private fun boundedStrings(values: List<String>, maxCount: Int, maxChars: Int): Boolean =
        values.isNotEmpty() &&
            values.size <= maxCount &&
            values.all { it.isNotBlank() && it.length <= maxChars }

    private fun rejectCurrent(
        reference: CognitiveTurnReference,
        failure: CognitiveGenerationFailure
    ): CognitiveGenerationResult = when (turns.failIfCurrent(reference, CognitiveTurnFailure.TURN_FAILED)) {
        CognitiveTurnTransitionResult.Stale -> CognitiveGenerationResult.Stale
        is CognitiveTurnTransitionResult.Failed,
        CognitiveTurnTransitionResult.Transitioned -> CognitiveGenerationResult.Rejected(failure)
    }

    private fun compensateAndReject(
        reference: CognitiveTurnReference,
        failure: CognitiveGenerationFailure,
        decision: DecisionOwnership? = null,
        reasoning: ReasoningOwnership? = null,
        planning: PlanningOwnership? = null
    ): CognitiveGenerationResult {
        val compensation = compensate(decision, reasoning, planning)
        val terminal = if (turns.isCurrentAt(reference, CognitiveTurnLifecycle.GENERATING)) {
            turns.failIfCurrent(reference, CognitiveTurnFailure.TURN_FAILED)
        } else {
            CognitiveTurnTransitionResult.Stale
        }
        if (!compensation.complete) {
            return CognitiveGenerationResult.Rejected(CognitiveGenerationFailure.COMPENSATION_FAILED)
        }
        return when (terminal) {
            CognitiveTurnTransitionResult.Stale -> CognitiveGenerationResult.Stale
            is CognitiveTurnTransitionResult.Failed,
            CognitiveTurnTransitionResult.Transitioned -> CognitiveGenerationResult.Rejected(failure)
        }
    }

    private fun compensateAndStale(
        decision: DecisionOwnership? = null,
        reasoning: ReasoningOwnership? = null,
        planning: PlanningOwnership? = null
    ): CognitiveGenerationResult {
        val compensation = compensate(decision, reasoning, planning)
        return if (compensation.complete) {
            CognitiveGenerationResult.Stale
        } else {
            CognitiveGenerationResult.Rejected(CognitiveGenerationFailure.COMPENSATION_FAILED)
        }
    }

    private fun compensate(
        decision: DecisionOwnership?,
        reasoning: ReasoningOwnership?,
        planning: PlanningOwnership?
    ): CompensationResult {
        var complete = true
        if (decision != null && !safeRemove { decision.remove() }) complete = false
        if (reasoning != null && !safeRemove { reasoning.remove() }) complete = false
        if (planning != null && !safeRemove { planning.remove() }) complete = false
        return CompensationResult(complete)
    }

    private fun safeRemove(remove: () -> Boolean): Boolean = try {
        remove()
    } catch (_: Exception) {
        false
    }

    private fun turnSourceReference(reference: CognitiveTurnReference): String {
        val token = CognitiveProvenance.turnToken(scope, reference).value
        require(token.length <= limits.maxProvenanceReferenceChars) {
            "cognitive provenance reference exceeds configured limit"
        }
        return token
    }

    private companion object {
        const val COGNITIVE_RUNTIME_SOURCE_ID = "cognitive-runtime"
    }
}
