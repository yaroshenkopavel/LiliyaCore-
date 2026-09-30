package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTeamComposerContractTest {
    private val root = AgentRootTaskId("root-asf-i")
    private val policy = AgentTeamCompositionPolicyVersion("asf-i-policy-v1")
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val verifyScope = AgentCognitiveScope.create(listOf("verification"))

    private val nanoBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "nano-verifier",
        "atomic-validation",
        verifyScope
    )
    private val microBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "micro-analyst",
        "narrow-analysis",
        broadScope
    )
    private val fullBlueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "full-specialist",
        "broad-analysis",
        broadScope
    )

    private val nanoBudget = AgentWorkBudget(
        maxWallClockMillis = 1_000,
        maxInferenceUnits = 1_000,
        maxContextBytes = 4_000,
        maxRetrievalItems = 2,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val microBudget = AgentWorkBudget(
        maxWallClockMillis = 4_000,
        maxInferenceUnits = 4_000,
        maxContextBytes = 16_000,
        maxRetrievalItems = 8,
        maxArtifacts = 2,
        maxDescendants = 1
    )
    private val fullBudget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 16,
        maxArtifacts = 4,
        maxDescendants = 4
    )
    private val aggregate = AgentAggregateBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 32,
        maxArtifacts = 8,
        maxAgents = 4
    )

    @Test
    fun deterministic_task_uses_no_agent_team() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.deterministic(),
                candidates = emptyList()
            )
        )

        val fallback = assertIs<AgentTeamCompositionDecision.DeterministicFallback>(result)
        assertEquals(root, fallback.rootTaskId)
        assertEquals(policy, fallback.policyVersion)
        assertEquals(listOf("evidence:a"), fallback.inputReferences)
    }

    @Test
    fun atomic_task_selects_single_nano_worker() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(AgentWorkerRequirement.ATOMIC_VALIDATION)
                ),
                candidates = allCandidates()
            )
        )

        val plan = assertIs<AgentTeamCompositionDecision.Composed>(result).plan
        assertEquals(listOf(AgentWorkerClass.NANO), plan.selectedWorkerClasses)
        assertEquals(1, plan.coordinatorPlan.steps.size)
        assertEquals(AgentWorkerClass.NANO, plan.coordinatorPlan.steps.single().workerClass)
        assertNull(plan.coordinatorPlan.steps.single().parentStepId)
    }

    @Test
    fun broader_worker_is_used_when_it_is_smallest_available_sufficient_candidate() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.NARROW_MULTI_STEP
                    )
                ),
                candidates = listOf(microCandidate(), fullCandidate())
            )
        )

        val plan = assertIs<AgentTeamCompositionDecision.Composed>(result).plan
        assertEquals(listOf(AgentWorkerClass.MICRO), plan.selectedWorkerClasses)
        assertEquals(1, plan.coordinatorPlan.steps.size)
    }

    @Test
    fun broad_narrow_atomic_task_builds_root_first_minimal_team() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.NARROW_MULTI_STEP,
                        AgentWorkerRequirement.BROAD_SPECIALIST
                    )
                ),
                candidates = allCandidates()
            )
        )

        val plan = assertIs<AgentTeamCompositionDecision.Composed>(result).plan
        assertEquals(
            listOf(
                AgentWorkerClass.FULL,
                AgentWorkerClass.MICRO,
                AgentWorkerClass.NANO
            ),
            plan.selectedWorkerClasses
        )
        assertEquals(3, plan.coordinatorPlan.steps.size)
        val rootStep = plan.coordinatorPlan.steps.first()
        assertEquals(AgentWorkerClass.FULL, rootStep.workerClass)
        assertNull(rootStep.parentStepId)
        assertTrue(
            plan.coordinatorPlan.steps.drop(1).all {
                it.parentStepId == rootStep.id
            }
        )
        assertTrue(plan.coordinatorPlan.steps.all { it.inputReferences == listOf("evidence:a") })
    }

    @Test
    fun missing_sufficient_worker_fails_closed() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(AgentWorkerRequirement.BROAD_SPECIALIST)
                ),
                candidates = listOf(nanoCandidate(), microCandidate())
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.NO_SUITABLE_WORKER,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun aggregate_budget_that_cannot_cover_selected_ceiling_fails_closed() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.NARROW_MULTI_STEP,
                        AgentWorkerRequirement.BROAD_SPECIALIST
                    )
                ),
                candidates = allCandidates(),
                aggregateBudget = aggregate.copy(maxInferenceUnits = 12_000)
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.AGGREGATE_BUDGET_EXCEEDED,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun root_without_descendant_capacity_cannot_hide_a_child_team() {
        val fullNoChildren = fullCandidate(
            budget = fullBudget.copy(maxDescendants = 0)
        )
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.BROAD_SPECIALIST
                    )
                ),
                candidates = listOf(nanoCandidate(), fullNoChildren)
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.ROOT_DESCENDANT_BUDGET_EXCEEDED,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun root_scope_must_contain_every_child_scope() {
        val narrowRootScope = AgentCognitiveScope.create(listOf("analysis"))
        val full = fullCandidate(scope = narrowRootScope)
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.BROAD_SPECIALIST
                    )
                ),
                candidates = listOf(nanoCandidate(), full)
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.ROOT_SCOPE_TOO_NARROW,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun nano_candidate_cannot_have_children_even_before_composition() {
        assertFailsWith<IllegalArgumentException> {
            nanoCandidate(budget = nanoBudget.copy(maxDescendants = 1))
        }
    }

    @Test
    fun worker_shape_rejects_mixed_deterministic_requirement() {
        assertFailsWith<IllegalArgumentException> {
            AgentTeamTaskShape.workers(
                listOf(
                    AgentWorkerRequirement.DETERMINISTIC_CHECK,
                    AgentWorkerRequirement.ATOMIC_VALIDATION
                )
            )
        }
    }

    @Test
    fun capacity_cannot_escalate_atomic_work_to_larger_worker() {
        val constrained = AgentTeamCapacityEnvelope(
            allowedWorkerClasses = setOf(
                AgentWorkerClass.MICRO,
                AgentWorkerClass.FULL
            ),
            maxWorkers = 2
        )
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(AgentWorkerRequirement.ATOMIC_VALIDATION)
                ),
                candidates = allCandidates(),
                capacity = constrained
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.CAPACITY_RESTRICTED,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun capacity_team_size_limit_declines_instead_of_widening_budget() {
        val constrained = AgentTeamCapacityEnvelope(
            allowedWorkerClasses = AgentWorkerClass.entries.toSet(),
            maxWorkers = 1
        )
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.NARROW_MULTI_STEP,
                        AgentWorkerRequirement.BROAD_SPECIALIST
                    )
                ),
                candidates = allCandidates(),
                capacity = constrained
            )
        )

        assertEquals(
            AgentTeamCompositionRejection.CAPACITY_RESTRICTED,
            assertIs<AgentTeamCompositionDecision.Rejected>(result).reason
        )
    }

    @Test
    fun full_capacity_preserves_smallest_sufficient_selection() {
        val result = AgentTeamComposer.compose(
            request(
                shape = AgentTeamTaskShape.workers(
                    listOf(
                        AgentWorkerRequirement.ATOMIC_VALIDATION,
                        AgentWorkerRequirement.NARROW_MULTI_STEP
                    )
                ),
                candidates = allCandidates(),
                capacity = AgentTeamCapacityEnvelope.full(aggregate)
            )
        )

        val plan = assertIs<AgentTeamCompositionDecision.Composed>(result).plan
        assertEquals(
            listOf(AgentWorkerClass.MICRO, AgentWorkerClass.NANO),
            plan.selectedWorkerClasses
        )
    }

    @Test
    fun composition_identity_changes_with_aggregate_envelope_or_candidate_set() {
        val shape = AgentTeamTaskShape.workers(
            listOf(AgentWorkerRequirement.NARROW_MULTI_STEP)
        )
        val baseline = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(shape, allCandidates())
            )
        ).plan
        val changedEnvelope = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(
                    shape,
                    allCandidates(),
                    aggregateBudget = aggregate.copy(maxInferenceUnits = 19_999)
                )
            )
        ).plan
        val changedCandidates = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(
                    shape,
                    listOf(microCandidate(), fullCandidate())
                )
            )
        ).plan

        assertNotEquals(baseline.decisionId, changedEnvelope.decisionId)
        assertNotEquals(baseline.decisionId, changedCandidates.decisionId)
        assertEquals(baseline.selectedWorkerClasses, changedCandidates.selectedWorkerClasses)
    }

    @Test
    fun composition_identity_is_deterministic_and_policy_sensitive() {
        val shape = AgentTeamTaskShape.workers(
            listOf(
                AgentWorkerRequirement.ATOMIC_VALIDATION,
                AgentWorkerRequirement.BROAD_SPECIALIST
            )
        )
        val first = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(shape, allCandidates(), inputs = listOf("evidence:b", "evidence:a"))
            )
        ).plan
        val second = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(shape, allCandidates().reversed(), inputs = listOf("evidence:a", "evidence:b"))
            )
        ).plan
        val changedPolicy = assertIs<AgentTeamCompositionDecision.Composed>(
            AgentTeamComposer.compose(
                request(
                    shape,
                    allCandidates(),
                    inputs = listOf("evidence:a", "evidence:b"),
                    policyVersion = AgentTeamCompositionPolicyVersion("asf-i-policy-v2")
                )
            )
        ).plan

        assertEquals(first.decisionId, second.decisionId)
        assertNotEquals(first.decisionId, changedPolicy.decisionId)
        assertEquals(listOf("evidence:a", "evidence:b"), first.inputReferences)
    }

    @Test
    fun composition_contracts_carry_no_truth_authority_permission_or_secret_fields() {
        val forbidden = listOf(
            "truth", "authority", "permission", "executiongrant",
            "credential", "secret", "token", "license", "principal", "confidence"
        )

        listOf(
            AgentTeamCompositionRequest::class.java,
            AgentTeamTaskShape::class.java,
            AgentTeamWorkerCandidate::class.java,
            AgentTeamCompositionPlan::class.java,
            AgentTeamCompositionDecision.DeterministicFallback::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(
                    names.none { word in it },
                    type.simpleName + " contains forbidden field: " + word
                )
            }
        }
    }

    private fun request(
        shape: AgentTeamTaskShape,
        candidates: List<AgentTeamWorkerCandidate>,
        aggregateBudget: AgentAggregateBudget = aggregate,
        capacity: AgentTeamCapacityEnvelope = AgentTeamCapacityEnvelope.full(aggregateBudget),
        inputs: Collection<String> = listOf("evidence:a"),
        policyVersion: AgentTeamCompositionPolicyVersion = policy
    ) = AgentTeamCompositionRequest.create(
        rootTaskId = root,
        policyVersion = policyVersion,
        taskShape = shape,
        aggregateBudget = aggregateBudget,
        capacity = capacity,
        inputReferences = inputs,
        candidates = candidates
    )

    private fun allCandidates() =
        listOf(nanoCandidate(), microCandidate(), fullCandidate())

    private fun nanoCandidate(
        budget: AgentWorkBudget = nanoBudget
    ) = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.NANO,
        blueprint = AgentBlueprintReference(nanoBlueprint.id, nanoBlueprint.version),
        cognitiveScope = verifyScope,
        budget = budget,
        runtime = AgentWorkerRuntimeDescriptor(
            runtimeId = "nano-deterministic-v1",
            kind = AgentWorkerRuntimeKind.DETERMINISTIC
        )
    )

    private fun microCandidate(
        budget: AgentWorkBudget = microBudget
    ) = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.MICRO,
        blueprint = AgentBlueprintReference(microBlueprint.id, microBlueprint.version),
        cognitiveScope = broadScope,
        budget = budget,
        runtime = AgentWorkerRuntimeDescriptor(
            runtimeId = "micro-onnx-v1",
            kind = AgentWorkerRuntimeKind.ONNX,
            modelId = "micro-model"
        )
    )

    private fun fullCandidate(
        budget: AgentWorkBudget = fullBudget,
        scope: AgentCognitiveScope = broadScope
    ) = AgentTeamWorkerCandidate(
        workerClass = AgentWorkerClass.FULL,
        blueprint = AgentBlueprintReference(fullBlueprint.id, fullBlueprint.version),
        cognitiveScope = scope,
        budget = budget,
        runtime = AgentWorkerRuntimeDescriptor(
            runtimeId = "full-llm-v1",
            kind = AgentWorkerRuntimeKind.LLM,
            modelId = "qwen3-1.7b"
        )
    )
}
