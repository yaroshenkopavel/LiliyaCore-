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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutonomyDeliberationStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AutonomyDeliberationStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-request-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, AutonomyDeliberationStore(foundation.observability))
    }

    private fun request(
        id: String = "request-1",
        objective: String = "private deliberation objective",
        attemptNumber: Int = 1,
        createdAt: String = "2026-08-29T15:30:00Z"
    ) = AutonomyDeliberationRequest(
        id = AutonomyDeliberationRequestId(id),
        autonomy = AutonomyAttemptReference(
            proposalId = AutonomyProposalId("autonomy-1"),
            proposalGeneration = AutonomyGeneration(11),
            attemptNumber = attemptNumber
        ),
        objective = objective,
        createdAt = Instant.parse(createdAt)
    )

    @Test
    fun exact_registration_read_and_remove() {
        val f = fixture()
        val value = request()
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")

        val registered = assertIs<AutonomyDeliberationRegistrationResult.Registered>(
            f.store.register(value, context)
        ).registration

        assertEquals(value, f.store.find(value.id))
        assertEquals(value, f.store.inspect(value.id)?.request)
        assertEquals(registered.generation, f.store.inspect(value.id)?.generation)
        assertTrue(registered.remove(f.foundation.childContext(context, "Autonomy", "removeAutonomyDeliberation")))
        assertEquals(null, f.store.find(value.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = request(objective = "first private objective")
        val second = request(objective = "second private objective")
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")

        f.store.register(first, context)
        assertIs<AutonomyDeliberationRegistrationResult.Rejected>(f.store.register(second, context))

        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")
        val first = assertIs<AutonomyDeliberationRegistrationResult.Registered>(
            f.store.register(request(), context)
        ).registration
        assertTrue(first.remove(context))
        val replacement = assertIs<AutonomyDeliberationRegistrationResult.Registered>(
            f.store.register(request(objective = "replacement private objective"), context)
        ).registration

        assertFalse(first.remove(context))
        assertEquals(replacement.generation, f.store.inspect(replacement.request.id)?.generation)
    }

    @Test
    fun exact_autonomy_attempt_provenance_survives_as_data_only() {
        val f = fixture()
        val value = request(attemptNumber = 2)
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")

        f.store.register(value, context)

        assertEquals(value.autonomy, f.store.find(value.id)?.autonomy)
    }

    @Test
    fun private_objective_is_absent_from_rendering_and_observability() {
        val f = fixture()
        val secret = "never-log-deliberation-objective"
        val value = request(objective = secret)
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")

        f.store.register(value, context)

        assertFalse(value.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun snapshot_is_deterministic_and_detached() {
        val f = fixture()
        val context = f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")
        val b = request("request-b", createdAt = "2026-08-29T15:31:00Z")
        val a = request("request-a", createdAt = "2026-08-29T15:30:00Z")
        f.store.register(b, context)
        f.store.register(a, context)

        val snapshot = f.store.snapshot()
        f.store.register(request("request-c", createdAt = "2026-08-29T15:32:00Z"), context)

        assertEquals(listOf(a, b), snapshot)
        assertEquals(listOf(a, b, f.store.find(AutonomyDeliberationRequestId("request-c"))), f.store.snapshot())
    }

    @Test
    fun concurrent_same_id_has_exactly_one_winner() {
        val f = fixture()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) { index ->
            executor.execute {
                try {
                    start.await()
                    val result = f.store.register(
                        request(objective = "private objective $index"),
                        f.foundation.rootContext("registerAutonomyDeliberation", "Autonomy")
                    )
                    if (result is AutonomyDeliberationRegistrationResult.Registered) winners.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, winners.get())
    }
}
