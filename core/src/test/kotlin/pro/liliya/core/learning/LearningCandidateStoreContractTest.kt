package pro.liliya.core.learning

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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningCandidateStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: LearningCandidateStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "learning-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, LearningCandidateStore(foundation.observability))
    }

    private fun candidate(
        id: String = "candidate-1",
        proposal: String = "candidate proposal",
        createdAt: Instant = Instant.parse("2026-08-29T05:00:00Z"),
        origin: LearningOrigin = LearningOrigin.Declared(
            LearningSourceId("caller"),
            LearningSourceReference("contract")
        )
    ) = LearningCandidate(
        id = LearningCandidateId(id),
        origin = origin,
        proposal = proposal,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "Learning")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val c = candidate()
        val registration = assertIs<LearningCandidateRegistrationResult.Registered>(
            f.store.register(c, f.context("register"))
        ).registration

        assertEquals(c, f.store.find(c.id))
        assertEquals(registration.generation, f.store.inspect(c.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(c.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = candidate(proposal = "first")
        val second = candidate(proposal = "second")

        assertIs<LearningCandidateRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<LearningCandidateRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<LearningCandidateRegistrationResult.Registered>(
            f.store.register(candidate(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = candidate(proposal = "replacement")
        val current = assertIs<LearningCandidateRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun reflection_origin_is_structural_without_lookup_or_acceptance_semantics() {
        val f = fixture()
        val secret = "private-learning-proposal"
        val c = candidate(
            proposal = secret,
            origin = LearningOrigin.Reflection(
                ReflectionRecordId("reflection-not-installed"),
                ReflectionGeneration(999L)
            )
        )

        assertIs<LearningCandidateRegistrationResult.Registered>(f.store.register(c, f.context("structural")))
        assertEquals(c, f.store.find(c.id))

        val events = f.logs.snapshot()
        assertTrue(events.any { event -> event.metadata["reflectionRecordId"] == "reflection-not-installed" })
        assertTrue(events.any { event -> event.metadata["reflectionGeneration"] == "999" })
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("accepted", ignoreCase = true) ||
                    key.contains("approved", ignoreCase = true) ||
                    key.contains("applied", ignoreCase = true) ||
                    key.contains("truth", ignoreCase = true) ||
                    key.contains("confidence", ignoreCase = true) ||
                    key.contains("trust", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true)
            }
        })
    }

    @Test
    fun proposal_is_redacted_from_to_string_and_observability_metadata() {
        val f = fixture()
        val secret = "never-log-learning-proposal"
        val c = candidate(proposal = secret)

        assertFalse(c.toString().contains(secret))
        assertTrue(c.toString().contains("<redacted>"))
        assertIs<LearningCandidateRegistrationResult.Registered>(f.store.register(c, f.context("redaction")))
        assertFalse(f.logs.snapshot().any { event -> event.metadata.values.any { value -> value == secret } })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val later = Instant.parse("2026-08-29T06:00:00Z")
        val earlier = Instant.parse("2026-08-29T05:00:00Z")

        listOf(
            candidate(id = "b", createdAt = earlier),
            candidate(id = "c", createdAt = later),
            candidate(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<LearningCandidateRegistrationResult.Registered>(
                f.store.register(item, f.context("snapshot-$index"))
            )
        }

        assertEquals(listOf("a", "b", "c"), f.store.snapshot().map { it.id.value })
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)

        repeat(threads) { index ->
            pool.execute {
                try {
                    ready.countDown()
                    start.await()
                    val result = f.store.register(
                        candidate(proposal = "proposal-$index"),
                        f.context("concurrent-$index")
                    )
                    if (result is LearningCandidateRegistrationResult.Registered) {
                        winners.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, winners.get())
        assertTrue(f.store.contains(LearningCandidateId("candidate-1")))
    }
}
