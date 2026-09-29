package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentWorkerProfilesContractTest {
    private val nanoBudget = AgentWorkBudget(5_000, 2_000, 16_000, 4, 1, 0)
    private val microBudget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 1)
    private val fullBudget = AgentWorkBudget(120_000, 100_000, 1_000_000, 128, 32, 12)

    private val profiles = AgentWorkerProfileSet(
        listOf(
            AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
            AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false),
            AgentWorkerProfile(AgentWorkerClass.FULL, fullBudget, 1, true)
        )
    )

    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "worker",
        "bounded-cognition",
        AgentCognitiveScope.create(listOf("verification"))
    )

    @Test
    fun profile_set_requires_exact_monotonic_full_micro_nano_hierarchy() {
        assertEquals(AgentWorkerClass.NANO, profiles.profile(AgentWorkerClass.NANO).workerClass)
        assertEquals(AgentWorkerClass.MICRO, profiles.profile(AgentWorkerClass.MICRO).workerClass)
        assertEquals(AgentWorkerClass.FULL, profiles.profile(AgentWorkerClass.FULL).workerClass)

        assertFailsWith<IllegalArgumentException> {
            AgentWorkerProfileSet(
                listOf(
                    AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
                    AgentWorkerProfile(AgentWorkerClass.MICRO, microBudget, 1, false)
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentWorkerProfileSet(
                listOf(
                    AgentWorkerProfile(AgentWorkerClass.NANO, nanoBudget, 0, false),
                    AgentWorkerProfile(AgentWorkerClass.MICRO, fullBudget, 1, false),
                    AgentWorkerProfile(AgentWorkerClass.FULL, microBudget, 1, true)
                )
            )
        }
    }

    @Test
    fun nano_descendants_and_protected_tool_view_are_structurally_forbidden() {
        assertFailsWith<IllegalArgumentException> {
            AgentWorkerProfile(
                AgentWorkerClass.NANO,
                nanoBudget.copy(maxDescendants = 1),
                maxLogicalRoleRetries = 0,
                protectedToolViewAllowed = false
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentWorkerProfile(
                AgentWorkerClass.NANO,
                nanoBudget,
                maxLogicalRoleRetries = 0,
                protectedToolViewAllowed = true
            )
        }
    }

    @Test
    fun worker_admission_fails_closed_on_budget_retry_descendant_and_tool_widening() {
        val policy = AgentWorkerAdmissionPolicy(profiles)
        val runtime = AgentWorkerRuntimeDescriptor(
            runtimeId = "nano-validator-v1",
            kind = AgentWorkerRuntimeKind.DETERMINISTIC
        )

        assertIs<AgentWorkerAdmissionDecision.Admissible>(
            policy.evaluate(AgentWorkerClass.NANO, request(nanoBudget), runtime)
        )
        assertRejected(AgentWorkerAdmissionRejection.NANO_DESCENDANTS_FORBIDDEN) {
            policy.evaluate(
                AgentWorkerClass.NANO,
                request(nanoBudget.copy(maxDescendants = 1)),
                runtime
            )
        }
        assertRejected(AgentWorkerAdmissionRejection.PROFILE_BUDGET_EXCEEDED) {
            policy.evaluate(
                AgentWorkerClass.NANO,
                request(nanoBudget.copy(maxInferenceUnits = nanoBudget.maxInferenceUnits + 1)),
                runtime
            )
        }
        assertRejected(AgentWorkerAdmissionRejection.RETRY_LIMIT) {
            policy.evaluate(AgentWorkerClass.NANO, request(nanoBudget, attempt = 1), runtime)
        }
        assertRejected(AgentWorkerAdmissionRejection.PROTECTED_TOOL_VIEW_NOT_ALLOWED) {
            policy.evaluate(
                AgentWorkerClass.NANO,
                request(nanoBudget),
                runtime,
                protectedToolViewRequested = true
            )
        }
    }

    @Test
    fun routing_uses_smallest_sufficient_admitted_mechanism_and_never_downgrades_need() {
        assertIs<AgentWorkerRoutingDecision.DeterministicCheck>(
            AgentWorkerRouter.route(
                AgentWorkerRequirement.DETERMINISTIC_CHECK,
                emptySet()
            )
        )
        assertEquals(
            AgentWorkerClass.NANO,
            assertIs<AgentWorkerRoutingDecision.Worker>(
                AgentWorkerRouter.route(
                    AgentWorkerRequirement.ATOMIC_VALIDATION,
                    setOf(AgentWorkerClass.FULL, AgentWorkerClass.NANO, AgentWorkerClass.MICRO)
                )
            ).workerClass
        )
        assertEquals(
            AgentWorkerClass.MICRO,
            assertIs<AgentWorkerRoutingDecision.Worker>(
                AgentWorkerRouter.route(
                    AgentWorkerRequirement.NARROW_MULTI_STEP,
                    setOf(AgentWorkerClass.FULL, AgentWorkerClass.MICRO)
                )
            ).workerClass
        )
        assertIs<AgentWorkerRoutingDecision.Declined>(
            AgentWorkerRouter.route(
                AgentWorkerRequirement.BROAD_SPECIALIST,
                setOf(AgentWorkerClass.NANO, AgentWorkerClass.MICRO)
            )
        )
    }

    @Test
    fun atomic_routing_may_escalate_only_to_an_admitted_larger_worker() {
        assertEquals(
            AgentWorkerClass.MICRO,
            assertIs<AgentWorkerRoutingDecision.Worker>(
                AgentWorkerRouter.route(
                    AgentWorkerRequirement.ATOMIC_VALIDATION,
                    setOf(AgentWorkerClass.MICRO, AgentWorkerClass.FULL)
                )
            ).workerClass
        )
        assertIs<AgentWorkerRoutingDecision.Declined>(
            AgentWorkerRouter.route(
                AgentWorkerRequirement.NARROW_MULTI_STEP,
                setOf(AgentWorkerClass.NANO)
            )
        )
    }

    @Test
    fun runtime_descriptor_is_identity_only_and_contains_no_capability_or_authority_fields() {
        val descriptor = AgentWorkerRuntimeDescriptor(
            runtimeId = "qwen-worker-v1",
            kind = AgentWorkerRuntimeKind.LLM,
            modelId = "qwen3-1.7b"
        )
        assertEquals("qwen-worker-v1", descriptor.runtimeId)

        val forbidden = listOf(
            "authority", "permission", "capability", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        val names = AgentWorkerRuntimeDescriptor::class.java.declaredFields
            .map { it.name.lowercase() }
        forbidden.forEach { word ->
            assertTrue(names.none { word in it })
        }
    }

    @Test
    fun worker_contracts_do_not_carry_truth_authority_execution_or_secret_material() {
        val forbidden = listOf(
            "truth", "authority", "permission", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentWorkerProfile::class.java,
            AgentWorkerAdmissionDecision.Admissible::class.java,
            AgentWorkerRuntimeDescriptor::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it })
            }
        }
    }

    private fun request(
        budget: AgentWorkBudget,
        attempt: Int = 0
    ) = AgentSpawnRequest.create(
        AgentBlueprintReference(blueprint.id, blueprint.version),
        AgentSpawnProvenance(AgentRootTaskId("root-asf-h"), null, null, 0),
        blueprint.cognitiveScope,
        budget,
        logicalRoleAttempt = attempt
    )

    private fun assertRejected(
        expected: AgentWorkerAdmissionRejection,
        block: () -> AgentWorkerAdmissionDecision
    ) {
        assertEquals(expected, assertIs<AgentWorkerAdmissionDecision.Rejected>(block()).reason)
    }
}
