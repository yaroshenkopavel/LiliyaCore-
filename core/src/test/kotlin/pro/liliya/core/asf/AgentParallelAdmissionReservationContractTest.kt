package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentParallelAdmissionReservationContractTest {
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val verifyScope = AgentCognitiveScope.create(listOf("verification"))
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
        maxArtifacts = 8,
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

    @Test
    fun valid_parallel_plan_reserves_deterministic_structure() {
        val ready = assertIs<AgentParallelAdmissionResult.Ready>(
            AgentParallelAdmissionReservation.reserve(
                plan(
                    children = listOf(
                        child("b"),
                        child("a")
                    )
                )
            )
        )

        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 0,
                AgentCoordinatorStepId("b") to 0,
                AgentCoordinatorStepId("root") to 2
            ),
            ready.snapshot.directChildrenByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 0,
                AgentCoordinatorStepId("b") to 0,
                AgentCoordinatorStepId("root") to 2
            ),
            ready.snapshot.descendantsByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 1,
                AgentCoordinatorStepId("b") to 1,
                AgentCoordinatorStepId("root") to 0
            ),
            ready.snapshot.depthByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 1,
                AgentCoordinatorStepId("b") to 0,
                AgentCoordinatorStepId("root") to 0
            ),
            ready.snapshot.priorActiveAgentsByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 2,
                AgentCoordinatorStepId("b") to 1,
                AgentCoordinatorStepId("root") to 0
            ),
            ready.snapshot.priorAgentsForRootTaskByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 1,
                AgentCoordinatorStepId("b") to 0,
                AgentCoordinatorStepId("root") to 0
            ),
            ready.snapshot.priorDirectChildrenForParentByStep
        )
        assertEquals(
            mapOf(
                AgentCoordinatorStepId("a") to 2,
                AgentCoordinatorStepId("b") to 3,
                AgentCoordinatorStepId("root") to 4
            ),
            ready.snapshot.parentRemainingDescendantsByStep
        )
    }

    @Test
    fun direct_child_limit_is_rejected_before_parallel_admission() {
        val bounds = AgentFactoryBounds.PROTOTYPE.copy(maxDirectChildren = 1)
        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                plan(listOf(child("a"), child("b"))),
                bounds
            )
        )
        assertEquals(AgentParallelAdmissionRejection.DIRECT_CHILD_LIMIT, rejected.reason)
        assertEquals(AgentCoordinatorStepId("b"), rejected.stepId)
    }

    @Test
    fun ancestor_descendant_budget_is_rejected_before_parallel_admission() {
        val constrainedRoot = rootBudget.copy(maxDescendants = 1)
        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("parallel-admission-root"),
                    listOf(
                        root(constrainedRoot),
                        child("a"),
                        child("b")
                    )
                )
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.DESCENDANT_BUDGET_EXCEEDED,
            rejected.reason
        )
        assertEquals(AgentCoordinatorStepId("b"), rejected.stepId)
    }

    @Test
    fun child_scope_cannot_widen_beyond_parent_scope() {
        val narrowRoot = AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId("root"),
            parentStepId = null,
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = verifyScope,
            budget = rootBudget,
            inputReferences = listOf("evidence:root")
        )
        val wideningChild = AgentCoordinatorStep.create(
            id = AgentCoordinatorStepId("child"),
            parentStepId = AgentCoordinatorStepId("root"),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            cognitiveScope = broadScope,
            budget = childBudget,
            inputReferences = listOf("evidence:child")
        )

        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                AgentCoordinatorPlan(
                    AgentRootTaskId("parallel-admission-root"),
                    listOf(narrowRoot, wideningChild)
                )
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.PARENT_SCOPE_TOO_NARROW,
            rejected.reason
        )
    }

    @Test
    fun child_budget_cannot_widen_beyond_parent_budget() {
        val widening = childBudget.copy(maxInferenceUnits = rootBudget.maxInferenceUnits + 1)
        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                plan(listOf(child("a", widening)))
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.CHILD_BUDGET_WIDENING,
            rejected.reason
        )
    }

    @Test
    fun retry_limit_is_rejected_before_parallel_admission() {
        val retrying = child("a", attempt = AgentFactoryBounds.PROTOTYPE.maxRetryPerLogicalRole + 1)
        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                plan(listOf(retrying))
            )
        )
        assertEquals(AgentParallelAdmissionRejection.RETRY_LIMIT, rejected.reason)
    }

    @Test
    fun active_population_limit_is_applied_to_one_dependency_wave() {
        val bounds = AgentFactoryBounds.PROTOTYPE.copy(
            maxActiveAgents = 1,
            maxDirectChildren = 4
        )
        val rejected = assertIs<AgentParallelAdmissionResult.Rejected>(
            AgentParallelAdmissionReservation.reserve(
                plan(listOf(child("a"), child("b"))),
                bounds
            )
        )
        assertEquals(
            AgentParallelAdmissionRejection.ACTIVE_POPULATION_LIMIT,
            rejected.reason
        )
        assertEquals(AgentCoordinatorStepId("b"), rejected.stepId)
    }

    private fun plan(
        children: List<AgentCoordinatorStep>
    ) = AgentCoordinatorPlan(
        rootTaskId = AgentRootTaskId("parallel-admission-root"),
        steps = listOf(root(rootBudget)) + children
    )

    private fun root(
        budget: AgentWorkBudget
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId("root"),
        parentStepId = null,
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = broadScope,
        budget = budget,
        inputReferences = listOf("evidence:root")
    )

    private fun child(
        id: String,
        budget: AgentWorkBudget = childBudget,
        attempt: Int = 0
    ) = AgentCoordinatorStep.create(
        id = AgentCoordinatorStepId(id),
        parentStepId = AgentCoordinatorStepId("root"),
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        cognitiveScope = verifyScope,
        budget = budget,
        inputReferences = listOf("evidence:$id"),
        logicalRoleAttempt = attempt
    )
}
