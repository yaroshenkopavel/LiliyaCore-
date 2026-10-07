package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentParallelAdmissionReservationContractTest {
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val narrowScope = AgentCognitiveScope.create(listOf("verification"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "parallel-admission",
        "bounded-admission",
        broadScope
    )

    private val rootBudget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 16,
        maxArtifacts = 4,
        maxDescendants = 4
    )
    private val childBudget = AgentWorkBudget(
        maxWallClockMillis = 2_000,
        maxInferenceUnits = 2_000,
        maxContextBytes = 8_000,
        maxRetrievalItems = 2,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val bounds = AgentFactoryBounds.PROTOTYPE

    @Test
    fun bounded_fanout_reserves_root_population_and_hierarchy_before_launch() {
        val ready = assertIs<AgentParallelAdmissionReservationResult.Ready>(
            AgentParallelAdmissionReservationPlanner.reserve(
                plan = fanoutPlan(2),
                bounds = bounds
            )
        ).reservation

        assertEquals(3, ready.projectedAgentsForRootTask)
        assertEquals(2, ready.maxConcurrentWaveAgents)
        assertEquals(
            listOf(
                AgentParallelParentReservation(
                    parentStepId = AgentCoordinatorStepId("root"),
                    directChildren = 2,
                    plannedDescendants = 2,
                    reservedDescendantCapacity = 2
                )
            ),
            ready.parents
        )
    }

    @Test
    fun concurrent_wave_is_rejected_when_baseline_plus_siblings_exceeds_active_cap() {
        val rejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                plan = fanoutPlan(2),
                bounds = bounds,
                baselineActiveAgents = bounds.maxActiveAgents - 1
            )
        )

        assertEquals(
            AgentParallelAdmissionRejection.ACTIVE_POPULATION_LIMIT,
            rejected.reason
        )
    }

    @Test
    fun fifth_direct_child_is_rejected_before_any_parallel_admission() {
        val rejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                plan = fanoutPlan(5, rootBudget.copy(maxDescendants = 8)),
                bounds = bounds
            )
        )

        assertEquals(
            AgentParallelAdmissionRejection.DIRECT_CHILD_LIMIT,
            rejected.reason
        )
    }

    @Test
    fun ancestor_descendant_capacity_is_reserved_across_multiple_depths() {
        val constrainedRoot = rootBudget.copy(maxDescendants = 1)
        val root = step("root", null, constrainedRoot, broadScope)
        val child = step(
            "child",
            "root",
            childBudget.copy(maxDescendants = 1),
            narrowScope
        )
        val grandchild = step(
            "grandchild",
            "child",
            childBudget,
            narrowScope
        )

        val rejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("reservation-root"),
                    listOf(root, child, grandchild)
                ),
                bounds
            )
        )

        assertEquals(
            AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED,
            rejected.reason
        )
    }

    @Test
    fun sibling_declared_subtree_capacity_is_reserved_before_concurrent_admission() {
        val constrainedRoot = rootBudget.copy(maxDescendants = 2)
        val root = step("root", null, constrainedRoot, broadScope)
        val childWithFutureCapacity = step(
            "child-a",
            "root",
            childBudget.copy(maxDescendants = 1),
            narrowScope
        )
        val sibling = step(
            "child-b",
            "root",
            childBudget,
            narrowScope
        )

        val rejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("reserved-subtree-root"),
                    listOf(root, childWithFutureCapacity, sibling)
                ),
                bounds
            )
        )

        assertEquals(
            AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED,
            rejected.reason
        )
    }

    @Test
    fun child_scope_or_budget_widening_fails_preflight() {
        val narrowParent = step(
            "root",
            null,
            rootBudget,
            narrowScope
        )
        val widerChild = step(
            "child",
            "root",
            childBudget,
            broadScope
        )

        val scopeRejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("scope-root"),
                    listOf(narrowParent, widerChild)
                ),
                bounds
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.CHILD_SCOPE_EXCEEDS_PARENT,
            scopeRejected.reason
        )

        val constrainedParent = step(
            "root",
            null,
            rootBudget.copy(maxInferenceUnits = 1_000),
            broadScope
        )
        val expensiveChild = step(
            "child",
            "root",
            childBudget.copy(maxInferenceUnits = 2_000),
            narrowScope
        )

        val budgetRejected = assertIs<AgentParallelAdmissionReservationResult.Rejected>(
            AgentParallelAdmissionReservationPlanner.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("budget-root"),
                    listOf(constrainedParent, expensiveChild)
                ),
                bounds
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.CHILD_BUDGET_EXCEEDS_PARENT,
            budgetRejected.reason
        )
    }

    private fun fanoutPlan(
        childCount: Int,
        root: AgentWorkBudget = rootBudget
    ): AgentCoordinatorPlan =
        AgentCoordinatorPlan(
            rootTaskId = AgentRootTaskId("reservation-root"),
            steps = buildList {
                add(step("root", null, root, broadScope))
                (1..childCount).forEach { index ->
                    add(
                        step(
                            "child-$index",
                            "root",
                            childBudget,
                            narrowScope
                        )
                    )
                }
            }
        )

    private fun step(
        id: String,
        parent: String?,
        budget: AgentWorkBudget,
        scope: AgentCognitiveScope
    ): AgentCoordinatorStep =
        AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId(id),
            parentStepId = parent?.let(::AgentCoordinatorStepId),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = scope,
            budget = budget,
            inputReferences = listOf("evidence:$id")
        )
}
