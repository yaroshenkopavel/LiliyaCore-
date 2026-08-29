package pro.liliya.core.planning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PlanningReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: PlanningComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, PlanningComposition(foundation))
    }

    private fun proposal(
        id: String = "plan-1",
        goal: String = "private planning goal"
    ) = PlanningProposal(
        id = PlanningProposalId(id),
        origin = PlanningOrigin(
            sourceId = PlanningSourceId("caller"),
            sourceReference = PlanningSourceReference("readiness")
        ),
        goal = goal,
        steps = listOf(
            PlanningStep(PlanningStepId("step-1"), "private planning step")
        ),
        createdAt = Instant.parse("2026-08-29T12:30:00Z")
    )

    @Test
    fun ownership_remove_is_one_shot_and_repeated_remove_fails_closed() {
        val f = fixture("one-shot")
        val ownership = assertIs<PlanningInstallResult.Installed>(
            f.composition.install(proposal())
        ).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertFalse(f.composition.contains(ownership.proposal.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val p = proposal()

        val firstOwnership = assertIs<PlanningInstallResult.Installed>(first.composition.install(p)).ownership
        val secondOwnership = assertIs<PlanningInstallResult.Installed>(second.composition.install(p)).ownership

        assertNotSame(firstOwnership, secondOwnership)
        assertTrue(first.composition.contains(p.id))
        assertTrue(second.composition.contains(p.id))
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(p.id))
        assertTrue(second.composition.contains(p.id))
    }

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val f = fixture("snapshot")
        val first = proposal(id = "plan-a")
        val second = proposal(id = "plan-b")

        assertIs<PlanningInstallResult.Installed>(f.composition.install(first))
        val snapshotBefore = f.composition.snapshot()
        assertEquals(listOf("plan-a"), snapshotBefore.map { it.id.value })

        assertIs<PlanningInstallResult.Installed>(f.composition.install(second))
        assertEquals(listOf("plan-a"), snapshotBefore.map { it.id.value })
        assertEquals(listOf("plan-a", "plan-b"), f.composition.snapshot().map { it.id.value })
    }

    @Test
    fun planning_lifecycle_observability_contains_no_decision_authority_or_execution_semantics() {
        val f = fixture("semantics")
        val secretGoal = "never-observe-this-goal"
        val ownership = assertIs<PlanningInstallResult.Installed>(
            f.composition.install(proposal(goal = secretGoal))
        ).ownership
        ownership.remove()

        val forbiddenTokens = listOf(
            "decision",
            "approved",
            "approval",
            "authority",
            "authorized",
            "capability",
            "execution",
            "execute",
            "executed"
        )

        f.logs.snapshot().forEach { event ->
            assertFalse(event.metadata.values.any { it == secretGoal })
            val keys = event.metadata.keys.map { it.lowercase() }
            assertFalse(keys.any { key -> forbiddenTokens.any { token -> key.contains(token) } })
        }
    }
}
