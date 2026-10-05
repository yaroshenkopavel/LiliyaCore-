package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSkillLaboratoryTest {
    @Test
    fun skill_candidate_runs_to_governance_eligibility_without_installation_state() {
        val lab = AgentSkillCandidateLab()
        val candidate = skill("candidate", "skill:parent:v1")
        lab.propose(candidate)

        assertEquals(
            AgentSkillCandidateValidation.ADMISSIBLE,
            lab.validate(candidate.id, "skill:parent:v1")
        )

        val evaluation = lab.recordEvaluation(
            candidate.id,
            baseline = vector(
                completed = true,
                inference = 100,
                unresolved = 1,
                workers = 1
            ),
            candidateVector = vector(
                completed = true,
                inference = 80,
                unresolved = 0,
                workers = 1
            ),
            evidenceReferences = listOf("eval:skill")
        )

        assertEquals(candidate.id, evaluation.candidateId)
        assertEquals(listOf("eval:skill"), evaluation.evidenceReferences)

        val eligible = lab.markEligibleForGovernance(candidate.id)
        assertEquals(
            AgentSkillCandidateState.ELIGIBLE_FOR_GOVERNANCE,
            eligible.state
        )
        assertTrue(
            AgentSkillCandidateState.entries.none {
                it.name == "ACTIVE" || it.name == "INSTALLED"
            }
        )
    }

    @Test
    fun parent_mismatch_rejects_candidate() {
        val lab = AgentSkillCandidateLab()
        val candidate = skill("wrong-parent", "skill:parent:v1")
        lab.propose(candidate)

        assertEquals(
            AgentSkillCandidateValidation.PARENT_MISMATCH,
            lab.validate(candidate.id, "skill:parent:v2")
        )
        assertEquals(
            AgentSkillCandidateState.REJECTED,
            lab.candidate(candidate.id)?.state
        )
    }

    @Test
    fun evaluation_without_evidence_fails_closed() {
        val lab = AgentSkillCandidateLab()
        val candidate = skill("no-evidence", null)
        lab.propose(candidate)
        lab.validate(candidate.id, null)

        assertFailsWith<IllegalArgumentException> {
            lab.recordEvaluation(
                candidate.id,
                vector(true, 100, 0, 1),
                vector(true, 90, 0, 1),
                emptyList()
            )
        }
        assertNull(lab.evaluation(candidate.id))
    }

    @Test
    fun mixed_tradeoffs_remain_explicit_for_council() {
        val baseline = vector(
            completed = true,
            inference = 100,
            unresolved = 0,
            workers = 1
        )
        val council = vector(
            completed = true,
            inference = 160,
            unresolved = 0,
            workers = 3
        ).copy(challengedFindings = 0)
        val evaluation = AgentCouncilEvaluation.create(
            AgentRootTaskId("root-eval"),
            baseline.copy(challengedFindings = 2),
            council,
            listOf("eval:council")
        )

        assertEquals(
            AgentLaboratoryMetricRelation.BETTER,
            evaluation.comparison.challengedFindings
        )
        assertEquals(
            AgentLaboratoryMetricRelation.WORSE,
            evaluation.comparison.inference
        )
        assertEquals(
            AgentLaboratoryMetricRelation.WORSE,
            evaluation.comparison.workerCount
        )
        assertTrue(evaluation.comparison.mixedTradeoffs)
    }

    @Test
    fun laboratory_contract_has_no_score_winner_truth_install_or_authority_fields() {
        val forbidden = listOf(
            "score", "winner", "truth", "authority", "permission",
            "credential", "secret", "token", "license", "executiongrant"
        )
        listOf(
            AgentLaboratoryEvaluationVector::class.java,
            AgentLaboratoryEvaluationComparison::class.java,
            AgentSkillCandidateEvaluation::class.java,
            AgentCouncilEvaluation::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }

        val methodNames = AgentSkillCandidateLab::class.java
            .declaredMethods
            .map { it.name.lowercase() }
        listOf("install", "activate", "enable", "register", "promote").forEach {
            forbiddenMethod -> assertTrue(methodNames.none { forbiddenMethod in it })
        }
    }

    private fun skill(
        seed: String,
        parent: String?
    ) = AgentSkillCandidate.create(
        parentSkillReference = parent,
        name = "skill-$seed",
        version = 1,
        inputKinds = listOf("input"),
        outputKinds = listOf("output"),
        provenanceReferences = listOf("source:$seed")
    )

    private fun vector(
        completed: Boolean,
        inference: Long,
        unresolved: Int,
        workers: Int
    ) = AgentLaboratoryEvaluationVector(
        completed = completed,
        challengedFindings = 0,
        unresolvedConflictFindings = unresolved,
        wallClockMillis = 100,
        inferenceUnits = inference,
        contextBytes = 100,
        artifactCount = 1,
        retryCount = 0,
        cancellationCount = 0,
        workerCount = workers
    )
}
