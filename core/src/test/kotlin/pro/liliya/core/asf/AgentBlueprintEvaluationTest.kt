package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentBlueprintEvaluationTest {
    @Test
    fun comparison_preserves_mixed_tradeoffs_instead_of_single_score() {
        val baseline = AgentBlueprintEvaluationVector(
            completed = true,
            challengedFindings = 1,
            unresolvedConflictFindings = 0,
            wallClockMillis = 1000,
            inferenceUnits = 100,
            contextBytes = 1000,
            artifactCount = 2,
            retryCount = 0
        )
        val candidate = AgentBlueprintEvaluationVector(
            completed = true,
            challengedFindings = 0,
            unresolvedConflictFindings = 1,
            wallClockMillis = 800,
            inferenceUnits = 120,
            contextBytes = 900,
            artifactCount = 2,
            retryCount = 0
        )

        val result = AgentBlueprintEvaluationComparator.compare(
            baseline,
            candidate
        )

        assertEquals(
            AgentBlueprintEvaluationRelation.BETTER,
            result.challengedFindings
        )
        assertEquals(
            AgentBlueprintEvaluationRelation.WORSE,
            result.unresolvedConflicts
        )
        assertEquals(
            AgentBlueprintEvaluationRelation.BETTER,
            result.wallClock
        )
        assertEquals(
            AgentBlueprintEvaluationRelation.WORSE,
            result.inference
        )
        assertTrue(result.mixedTradeoffs)
    }

    @Test
    fun completion_regression_is_explicit_even_if_candidate_is_cheaper() {
        val baseline = vector(completed = true, inference = 100)
        val candidate = vector(completed = false, inference = 10)

        val result = AgentBlueprintEvaluationComparator.compare(
            baseline,
            candidate
        )

        assertEquals(
            AgentBlueprintEvaluationRelation.WORSE,
            result.completion
        )
        assertEquals(
            AgentBlueprintEvaluationRelation.BETTER,
            result.inference
        )
        assertTrue(result.mixedTradeoffs)
    }

    @Test
    fun equal_vectors_remain_equal_without_hidden_ranking() {
        val baseline = vector(completed = true, inference = 100)
        val result = AgentBlueprintEvaluationComparator.compare(
            baseline,
            baseline
        )

        assertTrue(
            listOf(
                result.completion,
                result.challengedFindings,
                result.unresolvedConflicts,
                result.wallClock,
                result.inference,
                result.context,
                result.artifacts,
                result.retries
            ).all { it == AgentBlueprintEvaluationRelation.EQUAL }
        )
        assertTrue(!result.mixedTradeoffs)
    }

    @Test
    fun evaluation_contract_has_no_score_winner_truth_or_permission_fields() {
        val forbidden = listOf(
            "score", "winner", "truth", "authority", "permission",
            "credential", "secret", "token", "license", "execution"
        )
        listOf(
            AgentBlueprintEvaluationVector::class.java,
            AgentBlueprintEvaluationComparison::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    private fun vector(
        completed: Boolean,
        inference: Long
    ) = AgentBlueprintEvaluationVector(
        completed = completed,
        challengedFindings = 0,
        unresolvedConflictFindings = 0,
        wallClockMillis = 100,
        inferenceUnits = inference,
        contextBytes = 100,
        artifactCount = 1,
        retryCount = 0
    )
}
