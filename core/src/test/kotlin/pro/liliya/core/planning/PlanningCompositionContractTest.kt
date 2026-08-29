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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: PlanningComposition
    )

    private fun fixture(prefix: String = "planning"): Fixture {
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
        goal: String = "private goal"
    ) = PlanningProposal(
        id = PlanningProposalId(id),
        origin = PlanningOrigin(
            PlanningSourceId("caller"),
            PlanningSourceReference("contract")
        ),
        goal = goal,
        steps = listOf(
            PlanningStep(PlanningStepId("step-1"), "private first step"),
            PlanningStep(PlanningStepId("step-2"), "private second step")
        ),
        createdAt = Instant.parse("2026-08-29T12:00:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val p = proposal()
        val ownership = assertIs<PlanningInstallResult.Installed>(f.composition.install(p)).ownership

        assertEquals(p, ownership.proposal)
        assertEquals(ownership.generation, f.composition.inspect(p.id)?.generation)
        assertEquals(p, f.composition.find(p.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(p.id))
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = proposal(goal = "first private goal")
        val second = proposal(goal = "second private goal")

        assertIs<PlanningInstallResult.Installed>(f.composition.install(first))
        assertIs<PlanningInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<PlanningInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(stale.remove())

        val replacement = proposal(goal = "replacement private goal")
        val current = assertIs<PlanningInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun compositions_are_isolated() {
        val first = fixture("first")
        val second = fixture("second")
        val p = proposal()

        assertIs<PlanningInstallResult.Installed>(first.composition.install(p))
        assertTrue(first.composition.contains(p.id))
        assertFalse(second.composition.contains(p.id))
    }

    @Test
    fun goal_and_steps_are_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secretGoal = "never-log-planning-goal"
        val secretStep = "never-log-planning-step"
        val p = PlanningProposal(
            id = PlanningProposalId("private-plan"),
            origin = PlanningOrigin(PlanningSourceId("caller")),
            goal = secretGoal,
            steps = listOf(PlanningStep(PlanningStepId("private-step"), secretStep)),
            createdAt = Instant.parse("2026-08-29T12:00:00Z")
        )

        val ownership = assertIs<PlanningInstallResult.Installed>(f.composition.install(p)).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value -> value == secretGoal || value == secretStep }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<PlanningInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.message == "planning proposal registered" }
        val removed = f.logs.snapshot().first { it.message == "planning proposal removed" }

        assertEquals(registered.context.correlationId, removed.context.correlationId)
        assertEquals(registered.context.operationId, removed.context.parentOperationId)
    }
}
