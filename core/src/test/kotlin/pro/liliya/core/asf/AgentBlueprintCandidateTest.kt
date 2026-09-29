package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBlueprintCandidateTest {
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val narrowScope = AgentCognitiveScope.create(listOf("verification"))
    private val parent = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parent",
        "analysis",
        broadScope
    )
    private val parentBudget = AgentWorkBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 8,
        maxArtifacts = 4,
        maxDescendants = 2
    )

    @Test
    fun candidate_identity_is_deterministic_and_provenance_canonical() {
        val a = candidate(
            scope = narrowScope,
            provenance = listOf("evidence:b", "evidence:a")
        )
        val b = candidate(
            scope = narrowScope,
            provenance = listOf("evidence:a", "evidence:b")
        )

        assertEquals(a.id, b.id)
        assertEquals(listOf("evidence:a", "evidence:b"), a.provenanceReferences)
    }

    @Test
    fun candidate_identity_changes_when_blueprint_changes() {
        val a = candidate(scope = narrowScope, role = "specialist-a")
        val b = candidate(scope = narrowScope, role = "specialist-b")

        assertNotEquals(a.id, b.id)
    }

    @Test
    fun exact_parent_and_narrower_scope_are_admissible() {
        assertEquals(
            AgentBlueprintCandidateValidation.ADMISSIBLE,
            AgentBlueprintCandidateValidator.validate(
                candidate(scope = narrowScope),
                parent,
                parentBudget
            )
        )
    }

    @Test
    fun scope_widening_is_rejected() {
        val narrowerParent = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "narrow-parent",
            "verification",
            narrowScope
        )
        val proposed = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "wide-child",
            "analysis",
            broadScope
        )
        val candidate = AgentBlueprintCandidate.create(
            AgentBlueprintReference(narrowerParent.id, narrowerParent.version),
            proposed,
            parentBudget,
            listOf("evidence:a")
        )

        assertEquals(
            AgentBlueprintCandidateValidation.SCOPE_WIDENING,
            AgentBlueprintCandidateValidator.validate(
                candidate,
                narrowerParent,
                parentBudget
            )
        )
    }

    @Test
    fun budget_widening_is_rejected() {
        val c = candidate(
            scope = narrowScope,
            budget = parentBudget.copy(
                maxInferenceUnits = parentBudget.maxInferenceUnits + 1
            )
        )

        assertEquals(
            AgentBlueprintCandidateValidation.BUDGET_WIDENING,
            AgentBlueprintCandidateValidator.validate(c, parent, parentBudget)
        )
    }

    @Test
    fun parent_mismatch_is_rejected() {
        val other = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "other",
            "analysis",
            broadScope
        )

        assertEquals(
            AgentBlueprintCandidateValidation.PARENT_MISMATCH,
            AgentBlueprintCandidateValidator.validate(
                candidate(scope = narrowScope),
                other,
                parentBudget
            )
        )
    }

    @Test
    fun lifecycle_has_no_active_or_installed_state() {
        val names = AgentBlueprintCandidateState.entries.map { it.name }

        assertTrue("ACTIVE" !in names)
        assertTrue("INSTALLED" !in names)
        assertTrue("ENABLED" !in names)
    }

    @Test
    fun eligible_for_governance_is_terminal_inside_candidate_lab() {
        val eligible = candidate(scope = narrowScope)
            .transition(AgentBlueprintCandidateState.VALIDATING)
            .transition(AgentBlueprintCandidateState.EVALUATED)
            .transition(AgentBlueprintCandidateState.ELIGIBLE_FOR_GOVERNANCE)

        assertFailsWith<IllegalArgumentException> {
            eligible.transition(AgentBlueprintCandidateState.PROPOSED)
        }
    }

    @Test
    fun rejected_and_quarantined_candidates_are_terminal() {
        val rejected = candidate(scope = narrowScope)
            .transition(AgentBlueprintCandidateState.REJECTED)
        val quarantined = candidate(scope = narrowScope, role = "q")
            .transition(AgentBlueprintCandidateState.QUARANTINED)

        assertFailsWith<IllegalArgumentException> {
            rejected.transition(AgentBlueprintCandidateState.VALIDATING)
        }
        assertFailsWith<IllegalArgumentException> {
            quarantined.transition(AgentBlueprintCandidateState.VALIDATING)
        }
    }

    @Test
    fun candidate_repository_is_separate_and_rejects_duplicates() {
        val repo = AgentBlueprintCandidateRepository()
        val c = candidate(scope = narrowScope)

        repo.add(c)
        assertEquals(c, repo.get(c.id))
        assertFailsWith<IllegalArgumentException> { repo.add(c) }
        assertNull(repo.get(AgentBlueprintCandidateId("missing")))
    }

    @Test
    fun candidate_repository_exposes_no_install_activation_or_registry_mutation_api() {
        val methodNames = AgentBlueprintCandidateRepository::class.java
            .declaredMethods
            .map { it.name.lowercase() }

        listOf("install", "activate", "enable", "register", "promote").forEach { forbidden ->
            assertTrue(methodNames.none { forbidden in it })
        }

        val fields = AgentBlueprintCandidateRepository::class.java
            .declaredFields
            .map { it.type.name.lowercase() }

        assertTrue(fields.none { "agentblueprintregistry" in it })
    }

    @Test
    fun candidate_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentBlueprintCandidate::class.java,
            AgentBlueprintCandidateRepository::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it })
            }
        }
    }

    private fun candidate(
        scope: AgentCognitiveScope,
        role: String = "specialist",
        budget: AgentWorkBudget = parentBudget,
        provenance: List<String> = listOf("evidence:a")
    ): AgentBlueprintCandidate {
        val proposed = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            role,
            "candidate-objective",
            scope
        )
        return AgentBlueprintCandidate.create(
            AgentBlueprintReference(parent.id, parent.version),
            proposed,
            budget,
            provenance
        )
    }
}
