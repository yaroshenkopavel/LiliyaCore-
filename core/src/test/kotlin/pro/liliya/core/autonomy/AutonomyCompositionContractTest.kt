package pro.liliya.core.autonomy

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
import kotlin.test.assertTrue

class AutonomyCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AutonomyComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-composition-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AutonomyComposition(foundation))
    }

    private fun proposal(
        id: String = "autonomy-1",
        objective: String = "private objective",
        trigger: String = "private trigger"
    ) = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = AutonomyOrigin.Declared(
            sourceId = AutonomySourceId("goal-context"),
            sourceReference = AutonomySourceReference("declared-1")
        ),
        objective = objective,
        trigger = trigger,
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(maxAttempts = 3),
        createdAt = Instant.parse("2026-08-29T14:45:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val value = proposal()

        val installed = assertIs<AutonomyInstallResult.Installed>(f.composition.install(value))

        assertEquals(value, installed.ownership.proposal)
        assertTrue(installed.ownership.generation.value > 0)
        assertEquals(value, f.composition.find(value.id))
        assertTrue(installed.ownership.remove())
        assertFalse(f.composition.contains(value.id))
        assertFalse(installed.ownership.remove())
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = proposal(objective = "first private objective")
        val second = proposal(objective = "second private objective")

        val firstInstalled = assertIs<AutonomyInstallResult.Installed>(f.composition.install(first))
        assertIs<AutonomyInstallResult.Rejected>(f.composition.install(second))

        assertEquals(first, f.composition.find(first.id))
        assertEquals(firstInstalled.ownership.generation, f.composition.inspect(first.id)?.generation)
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(first.remove())

        val replacement = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(proposal(objective = "replacement private objective"))
        ).ownership

        assertFalse(first.remove())
        assertTrue(f.composition.contains(replacement.proposal.id))
        assertEquals(replacement.generation, f.composition.inspect(replacement.proposal.id)?.generation)
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture().composition
        val second = fixture().composition
        val value = proposal()

        val firstOwnership = assertIs<AutonomyInstallResult.Installed>(first.install(value)).ownership
        val secondOwnership = assertIs<AutonomyInstallResult.Installed>(second.install(value)).ownership

        assertNotEquals(firstOwnership, secondOwnership)
        assertTrue(firstOwnership.remove())
        assertFalse(first.contains(value.id))
        assertTrue(second.contains(value.id))
    }

    @Test
    fun objective_and_trigger_are_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secretObjective = "never-log-autonomy-objective"
        val secretTrigger = "never-log-autonomy-trigger"
        val ownership = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(proposal(objective = secretObjective, trigger = secretTrigger))
        ).ownership
        ownership.remove()

        val secrets = setOf(secretObjective, secretTrigger)
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.marker == "AUTONOMY_PROPOSAL_REGISTERED" }
        val removed = f.logs.snapshot().first { it.marker == "AUTONOMY_PROPOSAL_REMOVED" }

        assertEquals("installAutonomyProposal", registered.context.operation)
        assertEquals("removeAutonomyProposal", removed.context.operation)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
    }
}
