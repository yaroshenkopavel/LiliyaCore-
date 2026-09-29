package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBlueprintCandidateLabTest {
    private val scope = AgentCognitiveScope.create(
        listOf("analysis", "verification")
    )
    private val parent = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parent",
        "analysis",
        scope
    )
    private val budget = AgentWorkBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 8,
        maxArtifacts = 4,
        maxDescendants = 2
    )

    @Test
    fun admissible_candidate_runs_through_lab_without_installation_state() {
        val lab = AgentBlueprintCandidateLab()
        val c = candidate()

        lab.propose(c)
        assertEquals(
            AgentBlueprintCandidateValidation.ADMISSIBLE,
            lab.validate(c.id, parent, budget)
        )

        val evaluation = lab.recordEvaluation(
            c.id,
            AgentBlueprintReference(parent.id, parent.version),
            baseline = vector(
                completed = true,
                inference = 100,
                unresolved = 1
            ),
            candidateVector = vector(
                completed = true,
                inference = 80,
                unresolved = 0
            )
        )

        assertEquals(c.id, evaluation.candidateId)
        assertEquals(
            AgentBlueprintCandidateState.EVALUATED,
            lab.candidate(c.id)?.state
        )

        val eligible = lab.markEligibleForGovernance(c.id)
        assertEquals(
            AgentBlueprintCandidateState.ELIGIBLE_FOR_GOVERNANCE,
            eligible.state
        )
        assertTrue(
            AgentBlueprintCandidateState.entries.none {
                it.name == "INSTALLED" || it.name == "ACTIVE"
            }
        )
    }

    @Test
    fun invalid_candidate_is_rejected_and_cannot_be_evaluated() {
        val lab = AgentBlueprintCandidateLab()
        val widened = candidate(
            candidateBudget = budget.copy(
                maxContextBytes = budget.maxContextBytes + 1
            )
        )
        lab.propose(widened)

        assertEquals(
            AgentBlueprintCandidateValidation.BUDGET_WIDENING,
            lab.validate(widened.id, parent, budget)
        )
        assertEquals(
            AgentBlueprintCandidateState.REJECTED,
            lab.candidate(widened.id)?.state
        )
        assertFailsWith<IllegalArgumentException> {
            lab.recordEvaluation(
                widened.id,
                AgentBlueprintReference(parent.id, parent.version),
                vector(true, 100, 0),
                vector(true, 90, 0)
            )
        }
    }

    @Test
    fun evaluation_requires_exact_parent_baseline() {
        val lab = AgentBlueprintCandidateLab()
        val c = candidate()
        lab.propose(c)
        lab.validate(c.id, parent, budget)

        val other = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "other",
            "analysis",
            scope
        )

        assertFailsWith<IllegalArgumentException> {
            lab.recordEvaluation(
                c.id,
                AgentBlueprintReference(other.id, other.version),
                vector(true, 100, 0),
                vector(true, 90, 0)
            )
        }
        assertNull(lab.evaluation(c.id))
    }

    @Test
    fun evaluation_is_single_record_and_cannot_be_silently_replaced() {
        val lab = AgentBlueprintCandidateLab()
        val c = candidate()
        lab.propose(c)
        lab.validate(c.id, parent, budget)
        lab.recordEvaluation(
            c.id,
            AgentBlueprintReference(parent.id, parent.version),
            vector(true, 100, 0),
            vector(true, 90, 0)
        )

        assertFailsWith<IllegalArgumentException> {
            lab.recordEvaluation(
                c.id,
                AgentBlueprintReference(parent.id, parent.version),
                vector(true, 100, 0),
                vector(true, 1, 0)
            )
        }
    }

    @Test
    fun eligible_candidate_still_has_no_registry_install_path() {
        val methodNames = AgentBlueprintCandidateLab::class.java
            .declaredMethods
            .map { it.name.lowercase() }

        listOf(
            "install", "register", "activate", "enable",
            "applytoproduction", "writeblueprintregistry"
        ).forEach { forbidden ->
            assertTrue(methodNames.none { forbidden in it })
        }

        val fieldTypes = AgentBlueprintCandidateLab::class.java
            .declaredFields
            .map { it.type.name.lowercase() }

        assertTrue(fieldTypes.none { "agentblueprintregistry" in it })
    }

    @Test
    fun quarantine_is_terminal_inside_lab() {
        val lab = AgentBlueprintCandidateLab()
        val c = candidate()
        lab.propose(c)

        val q = lab.quarantine(c.id)
        assertEquals(
            AgentBlueprintCandidateState.QUARANTINED,
            q.state
        )
        assertFailsWith<IllegalArgumentException> {
            lab.validate(c.id, parent, budget)
        }
    }

    private fun candidate(
        candidateBudget: AgentWorkBudget = budget
    ): AgentBlueprintCandidate {
        val proposed = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "candidate",
            "analysis",
            scope
        )
        return AgentBlueprintCandidate.create(
            AgentBlueprintReference(parent.id, parent.version),
            proposed,
            candidateBudget,
            listOf("evidence:candidate")
        )
    }

    private fun vector(
        completed: Boolean,
        inference: Long,
        unresolved: Int
    ) = AgentBlueprintEvaluationVector(
        completed = completed,
        challengedFindings = 0,
        unresolvedConflictFindings = unresolved,
        wallClockMillis = 100,
        inferenceUnits = inference,
        contextBytes = 100,
        artifactCount = 1,
        retryCount = 0
    )
}
