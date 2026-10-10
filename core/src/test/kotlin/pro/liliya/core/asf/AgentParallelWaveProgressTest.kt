package pro.liliya.core.asf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentParallelWaveProgressTest {
    private val root = AgentCoordinatorStepId("root")
    private val a = AgentCoordinatorStepId("a")
    private val b = AgentCoordinatorStepId("b")

    private fun schedule() = AgentParallelScheduleResult.Ready(
        listOf(
            AgentParallelWave(0, listOf(root), reservation(1)),
            AgentParallelWave(1, listOf(a, b), reservation(2))
        )
    )

    private fun reservation(agents: Int) = AgentParallelWaveReservation(
        maxWallClockMillis = 1_000,
        maxInferenceUnits = 1_000,
        maxContextBytes = 1_000,
        maxRetrievalItems = 0,
        maxArtifacts = agents,
        maxAgents = agents
    )

    private fun success(vararg ids: AgentCoordinatorStepId) =
        AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            ids.map { AgentParallelWaveTaskOutcome.Completed(it) }
        )

    @Test
    fun completed_waves_advance_in_dependency_order() {
        val first = assertNotNull(AgentParallelWaveProgress().advance(schedule(), success(root)))
        assertEquals(1, first.nextWaveIndex)
        assertEquals(setOf(root), first.completedStepIds)
        val second = assertNotNull(first.advance(schedule(), success(a, b)))
        assertEquals(2, second.nextWaveIndex)
        assertEquals(setOf(root, a, b), second.completedStepIds)
        assertNull(second.advance(schedule(), success(root)))
    }

    @Test
    fun incomplete_failed_and_wrong_wave_results_never_advance() {
        val first = assertNotNull(AgentParallelWaveProgress().advance(schedule(), success(root)))
        assertNull(AgentParallelWaveProgress().advance(schedule(), success(a, b)))
        assertNull(first.advance(schedule(), success(a)))
        assertNull(first.advance(schedule(), success(root)))
        assertNull(first.advance(schedule(), AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.PARTIAL,
            listOf(AgentParallelWaveTaskOutcome.Completed(a))
        )))
        assertNull(first.advance(schedule(), AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            listOf(
                AgentParallelWaveTaskOutcome.Completed(a),
                AgentParallelWaveTaskOutcome.Failed(b, "rejected")
            )
        )))
        assertNull(first.advance(schedule(), AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.CANCELLED, emptyList()
        )))
    }

    @Test
    fun forged_prior_completion_or_index_is_rejected() {
        val forged = AgentParallelWaveProgress(setOf(a), 1)
        assertNull(forged.advance(schedule(), success(a, b)))
        val skipped = AgentParallelWaveProgress(setOf(root), 2)
        assertNull(skipped.advance(schedule(), success(a, b)))
    }
}
