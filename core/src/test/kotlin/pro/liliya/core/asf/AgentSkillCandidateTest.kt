package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentSkillCandidateTest {
    @Test
    fun identity_is_deterministic_and_collections_are_canonical() {
        val a = AgentSkillCandidate.create(
            parentSkillReference = "skill:parent:v1",
            name = "fact-check",
            version = 1,
            inputKinds = listOf("claim", "evidence"),
            outputKinds = listOf("finding", "report"),
            provenanceReferences = listOf("src:b", "src:a")
        )
        val b = AgentSkillCandidate.create(
            parentSkillReference = "skill:parent:v1",
            name = "fact-check",
            version = 1,
            inputKinds = listOf("evidence", "claim"),
            outputKinds = listOf("report", "finding"),
            provenanceReferences = listOf("src:a", "src:b")
        )

        assertEquals(a.id, b.id)
        assertEquals(listOf("claim", "evidence"), a.inputKinds)
        assertEquals(listOf("finding", "report"), a.outputKinds)
        assertEquals(listOf("src:a", "src:b"), a.provenanceReferences)
    }

    @Test
    fun identity_changes_with_skill_contract() {
        val a = skill("a")
        val b = skill("b")
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun lifecycle_has_no_active_or_installed_state() {
        val names = AgentSkillCandidateState.entries.map { it.name }
        assertTrue("ACTIVE" !in names)
        assertTrue("INSTALLED" !in names)
        assertTrue("ENABLED" !in names)
    }

    @Test
    fun governance_eligibility_is_terminal_inside_lab_contract() {
        val candidate = skill("terminal")
            .transition(AgentSkillCandidateState.VALIDATING)
            .transition(AgentSkillCandidateState.EVALUATED)
            .transition(AgentSkillCandidateState.ELIGIBLE_FOR_GOVERNANCE)

        assertFailsWith<IllegalArgumentException> {
            candidate.transition(AgentSkillCandidateState.PROPOSED)
        }
    }

    @Test
    fun repository_rejects_duplicates_and_exposes_no_install_api() {
        val repo = AgentSkillCandidateRepository()
        val candidate = skill("repo")
        repo.add(candidate)
        assertEquals(candidate, repo.get(candidate.id))
        assertFailsWith<IllegalArgumentException> { repo.add(candidate) }

        val methodNames = AgentSkillCandidateRepository::class.java
            .declaredMethods
            .map { it.name.lowercase() }
        listOf("install", "activate", "enable", "register", "promote").forEach { forbidden ->
            assertTrue(methodNames.none { forbidden in it })
        }
    }

    @Test
    fun skill_candidate_contract_has_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentSkillCandidate::class.java,
            AgentSkillCandidateRepository::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    private fun skill(seed: String) = AgentSkillCandidate.create(
        parentSkillReference = null,
        name = "skill-$seed",
        version = 1,
        inputKinds = listOf("input"),
        outputKinds = listOf("output"),
        provenanceReferences = listOf("source:$seed")
    )
}
