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

class AutonomyDeliberationCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AutonomyDeliberationComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-deliberation-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AutonomyDeliberationComposition(foundation))
    }

    private fun request(
        id: String = "request-1",
        objective: String = "private deliberation objective",
        attemptNumber: Int = 1,
        createdAt: String = "2026-08-29T15:05:00Z"
    ) = AutonomyDeliberationRequest(
        id = AutonomyDeliberationRequestId(id),
        autonomy = AutonomyAttemptReference(
            proposalId = AutonomyProposalId("autonomy-1"),
            proposalGeneration = AutonomyGeneration(7),
            attemptNumber = attemptNumber
        ),
        objective = objective,
        createdAt = Instant.parse(createdAt)
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val value = request()

        val installed = assertIs<AutonomyDeliberationInstallResult.Installed>(f.composition.install(value))

        assertEquals(value, installed.ownership.request)
        assertTrue(installed.ownership.generation.value > 0)
        assertEquals(value, f.composition.find(value.id))
        assertTrue(installed.ownership.remove())
        assertFalse(f.composition.contains(value.id))
        assertFalse(installed.ownership.remove())
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = request(objective = "first private objective")
        val second = request(objective = "second private objective")

        val installed = assertIs<AutonomyDeliberationInstallResult.Installed>(f.composition.install(first))
        assertIs<AutonomyDeliberationInstallResult.Rejected>(f.composition.install(second))

        assertEquals(first, f.composition.find(first.id))
        assertEquals(installed.ownership.generation, f.composition.inspect(first.id)?.generation)
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AutonomyDeliberationInstallResult.Installed>(f.composition.install(request())).ownership
        assertTrue(first.remove())

        val replacement = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.composition.install(request(objective = "replacement private objective", attemptNumber = 2))
        ).ownership

        assertFalse(first.remove())
        assertTrue(f.composition.contains(replacement.request.id))
        assertEquals(replacement.generation, f.composition.inspect(replacement.request.id)?.generation)
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture().composition
        val second = fixture().composition
        val value = request()

        val firstOwnership = assertIs<AutonomyDeliberationInstallResult.Installed>(first.install(value)).ownership
        val secondOwnership = assertIs<AutonomyDeliberationInstallResult.Installed>(second.install(value)).ownership

        assertNotEquals(firstOwnership, secondOwnership)
        assertTrue(firstOwnership.remove())
        assertFalse(first.contains(value.id))
        assertTrue(second.contains(value.id))
    }

    @Test
    fun objective_is_absent_from_lifecycle_observability() {
        val f = fixture()
        val secret = "never-log-deliberation-objective"
        val ownership = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.composition.install(request(objective = secret))
        ).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<AutonomyDeliberationInstallResult.Installed>(f.composition.install(request())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.marker == "AUTONOMY_DELIBERATION_REQUEST_REGISTERED" }
        val removed = f.logs.snapshot().first { it.marker == "AUTONOMY_DELIBERATION_REQUEST_REMOVED" }

        assertEquals("installAutonomyDeliberationRequest", registered.context.operation)
        assertEquals("removeAutonomyDeliberationRequest", removed.context.operation)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
    }

    @Test
    fun snapshot_is_detached_and_deterministic() {
        val f = fixture()
        val b = request(id = "request-b", createdAt = "2026-08-29T15:06:00Z")
        val a = request(id = "request-a", createdAt = "2026-08-29T15:05:00Z")
        f.composition.install(b)
        f.composition.install(a)

        val snapshot = f.composition.snapshot()
        f.composition.install(request(id = "request-c", createdAt = "2026-08-29T15:07:00Z"))

        assertEquals(listOf(a, b), snapshot)
        assertEquals(listOf(a, b, request(id = "request-c", createdAt = "2026-08-29T15:07:00Z")), f.composition.snapshot())
    }
}
