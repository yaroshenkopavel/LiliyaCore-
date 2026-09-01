package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionSnapshot
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningInstallResult
import pro.liliya.core.learning.LearningOrigin
import pro.liliya.core.learning.LearningOwnership
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningProposalSnapshot
import pro.liliya.core.reasoning.ReasoningArtifactSnapshot
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition
import pro.liliya.core.reflection.ReflectionInstallResult
import pro.liliya.core.reflection.ReflectionOrigin
import pro.liliya.core.reflection.ReflectionOwnership
import pro.liliya.core.reflection.ReflectionRecord
import pro.liliya.core.reflection.ReflectionRecordId
import pro.liliya.core.reflection.ReflectionSourceId
import pro.liliya.core.reflection.ReflectionSourceReference

internal class CognitiveFinalizationCoordinator(
    private val turns: CognitiveTurnRegistry,
    private val scope: CognitiveRuntimeScopeId,
    private val outcomeMaterialization: CognitiveOutcomeMaterializationPort,
    private val planning: PlanningComposition,
    private val reasoning: ReasoningComposition,
    private val decision: DecisionComposition,
    private val reflection: ReflectionComposition,
    private val learning: LearningComposition,
    private val artifactIds: CognitiveArtifactIdSource,
    private val timestamps: CognitiveTimestampSource,
    private val limits: CognitiveRuntimeLimits
) {
    private data class ValidatedCognition(
        val receipt: AcceptedCognitionReceipt,
        val planning: PlanningProposalSnapshot,
        val reasoning: ReasoningArtifactSnapshot,
        val decision: DecisionSnapshot,
        val inference: CognitiveInferenceResult.Succeeded
    )

    private data class CompensationResult(val complete: Boolean)

    private val gate = Any()
    private var finalizationInProgress = false

    fun finalize(reference: CognitiveTurnReference): CognitiveFinalizationResult {
        synchronized(gate) {
            if (finalizationInProgress) {
                return CognitiveFinalizationResult.Rejected(
                    CognitiveFinalizationFailure.FINALIZATION_IN_PROGRESS
                )
            }
            finalizationInProgress = true
        }
        return try {
            finalizeInternal(reference)
        } finally {
            synchronized(gate) { finalizationInProgress = false }
        }
    }

    private fun finalizeInternal(reference: CognitiveTurnReference): CognitiveFinalizationResult {
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return CognitiveFinalizationResult.Stale
        }

        val validated = validateAcceptedCognition(reference)
            ?: return rejectCurrent(reference, CognitiveFinalizationFailure.ACCEPTED_COGNITION_MISMATCH)

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return CognitiveFinalizationResult.Stale
        }

        val materialized = try {
            outcomeMaterialization.materialize(
                CognitiveOutcomeMaterializationRequest(
                    turn = reference,
                    planning = validated.planning,
                    reasoning = validated.reasoning,
                    decision = validated.decision,
                    inferenceOutput = validated.inference.output,
                    budgets = CognitiveOutcomeBudgets.from(limits)
                )
            )
        } catch (_: Exception) {
            return rejectCurrent(reference, CognitiveFinalizationFailure.OUTCOME_MATERIALIZER_FAILED)
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return CognitiveFinalizationResult.Stale
        }

        val candidate = when (materialized) {
            is CognitiveOutcomeMaterializationResult.Rejected ->
                return rejectCurrent(reference, CognitiveFinalizationFailure.OUTCOME_MATERIALIZER_REJECTED)
            is CognitiveOutcomeMaterializationResult.Succeeded -> materialized.candidate
        }
        if (!candidateWithinLimits(candidate)) {
            return rejectCurrent(reference, CognitiveFinalizationFailure.OUTCOME_LIMIT_REJECTED)
        }

        val allocatedIds = mutableSetOf<String>()
        val prepared = try {
            val reflectionId = ReflectionRecordId(
                nextId(CognitiveArtifactIdKind.REFLECTION_RECORD, allocatedIds)
            )
            val learningId = LearningCandidateId(
                nextId(CognitiveArtifactIdKind.LEARNING_CANDIDATE, allocatedIds)
            )
            val resultCreatedAt = timestamps.now()
            val reflectionCreatedAt = timestamps.now()
            val learningCreatedAt = timestamps.now()
            PreparedArtifacts(
                reflectionId = reflectionId,
                learningId = learningId,
                result = CognitiveResult(
                    turn = reference,
                    planning = validated.receipt.planning,
                    reasoning = validated.receipt.reasoning,
                    decision = validated.receipt.decision,
                    content = candidate.resultContent,
                    createdAt = resultCreatedAt
                ),
                reflectionCreatedAt = reflectionCreatedAt,
                learningCreatedAt = learningCreatedAt
            )
        } catch (_: Exception) {
            return rejectCurrent(reference, CognitiveFinalizationFailure.ARTIFACT_ID_OR_TIME_FAILED)
        }

        if (reflection.contains(prepared.reflectionId) || learning.contains(prepared.learningId)) {
            return rejectCurrent(reference, CognitiveFinalizationFailure.ARTIFACT_ID_COLLISION)
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return CognitiveFinalizationResult.Stale
        }

        val resultProvenance = CognitiveProvenance.resultToken(
            scope = scope,
            reference = reference,
            decision = validated.receipt.decision
        ).value
        if (resultProvenance.length > limits.maxProvenanceReferenceChars) {
            return rejectCurrent(reference, CognitiveFinalizationFailure.OUTCOME_LIMIT_REJECTED)
        }

        val reflectionRecord = ReflectionRecord(
            id = prepared.reflectionId,
            origin = ReflectionOrigin.Declared(
                sourceId = ReflectionSourceId(COGNITIVE_RUNTIME_RESULT_SOURCE_ID),
                sourceReference = ReflectionSourceReference(resultProvenance)
            ),
            content = candidate.reflectionContent,
            createdAt = prepared.reflectionCreatedAt
        )
        val reflectionOwnership = when (val installed = reflection.install(reflectionRecord)) {
            is ReflectionInstallResult.Installed -> installed.ownership
            is ReflectionInstallResult.Rejected ->
                return rejectCurrent(reference, CognitiveFinalizationFailure.REFLECTION_INSTALL_FAILED)
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return compensateAndStale(reflection = reflectionOwnership)
        }
        if (!reflectionStillExact(reflectionOwnership)) {
            return compensateAndReject(
                reference = reference,
                failure = CognitiveFinalizationFailure.REFLECTION_MISMATCH,
                reflection = reflectionOwnership
            )
        }

        val learningCandidate = LearningCandidate(
            id = prepared.learningId,
            origin = LearningOrigin.Reflection(
                recordId = reflectionOwnership.record.id,
                generation = reflectionOwnership.generation
            ),
            proposal = candidate.learningProposal,
            createdAt = prepared.learningCreatedAt
        )
        val learningOwnership = when (val installed = learning.install(learningCandidate)) {
            is LearningInstallResult.Installed -> installed.ownership
            is LearningInstallResult.Rejected ->
                return compensateAndReject(
                    reference = reference,
                    failure = CognitiveFinalizationFailure.LEARNING_INSTALL_FAILED,
                    reflection = reflectionOwnership
                )
        }

        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
            return compensateAndStale(
                learning = learningOwnership,
                reflection = reflectionOwnership
            )
        }
        if (!reflectionStillExact(reflectionOwnership) || !learningStillExact(learningOwnership)) {
            return compensateAndReject(
                reference = reference,
                failure = CognitiveFinalizationFailure.LEARNING_MISMATCH,
                learning = learningOwnership,
                reflection = reflectionOwnership
            )
        }

        return when (turns.completeIfCurrent(reference)) {
            CognitiveTurnTransitionResult.Transitioned -> CognitiveFinalizationResult.Completed(
                result = prepared.result,
                reflection = CognitiveReflectionReference(
                    reflectionOwnership.record.id,
                    reflectionOwnership.generation
                ),
                learning = CognitiveLearningReference(
                    learningOwnership.candidate.id,
                    learningOwnership.generation
                )
            )

            CognitiveTurnTransitionResult.Stale -> compensateAndStale(
                learning = learningOwnership,
                reflection = reflectionOwnership
            )

            is CognitiveTurnTransitionResult.Failed -> compensateAndReject(
                reference = reference,
                failure = CognitiveFinalizationFailure.TERMINAL_COMPLETION_FAILED,
                learning = learningOwnership,
                reflection = reflectionOwnership
            )
        }
    }

    private data class PreparedArtifacts(
        val reflectionId: ReflectionRecordId,
        val learningId: LearningCandidateId,
        val result: CognitiveResult,
        val reflectionCreatedAt: java.time.Instant,
        val learningCreatedAt: java.time.Instant
    )

    private fun validateAcceptedCognition(reference: CognitiveTurnReference): ValidatedCognition? {
        val receipt = turns.acceptedCognitionIfCurrent(reference) ?: return null
        val inference = turns.inferenceIfCurrent(reference) ?: return null
        if (receipt.turn != reference || inference.turn != reference) return null

        val expectedSourceReference = CognitiveProvenance.turnToken(scope, reference).value

        val planningSnapshot = planning.inspect(receipt.planning.id) ?: return null
        if (planningSnapshot.generation != receipt.planning.generation) return null
        if (planningSnapshot.proposal.origin.sourceId.value != COGNITIVE_RUNTIME_SOURCE_ID) return null
        if (planningSnapshot.proposal.origin.sourceReference?.value != expectedSourceReference) return null

        val reasoningSnapshot = reasoning.inspect(receipt.reasoning.id) ?: return null
        if (reasoningSnapshot.generation != receipt.reasoning.generation) return null
        if (reasoningSnapshot.artifact.origin.sourceId.value != COGNITIVE_RUNTIME_SOURCE_ID) return null
        if (reasoningSnapshot.artifact.origin.sourceReference?.value != expectedSourceReference) return null

        val decisionSnapshot = decision.inspect(receipt.decision.id) ?: return null
        if (decisionSnapshot.generation != receipt.decision.generation) return null
        val expectedInputs = listOf(
            DecisionInputReference.Planning(receipt.planning.id, receipt.planning.generation),
            DecisionInputReference.Reasoning(receipt.reasoning.id, receipt.reasoning.generation)
        )
        if (decisionSnapshot.decision.inputs != expectedInputs) return null

        return ValidatedCognition(
            receipt = receipt,
            planning = planningSnapshot,
            reasoning = reasoningSnapshot,
            decision = decisionSnapshot,
            inference = inference
        )
    }

    private fun candidateWithinLimits(candidate: CognitiveOutcomeCandidate): Boolean =
        candidate.resultContent.length <= limits.maxResultChars &&
            candidate.reflectionContent.length <= limits.maxReflectionChars &&
            candidate.learningProposal.length <= limits.maxLearningProposalChars

    private fun reflectionStillExact(ownership: ReflectionOwnership): Boolean =
        reflection.inspect(ownership.record.id)?.let { snapshot ->
            snapshot.generation == ownership.generation && snapshot.record == ownership.record
        } == true

    private fun learningStillExact(ownership: LearningOwnership): Boolean =
        learning.inspect(ownership.candidate.id)?.let { snapshot ->
            snapshot.generation == ownership.generation && snapshot.candidate == ownership.candidate
        } == true

    private fun nextId(kind: CognitiveArtifactIdKind, allocatedIds: MutableSet<String>): String {
        val value = artifactIds.next(kind)
        require(value.isNotBlank()) { "cognitive artifact id source returned blank id" }
        require(allocatedIds.add(value)) { "cognitive artifact id source returned duplicate id in one attempt" }
        return value
    }

    private fun rejectCurrent(
        reference: CognitiveTurnReference,
        failure: CognitiveFinalizationFailure
    ): CognitiveFinalizationResult = when (
        turns.failIfCurrent(reference, CognitiveTurnFailure.TURN_FAILED)
    ) {
        CognitiveTurnTransitionResult.Stale -> CognitiveFinalizationResult.Stale
        is CognitiveTurnTransitionResult.Failed,
        CognitiveTurnTransitionResult.Transitioned -> CognitiveFinalizationResult.Rejected(failure)
    }

    private fun compensateAndReject(
        reference: CognitiveTurnReference,
        failure: CognitiveFinalizationFailure,
        learning: LearningOwnership? = null,
        reflection: ReflectionOwnership? = null
    ): CognitiveFinalizationResult {
        val compensation = compensate(learning, reflection)
        if (!compensation.complete) {
            if (turns.isCurrentAt(reference, CognitiveTurnLifecycle.COGNITION_READY)) {
                turns.failIfCurrent(reference, CognitiveTurnFailure.TURN_FAILED)
            }
            return CognitiveFinalizationResult.Rejected(
                CognitiveFinalizationFailure.COMPENSATION_FAILED
            )
        }
        return rejectCurrent(reference, failure)
    }

    private fun compensateAndStale(
        learning: LearningOwnership? = null,
        reflection: ReflectionOwnership? = null
    ): CognitiveFinalizationResult {
        val compensation = compensate(learning, reflection)
        return if (compensation.complete) {
            CognitiveFinalizationResult.Stale
        } else {
            CognitiveFinalizationResult.Rejected(
                CognitiveFinalizationFailure.COMPENSATION_FAILED
            )
        }
    }

    private fun compensate(
        learning: LearningOwnership?,
        reflection: ReflectionOwnership?
    ): CompensationResult {
        var complete = true
        if (learning != null && !safeRemove { learning.remove() }) complete = false
        if (reflection != null && !safeRemove { reflection.remove() }) complete = false
        return CompensationResult(complete)
    }

    private fun safeRemove(remove: () -> Boolean): Boolean = try {
        remove()
    } catch (_: Exception) {
        false
    }
}
