package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutonomyStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AutonomyStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, AutonomyStore(foundation.observability))
    }

    private fun proposal(
        id: String = "autonomy-1",
        objective: String = "private autonomy objective",
        trigger: String = "private trigger condition",
        createdAt: Instant = Instant.parse("2026-08-29T15:00:00Z")
    ) = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = AutonomyOrigin.Reflection(
            recordId = ReflectionRecordId("reflection-1"),
            generation = ReflectionGeneration(7)
        ),
        objective = objective,
        triggerDescription = trigger,
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(maxAttempts = 3),
        createdAt = createdAt
    )

    private fun context(f: Fixture, operation: String = "testAutonomy") =
        f.foundation.rootContext(operation = operation, component = "Autonomy")

    @Test
    fun registration_exposes_exact_generation_and_stale_safe_removal() {
        val f = fixture()
        val first = proposal()
        val firstRegistration = assertIs<AutonomyRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration

        assertEquals(first, f.store.find(first.id))
        assertEquals(firstRegistration.generation, f.store.inspect(first.id)?.generation)
        assertTrue(firstRegistration.remove(context(f, "removeAutonomy")))
        assertNull(f.store.find(first.id))

        val replacement = proposal(objective = "replacement private objective")
        val current = assertIs<AutonomyRegistrationResult.Registered>(
            f.store.register(replacement, context(f))
        ).registration

        assertNotEquals(firstRegistration.generation, current.generation)
        assertFalse(firstRegistration.remove(context(f, "removeStaleAutonomy")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = proposal(objective = "first private objective")
        val second = proposal(objective = "second private objective")

        assertIs<AutonomyRegistrationResult.Registered>(f.store.register(first, context(f)))
        assertIs<AutonomyRegistrationResult.Rejected>(f.store.register(second, context(f)))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun reflection_origin_is_exact_structural_data_without_lookup() {
        val f = fixture()
        val value = proposal()

        val registration = assertIs<AutonomyRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        val origin = assertIs<AutonomyOrigin.Reflection>(registration.proposal.origin)
        assertEquals("reflection-1", origin.recordId.value)
        assertEquals(7, origin.generation.value)
    }

    @Test
    fun budget_must_be_positive() {
        assertFailsWith<IllegalArgumentException> {
            AutonomyBudget(maxAttempts = 0)
        }
    }

    @Test
    fun private_objective_and_trigger_are_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secretObjective = "never-log-autonomy-objective"
        val secretTrigger = "never-log-autonomy-trigger"
        val value = proposal(objective = secretObjective, trigger = secretTrigger)

        assertFalse(value.toString().contains(secretObjective))
        assertFalse(value.toString().contains(secretTrigger))
        assertIs<AutonomyRegistrationResult.Registered>(f.store.register(value, context(f)))

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secretObjective ||
                event.message == secretTrigger ||
                event.metadata.values.any { it == secretObjective || it == secretTrigger }
        })
    }

    @Test
    fun snapshots_are_deterministic_and_detached() {
        val f = fixture()
        val later = proposal(id = "autonomy-b", createdAt = Instant.parse("2026-08-29T15:01:00Z"))
        val earlier = proposal(id = "autonomy-a", createdAt = Instant.parse("2026-08-29T15:00:00Z"))

        assertIs<AutonomyRegistrationResult.Registered>(f.store.register(later, context(f)))
        assertIs<AutonomyRegistrationResult.Registered>(f.store.register(earlier, context(f)))
        val snapshot = f.store.snapshot()
        val entries = f.store.snapshotEntries()

        assertEquals(listOf("autonomy-a", "autonomy-b"), snapshot.map { it.id.value })
        assertEquals(listOf("autonomy-a", "autonomy-b"), entries.map { it.proposal.id.value })

        assertTrue(assertIs<AutonomyRegistrationResult.Registered>(
            f.store.register(proposal(id = "autonomy-c", createdAt = Instant.parse("2026-08-29T15:02:00Z")), context(f))
        ).registration.remove(context(f, "removeThird")))

        assertEquals(listOf("autonomy-a", "autonomy-b"), snapshot.map { it.id.value })
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val results = (0 until workers).map { index ->
            pool.submit<AutonomyRegistrationResult> {
                ready.countDown()
                start.await()
                f.store.register(
                    proposal(objective = "private objective $index"),
                    context(f, "concurrentAutonomy")
                )
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val completed = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, completed.count { it is AutonomyRegistrationResult.Registered })
        assertEquals(workers - 1, completed.count { it is AutonomyRegistrationResult.Rejected })
        assertTrue(f.store.contains(AutonomyProposalId("autonomy-1")))
    }
}
