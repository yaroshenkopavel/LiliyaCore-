package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMetacognitiveFactoryControllerContractTest {
    private val now = Instant.parse("2026-10-01T13:20:00Z")
    private val expires = Instant.parse("2026-10-01T13:30:00Z")
    private val rootTask = AgentRootTaskId("root-asf-l")

    private val budget = AgentAggregateBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 100_000,
        maxRetrievalItems = 100,
        maxArtifacts = 20,
        maxAgents = 6
    )

    @Test
    fun cancellation_expiry_terminal_and_budget_exhaustion_are_hard_stops() {
        val controller = AgentMetacognitiveFactoryController()

        assertHardStop(
            controller.decide(observation(cancelled = true)),
            AgentFactoryControlReason.CANCELLED
        )
        assertHardStop(
            controller.decide(observation(observedAt = expires)),
            AgentFactoryControlReason.EXPIRED
        )
        assertHardStop(
            controller.decide(observation(terminal = true)),
            AgentFactoryControlReason.TERMINAL
        )
        assertHardStop(
            controller.decide(
                observation(
                    usage = AgentAggregateUsage(
                        wallClockMillis = 10_001,
                        inferenceUnits = 0,
                        contextBytes = 0,
                        retrievalItems = 0,
                        artifactCount = 0,
                        agentsStarted = 0
                    )
                )
            ),
            AgentFactoryControlReason.BUDGET_EXHAUSTED
        )
    }

    @Test
    fun critical_pressure_abandons_without_budget_or_capability_grant() {
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(pressure = AgentFactoryControlPressure.CRITICAL)
        )

        assertEquals(AgentFactoryControlDecisionKind.ABANDON, decision.kind)
        assertEquals(AgentFactoryControlReason.CRITICAL_PRESSURE, decision.reason)
        assertNull(decision.nextBudget)
        assertNull(decision.nextCapacity)
        assertFalse(decision.requiresFreshAdmission)
    }

    @Test
    fun low_value_branch_simplifies_with_strictly_remaining_budget() {
        val usage = AgentAggregateUsage(
            wallClockMillis = 1_000,
            inferenceUnits = 2_000,
            contextBytes = 10_000,
            retrievalItems = 10,
            artifactCount = 2,
            agentsStarted = 1
        )
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(
                usage = usage,
                lowValue = 2
            )
        )

        assertEquals(AgentFactoryControlDecisionKind.SIMPLIFY, decision.kind)
        assertEquals(AgentFactoryControlReason.LOW_VALUE_BRANCH, decision.reason)
        val next = assertNotNull(decision.nextBudget)
        assertTrue(next.maxWallClockMillis < budget.maxWallClockMillis)
        assertTrue(next.maxInferenceUnits < budget.maxInferenceUnits)
        assertTrue(next.maxContextBytes < budget.maxContextBytes)
        assertTrue(next.maxRetrievalItems < budget.maxRetrievalItems)
        assertTrue(next.maxArtifacts < budget.maxArtifacts)
        assertTrue(next.maxAgents < budget.maxAgents)
        assertFalse(decision.requiresFreshAdmission)
    }

    @Test
    fun repeated_contradiction_recomposes_only_with_fresh_admission() {
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(
                contradiction = 2,
                recompositionCount = 0
            )
        )

        assertEquals(AgentFactoryControlDecisionKind.RECOMPOSE, decision.kind)
        assertEquals(AgentFactoryControlReason.REPEATED_CONTRADICTION, decision.reason)
        assertTrue(decision.requiresFreshAdmission)
        assertNotNull(decision.nextBudget)
        assertNotNull(decision.nextCapacity)
    }

    @Test
    fun endless_recomposition_is_bounded_and_terminal_after_limit() {
        val controller = AgentMetacognitiveFactoryController(
            AgentFactoryControlPolicy(maxRecompositions = 2)
        )

        assertEquals(
            AgentFactoryControlDecisionKind.RECOMPOSE,
            controller.decide(observation(contradiction = 3, recompositionCount = 0)).kind
        )
        assertEquals(
            AgentFactoryControlDecisionKind.RECOMPOSE,
            controller.decide(observation(contradiction = 3, recompositionCount = 1)).kind
        )

        val terminal = controller.decide(
            observation(contradiction = 3, recompositionCount = 2)
        )
        assertEquals(AgentFactoryControlDecisionKind.ABANDON, terminal.kind)
        assertEquals(AgentFactoryControlReason.RECOMPOSITION_LIMIT, terminal.reason)
        assertNull(terminal.nextBudget)
        assertNull(terminal.nextCapacity)
    }

    @Test
    fun self_justifying_continuation_is_not_allowed_when_no_requirements_remain() {
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(remainingRequirements = emptyList())
        )

        assertEquals(AgentFactoryControlDecisionKind.STOP, decision.kind)
        assertEquals(AgentFactoryControlReason.NO_REMAINING_REQUIREMENTS, decision.reason)
    }

    @Test
    fun elevated_pressure_never_widens_worker_classes_or_count() {
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(
                pressure = AgentFactoryControlPressure.ELEVATED,
                activeWorkerClasses = listOf(
                    AgentWorkerClass.NANO,
                    AgentWorkerClass.MICRO,
                    AgentWorkerClass.FULL
                )
            )
        )

        assertEquals(AgentFactoryControlDecisionKind.SIMPLIFY, decision.kind)
        val capacity = assertNotNull(decision.nextCapacity)
        assertTrue(AgentWorkerClass.FULL !in capacity.allowedWorkerClasses)
        assertTrue(capacity.allowedWorkerClasses.all {
            it in setOf(AgentWorkerClass.NANO, AgentWorkerClass.MICRO)
        })
        assertTrue(capacity.maxWorkers <= 2)
    }

    @Test
    fun continuation_uses_remaining_budget_not_original_budget() {
        val usage = AgentAggregateUsage(
            wallClockMillis = 500,
            inferenceUnits = 400,
            contextBytes = 2_000,
            retrievalItems = 5,
            artifactCount = 1,
            agentsStarted = 1
        )
        val decision = AgentMetacognitiveFactoryController().decide(
            observation(usage = usage)
        )

        assertEquals(AgentFactoryControlDecisionKind.CONTINUE, decision.kind)
        val next = assertNotNull(decision.nextBudget)
        assertEquals(9_500, next.maxWallClockMillis)
        assertEquals(9_600, next.maxInferenceUnits)
        assertEquals(98_000, next.maxContextBytes)
        assertEquals(95, next.maxRetrievalItems)
        assertEquals(19, next.maxArtifacts)
        assertEquals(5, next.maxAgents)
    }

    @Test
    fun every_controller_decision_is_audited() {
        val records = mutableListOf<AgentFactoryControlAuditRecord>()
        val controller = AgentMetacognitiveFactoryController(
            auditLedger = AgentFactoryControlAuditLedger { records += it }
        )

        controller.decide(observation())
        controller.decide(observation(lowValue = 2))
        controller.decide(observation(cancelled = true))

        assertEquals(3, records.size)
        assertEquals(
            listOf(
                AgentFactoryControlDecisionKind.CONTINUE,
                AgentFactoryControlDecisionKind.SIMPLIFY,
                AgentFactoryControlDecisionKind.STOP
            ),
            records.map { it.kind }
        )
        assertTrue(records.all { it.rootTaskId == rootTask })
        assertTrue(records.all { it.observationReferences == listOf("evidence:controller") })
    }

    @Test
    fun controller_contracts_do_not_carry_authority_permission_or_execution_grants() {
        val forbidden = listOf(
            "authority", "permission", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentFactoryControlObservation::class.java,
            AgentFactoryControlDecision::class.java,
            AgentFactoryControlAuditRecord::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it })
            }
        }
    }

    private fun observation(
        observedAt: Instant = now,
        cancelled: Boolean = false,
        terminal: Boolean = false,
        usage: AgentAggregateUsage = AgentAggregateUsage(),
        activeWorkerClasses: List<AgentWorkerClass> = listOf(
            AgentWorkerClass.NANO,
            AgentWorkerClass.MICRO,
            AgentWorkerClass.FULL
        ),
        remainingRequirements: List<AgentWorkerRequirement> = listOf(
            AgentWorkerRequirement.NARROW_MULTI_STEP
        ),
        pressure: AgentFactoryControlPressure = AgentFactoryControlPressure.NONE,
        lowValue: Int = 0,
        contradiction: Int = 0,
        recompositionCount: Int = 0
    ) = AgentFactoryControlObservation(
        rootTaskId = rootTask,
        observedAt = observedAt,
        expiresAt = expires,
        cancelled = cancelled,
        terminal = terminal,
        aggregateBudget = budget,
        aggregateUsage = usage,
        activeWorkerClasses = activeWorkerClasses.distinct().sortedBy { it.order },
        remainingRequirements = remainingRequirements.distinct().sortedBy { it.ordinal },
        pressure = pressure,
        consecutiveLowValueSteps = lowValue,
        repeatedContradictionCount = contradiction,
        recompositionCount = recompositionCount,
        observationReferences = listOf("evidence:controller")
    )

    private fun assertHardStop(
        decision: AgentFactoryControlDecision,
        reason: AgentFactoryControlReason
    ) {
        assertEquals(AgentFactoryControlDecisionKind.STOP, decision.kind)
        assertEquals(reason, decision.reason)
        assertNull(decision.nextBudget)
        assertNull(decision.nextCapacity)
        assertFalse(decision.requiresFreshAdmission)
    }
}
