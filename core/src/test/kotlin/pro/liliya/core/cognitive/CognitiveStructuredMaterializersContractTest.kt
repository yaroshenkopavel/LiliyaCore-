package pro.liliya.core.cognitive

import java.time.Instant
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionRecord
import pro.liliya.core.decision.DecisionSnapshot
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningOrigin
import pro.liliya.core.planning.PlanningProposal
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningProposalSnapshot
import pro.liliya.core.planning.PlanningSourceId
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId
import pro.liliya.core.reasoning.ReasoningArtifact
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningArtifactSnapshot
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CognitiveStructuredMaterializersContractTest {
    private val limits = CognitiveRuntimeLimits()
    private val responseBudgets = CognitiveStructuredResponseBudgets.from(limits)

    @Test
    fun generation_materializer_projects_exact_cognition_candidate() {
        val result = assertIs<CognitiveMaterializationResult.Succeeded>(
            StructuredCognitiveMaterializationPort(responseBudgets).materialize(
                CognitiveMaterializationRequest(
                    turn = turn(),
                    inferenceOutput = validEnvelope(),
                    budgets = CognitiveMaterializationBudgets.from(limits)
                )
            )
        )

        with(result.candidate) {
            assertEquals("goal", planningGoal)
            assertEquals(listOf("step one", "step two"), planningSteps)
            assertEquals(listOf("premise one", "premise two"), reasoningPremises)
            assertEquals("analysis", reasoningAnalysis)
            assertEquals("conclusion", reasoningConclusion)
            assertEquals(listOf("option one", "option two"), decisionOptions)
            assertEquals(1, selectedDecisionOptionIndex)
            assertEquals("rationale", decisionRationale)
        }
    }

    @Test
    fun generation_materializer_rechecks_narrower_request_budget() {
        val result = assertIs<CognitiveMaterializationResult.Rejected>(
            StructuredCognitiveMaterializationPort(responseBudgets).materialize(
                CognitiveMaterializationRequest(
                    turn = turn(),
                    inferenceOutput = validEnvelope(),
                    budgets = CognitiveMaterializationBudgets.from(limits).copy(
                        maxPlanningGoalChars = 3
                    )
                )
            )
        )
        assertEquals(CognitiveMaterializationFailure.RESOURCE_LIMIT_REJECTED, result.reason)
    }

    @Test
    fun malformed_response_maps_to_structural_generation_rejection() {
        val malformed = validEnvelope().replace("PLANNING_GOAL=goal", "UNKNOWN=private-goal")
        val result = assertIs<CognitiveMaterializationResult.Rejected>(
            StructuredCognitiveMaterializationPort(responseBudgets).materialize(
                CognitiveMaterializationRequest(
                    turn = turn(),
                    inferenceOutput = malformed,
                    budgets = CognitiveMaterializationBudgets.from(limits)
                )
            )
        )
        assertEquals(CognitiveMaterializationFailure.STRUCTURE_REJECTED, result.reason)
    }

    @Test
    fun outcome_materializer_requires_exact_authoritative_cognition_match() {
        val snapshots = snapshots()
        val result = assertIs<CognitiveOutcomeMaterializationResult.Succeeded>(
            StructuredCognitiveOutcomeMaterializationPort(responseBudgets).materialize(
                CognitiveOutcomeMaterializationRequest(
                    turn = turn(),
                    planning = snapshots.planning,
                    reasoning = snapshots.reasoning,
                    decision = snapshots.decision,
                    inferenceOutput = validEnvelope(),
                    budgets = CognitiveOutcomeBudgets.from(limits)
                )
            )
        )

        assertEquals("result", result.candidate.resultContent)
        assertEquals("reflection", result.candidate.reflectionContent)
        assertEquals("learning", result.candidate.learningProposal)
    }

    @Test
    fun outcome_materializer_fails_closed_on_planning_semantic_mismatch() {
        val snapshots = snapshots(planningGoal = "different goal")
        assertSemanticMismatch(snapshots)
    }

    @Test
    fun outcome_materializer_fails_closed_on_reasoning_semantic_mismatch() {
        val snapshots = snapshots(reasoningAnalysis = "different analysis")
        assertSemanticMismatch(snapshots)
    }

    @Test
    fun outcome_materializer_fails_closed_on_decision_semantic_mismatch() {
        val snapshots = snapshots(selectedIndex = 0)
        assertSemanticMismatch(snapshots)
    }

    @Test
    fun outcome_materializer_rechecks_narrower_outcome_budget() {
        val snapshots = snapshots()
        val result = assertIs<CognitiveOutcomeMaterializationResult.Rejected>(
            StructuredCognitiveOutcomeMaterializationPort(responseBudgets).materialize(
                CognitiveOutcomeMaterializationRequest(
                    turn = turn(),
                    planning = snapshots.planning,
                    reasoning = snapshots.reasoning,
                    decision = snapshots.decision,
                    inferenceOutput = validEnvelope(),
                    budgets = CognitiveOutcomeBudgets.from(limits).copy(maxResultChars = 3)
                )
            )
        )
        assertEquals(CognitiveOutcomeMaterializationFailure.RESOURCE_LIMIT_REJECTED, result.reason)
    }

    @Test
    fun same_output_is_deterministic_across_generation_and_outcome_materializers() {
        val output = validEnvelope()
        val generation = assertIs<CognitiveMaterializationResult.Succeeded>(
            StructuredCognitiveMaterializationPort(responseBudgets).materialize(
                CognitiveMaterializationRequest(
                    turn(), output, CognitiveMaterializationBudgets.from(limits)
                )
            )
        )
        val snapshots = snapshots()
        val outcome = assertIs<CognitiveOutcomeMaterializationResult.Succeeded>(
            StructuredCognitiveOutcomeMaterializationPort(responseBudgets).materialize(
                CognitiveOutcomeMaterializationRequest(
                    turn(), snapshots.planning, snapshots.reasoning, snapshots.decision,
                    output, CognitiveOutcomeBudgets.from(limits)
                )
            )
        )

        assertEquals("goal", generation.candidate.planningGoal)
        assertEquals("result", outcome.candidate.resultContent)
    }

    private fun assertSemanticMismatch(snapshots: Snapshots) {
        val result = assertIs<CognitiveOutcomeMaterializationResult.Rejected>(
            StructuredCognitiveOutcomeMaterializationPort(responseBudgets).materialize(
                CognitiveOutcomeMaterializationRequest(
                    turn = turn(),
                    planning = snapshots.planning,
                    reasoning = snapshots.reasoning,
                    decision = snapshots.decision,
                    inferenceOutput = validEnvelope(),
                    budgets = CognitiveOutcomeBudgets.from(limits)
                )
            )
        )
        assertEquals(CognitiveOutcomeMaterializationFailure.STRUCTURE_REJECTED, result.reason)
    }

    private fun turn() = CognitiveTurnReference(
        CognitiveTurnId("private-turn"),
        CognitiveTurnGeneration(1)
    )

    private data class Snapshots(
        val planning: PlanningProposalSnapshot,
        val reasoning: ReasoningArtifactSnapshot,
        val decision: DecisionSnapshot
    )

    private fun snapshots(
        planningGoal: String = "goal",
        reasoningAnalysis: String = "analysis",
        selectedIndex: Int = 1
    ): Snapshots {
        val createdAt = Instant.parse("2026-09-01T00:00:00Z")
        val planning = PlanningProposalSnapshot(
            proposal = PlanningProposal(
                id = PlanningProposalId("planning-id"),
                origin = PlanningOrigin(PlanningSourceId("test")),
                goal = planningGoal,
                steps = listOf(
                    PlanningStep(PlanningStepId("step-1"), "step one"),
                    PlanningStep(PlanningStepId("step-2"), "step two")
                ),
                createdAt = createdAt
            ),
            generation = PlanningGeneration(1)
        )
        val reasoning = ReasoningArtifactSnapshot(
            artifact = ReasoningArtifact(
                id = ReasoningArtifactId("reasoning-id"),
                origin = ReasoningOrigin(ReasoningSourceId("test")),
                premises = listOf(
                    ReasoningPremise(ReasoningPremiseId("premise-1"), "premise one"),
                    ReasoningPremise(ReasoningPremiseId("premise-2"), "premise two")
                ),
                analysis = reasoningAnalysis,
                conclusion = "conclusion",
                createdAt = createdAt
            ),
            generation = ReasoningGeneration(1)
        )
        val options = listOf(
            DecisionOption(DecisionOptionId("option-1"), "option one"),
            DecisionOption(DecisionOptionId("option-2"), "option two")
        )
        val decision = DecisionSnapshot(
            decision = DecisionRecord(
                id = DecisionId("decision-id"),
                inputs = listOf(
                    DecisionInputReference.Planning(planning.proposal.id, planning.generation),
                    DecisionInputReference.Reasoning(reasoning.artifact.id, reasoning.generation)
                ),
                options = options,
                selectedOptionId = options[selectedIndex].id,
                rationale = "rationale",
                createdAt = createdAt
            ),
            generation = DecisionGeneration(1)
        )
        return Snapshots(planning, reasoning, decision)
    }

    private fun validEnvelope(): String = listOf(
        "LILIYA_COGNITIVE_RESPONSE_V1",
        "PLANNING_GOAL=goal",
        "PLANNING_STEP_COUNT=2",
        "PLANNING_STEP=step one",
        "PLANNING_STEP=step two",
        "REASONING_PREMISE_COUNT=2",
        "REASONING_PREMISE=premise one",
        "REASONING_PREMISE=premise two",
        "REASONING_ANALYSIS=analysis",
        "REASONING_CONCLUSION=conclusion",
        "DECISION_OPTION_COUNT=2",
        "DECISION_OPTION=option one",
        "DECISION_OPTION=option two",
        "DECISION_SELECTED_INDEX=1",
        "DECISION_RATIONALE=rationale",
        "RESULT_CONTENT=result",
        "REFLECTION_CONTENT=reflection",
        "LEARNING_PROPOSAL=learning",
        "END"
    ).joinToString("\n")
}
