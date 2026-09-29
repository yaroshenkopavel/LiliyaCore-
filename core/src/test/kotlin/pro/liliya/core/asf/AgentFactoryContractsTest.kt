package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentFactoryContractsTest {
    private val now = Instant.parse("2026-09-29T00:00:00Z")
    private val globalScope = AgentCognitiveScope.create(listOf("research", "verification", "planning"))
    private val blueprint = AgentBlueprint.create(
        version = AgentBlueprintVersion(1),
        role = "researcher",
        objectiveClass = "evidence-analysis",
        cognitiveScope = AgentCognitiveScope.create(listOf("research", "verification"))
    )
    private val globalBudget = AgentWorkBudget(
        maxWallClockMillis = 120_000,
        maxInferenceUnits = 100_000,
        maxContextBytes = 1_000_000,
        maxRetrievalItems = 128,
        maxArtifacts = 32,
        maxDescendants = 12
    )
    private val childBudget = AgentWorkBudget(
        maxWallClockMillis = 30_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 16,
        maxArtifacts = 4,
        maxDescendants = 2
    )

    @Test
    fun blueprint_identity_is_deterministic_and_scope_order_independent() {
        val first = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "researcher",
            "evidence-analysis",
            AgentCognitiveScope.create(listOf("verification", "research"))
        )
        val replay = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "researcher",
            "evidence-analysis",
            AgentCognitiveScope.create(listOf("research", "verification"))
        )

        assertEquals(first, replay)
        assertEquals(first.id, replay.id)
        assertEquals(listOf("research", "verification"), first.cognitiveScope.domains)
        assertNotEquals(
            first.id,
            AgentBlueprint.create(
                AgentBlueprintVersion(2),
                "researcher",
                "evidence-analysis",
                first.cognitiveScope
            ).id
        )
    }

    @Test
    fun spawn_request_identity_is_content_bound_and_deterministic() {
        val first = request()
        val replay = request()
        assertEquals(first, replay)
        assertEquals(first.id, replay.id)

        val changed = request(
            budget = childBudget.copy(maxInferenceUnits = childBudget.maxInferenceUnits + 1)
        )
        assertNotEquals(first.id, changed.id)
    }

    @Test
    fun root_and_child_provenance_fail_closed_on_malformed_parentage() {
        assertFailsWith<IllegalArgumentException> {
            AgentSpawnProvenance(
                AgentRootTaskId("root-1"),
                AgentInstanceId("parent"),
                AgentInstanceGeneration(1),
                depth = 0
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentSpawnProvenance(
                AgentRootTaskId("root-1"),
                parentAgentId = null,
                parentGeneration = null,
                depth = 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentSpawnProvenance(
                AgentRootTaskId("root-1"),
                AgentInstanceId("parent"),
                parentGeneration = null,
                depth = 1
            )
        }
    }

    @Test
    fun duplicate_scope_and_artifact_provenance_fail_closed() {
        assertFailsWith<IllegalArgumentException> {
            AgentCognitiveScope.create(listOf("research", "research"))
        }

        val spawn = request()
        val admission = AgentAdmission.create(
            spawn.id,
            AgentInstanceGeneration(1),
            spawn.cognitiveScope,
            spawn.budget,
            now
        )
        assertFailsWith<IllegalArgumentException> {
            AgentArtifact.create(
                producerId = admission.instanceId(),
                producerGeneration = admission.generation,
                rootTaskId = AgentRootTaskId("root-1"),
                kind = "research-report",
                payloadDigest = "sha256:abc",
                provenanceReferences = listOf("evidence:1", "evidence:1"),
                createdAt = now
            )
        }
    }

    @Test
    fun admission_requires_exact_blueprint_and_fails_closed_for_unknown_blueprint() {
        val policy = policy()
        val population = AgentPopulationSnapshot(0, 0, 0)

        assertIs<AgentAdmissionDecision.Admissible>(
            policy.evaluate(request(), blueprint, population)
        )
        assertEquals(
            AgentAdmissionRejection.UNKNOWN_BLUEPRINT,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(request(), null, population)
            ).reason
        )

        val other = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "critic",
            "evidence-analysis",
            blueprint.cognitiveScope
        )
        assertEquals(
            AgentAdmissionRejection.BLUEPRINT_VERSION_MISMATCH,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(request(), other, population)
            ).reason
        )
    }

    @Test
    fun child_admission_requires_parent_scope_and_remaining_budget_context() {
        val policy = policy()
        val child = childRequest(depth = 1)
        val population = AgentPopulationSnapshot(0, 0, 0)

        assertEquals(
            AgentAdmissionRejection.PARENT_CONTEXT_REQUIRED,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(child, blueprint, population)
            ).reason
        )
        assertEquals(
            AgentAdmissionRejection.PARENT_CONTEXT_REQUIRED,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(
                    child,
                    blueprint,
                    population,
                    parentScope = blueprint.cognitiveScope
                )
            ).reason
        )
    }

    @Test
    fun child_scope_and_budget_cannot_widen_parent_or_global_policy() {
        val policy = policy()
        val population = AgentPopulationSnapshot(0, 0, 0)

        val widerScope = AgentCognitiveScope.create(listOf("research", "verification"))
        val narrowParent = AgentCognitiveScope.create(listOf("research"))
        assertEquals(
            AgentAdmissionRejection.SCOPE_EXCEEDS_PARENT,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(
                    request(scope = widerScope),
                    blueprint,
                    population,
                    parentScope = narrowParent,
                    parentRemainingBudget = globalBudget
                )
            ).reason
        )

        val tooLarge = childBudget.copy(maxInferenceUnits = globalBudget.maxInferenceUnits + 1)
        assertEquals(
            AgentAdmissionRejection.BUDGET_EXCEEDS_GLOBAL,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(request(budget = tooLarge), blueprint, population)
            ).reason
        )

        val parentRemaining = childBudget.copy(maxInferenceUnits = childBudget.maxInferenceUnits - 1)
        assertEquals(
            AgentAdmissionRejection.BUDGET_EXCEEDS_PARENT,
            assertIs<AgentAdmissionDecision.Rejected>(
                policy.evaluate(
                    request(),
                    blueprint,
                    population,
                    parentScope = blueprint.cognitiveScope,
                    parentRemainingBudget = parentRemaining
                )
            ).reason
        )
    }

    @Test
    fun hard_population_depth_fanout_and_retry_bounds_are_enforced() {
        val policy = policy()

        assertRejected(AgentAdmissionRejection.ACTIVE_POPULATION_LIMIT) {
            policy.evaluate(request(), blueprint, AgentPopulationSnapshot(8, 0, 0))
        }
        assertRejected(AgentAdmissionRejection.ROOT_POPULATION_LIMIT) {
            policy.evaluate(request(), blueprint, AgentPopulationSnapshot(0, 12, 0))
        }
        assertRejected(AgentAdmissionRejection.SPAWN_DEPTH_LIMIT) {
            policy.evaluate(
                childRequest(depth = 4),
                blueprint,
                AgentPopulationSnapshot(0, 0, 0),
                parentScope = blueprint.cognitiveScope,
                parentRemainingBudget = globalBudget
            )
        }
        assertRejected(AgentAdmissionRejection.DIRECT_CHILD_LIMIT) {
            policy.evaluate(
                childRequest(depth = 1),
                blueprint,
                AgentPopulationSnapshot(0, 0, 4),
                parentScope = blueprint.cognitiveScope,
                parentRemainingBudget = globalBudget
            )
        }
        assertRejected(AgentAdmissionRejection.RETRY_LIMIT) {
            policy.evaluate(request(attempt = 2), blueprint, AgentPopulationSnapshot(0, 0, 0))
        }
    }

    @Test
    fun instance_can_only_be_created_from_admission_and_terminal_state_never_restarts() {
        val spawn = request()
        val admission = AgentAdmission.create(
            requestId = spawn.id,
            generation = AgentInstanceGeneration(1),
            admittedScope = spawn.cognitiveScope,
            admittedBudget = spawn.budget,
            admittedAt = now
        )

        val admitted = AgentInstance.fromAdmission(admission, spawn)
        assertEquals(admission.instanceId(), admitted.id)
        assertEquals(AgentLifecycleState.ADMITTED, admitted.lifecycle)

        val completed = admitted
            .transition(AgentLifecycleState.SPAWNED)
            .transition(AgentLifecycleState.RUNNING)
            .transition(AgentLifecycleState.COMPLETED)

        assertTrue(completed.lifecycle.terminal)
        assertFailsWith<IllegalArgumentException> {
            completed.transition(AgentLifecycleState.RUNNING)
        }
    }

    @Test
    fun instance_rejects_an_admission_bound_to_a_different_request() {
        val original = request()
        val admission = AgentAdmission.create(
            original.id,
            AgentInstanceGeneration(1),
            original.cognitiveScope,
            original.budget,
            now
        )
        val different = request(
            budget = original.budget.copy(maxInferenceUnits = original.budget.maxInferenceUnits - 1)
        )

        assertFailsWith<IllegalArgumentException> {
            AgentInstance.fromAdmission(admission, different)
        }
    }

    @Test
    fun recovery_never_restores_a_live_agent_without_fresh_admission() {
        assertEquals(
            AgentRecoveryDisposition.NEW_ADMISSION_REQUIRED,
            AgentLifecycleState.RUNNING.recoveryDisposition()
        )
        assertEquals(
            AgentRecoveryDisposition.NEW_ADMISSION_REQUIRED,
            AgentLifecycleState.SPAWNED.recoveryDisposition()
        )
        assertEquals(
            AgentRecoveryDisposition.TERMINAL_RETAINED,
            AgentLifecycleState.COMPLETED.recoveryDisposition()
        )
        assertEquals(
            AgentRecoveryDisposition.TERMINAL_RETAINED,
            AgentLifecycleState.FAILED.recoveryDisposition()
        )
    }

    @Test
    fun admission_and_instance_identity_change_with_generation() {
        val spawn = request()
        val first = AgentAdmission.create(
            spawn.id, AgentInstanceGeneration(1), spawn.cognitiveScope, spawn.budget, now
        )
        val second = AgentAdmission.create(
            spawn.id, AgentInstanceGeneration(2), spawn.cognitiveScope, spawn.budget, now
        )

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.instanceId(), second.instanceId())
    }

    @Test
    fun artifact_is_non_authoritative_provenance_bearing_and_canonical() {
        val admission = AgentAdmission.create(
            request().id,
            AgentInstanceGeneration(1),
            request().cognitiveScope,
            request().budget,
            now
        )
        val artifact = AgentArtifact.create(
            producerId = admission.instanceId(),
            producerGeneration = admission.generation,
            rootTaskId = AgentRootTaskId("root-1"),
            kind = "research-report",
            payloadDigest = "sha256:abc",
            provenanceReferences = listOf("evidence:z", "evidence:a"),
            createdAt = now
        )

        assertEquals(listOf("evidence:a", "evidence:z"), artifact.provenanceReferences)
        assertFailsWith<IllegalArgumentException> {
            AgentArtifact.create(
                admission.instanceId(),
                admission.generation,
                AgentRootTaskId("root-1"),
                "research-report",
                "sha256:abc",
                emptyList(),
                now
            )
        }
    }

    @Test
    fun durable_contracts_contain_no_authority_permission_or_secret_material() {
        val classes = listOf(
            AgentBlueprint::class.java,
            AgentSpawnRequest::class.java,
            AgentAdmission::class.java,
            AgentInstance::class.java,
            AgentArtifact::class.java,
            AgentWorkBudget::class.java
        )
        val forbidden = listOf(
            "authority", "executiongrant", "permission", "credential",
            "secret", "token", "principal", "license"
        )

        classes.forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it }, "${type.simpleName} contains forbidden field material: ${word}")
            }
        }
    }

    @Test
    fun prototype_bounds_match_accepted_asf_a_contract() {
        assertEquals(
            AgentFactoryBounds(
                maxActiveAgents = 8,
                maxAgentsPerRootTask = 12,
                maxSpawnDepth = 3,
                maxDirectChildren = 4,
                maxRetryPerLogicalRole = 1
            ),
            AgentFactoryBounds.PROTOTYPE
        )
    }

    private fun policy() = AgentAdmissionPolicy(
        AgentFactoryBounds.PROTOTYPE,
        globalScope,
        globalBudget
    )

    private fun request(
        scope: AgentCognitiveScope = AgentCognitiveScope.create(listOf("research")),
        budget: AgentWorkBudget = childBudget,
        attempt: Int = 0
    ) = AgentSpawnRequest.create(
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        provenance = AgentSpawnProvenance(
            rootTaskId = AgentRootTaskId("root-1"),
            parentAgentId = null,
            parentGeneration = null,
            depth = 0
        ),
        cognitiveScope = scope,
        budget = budget,
        logicalRoleAttempt = attempt
    )

    private fun childRequest(depth: Int) = AgentSpawnRequest.create(
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        provenance = AgentSpawnProvenance(
            rootTaskId = AgentRootTaskId("root-1"),
            parentAgentId = AgentInstanceId("parent-1"),
            parentGeneration = AgentInstanceGeneration(1),
            depth = depth
        ),
        cognitiveScope = AgentCognitiveScope.create(listOf("research")),
        budget = childBudget
    )

    private fun assertRejected(
        expected: AgentAdmissionRejection,
        block: () -> AgentAdmissionDecision
    ) {
        assertEquals(expected, assertIs<AgentAdmissionDecision.Rejected>(block()).reason)
    }
}
