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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningDecisionStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: LearningDecisionStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "learning-decision-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, LearningDecisionStore(foundation.observability))
    }

    private fun decision(
        id: String = "decision-1",
        rationale: String = "explicit caller rationale",
        disposition: LearningDecisionDisposition = LearningDecisionDisposition.APPROVE,
        candidateId: String = "candidate-1",
        candidateGeneration: Long = 1L,
        createdAt: Instant = Instant.parse("2026-08-29T09:00:00Z")
    ) = LearningDecision(
        id = LearningDecisionId(id),
        candidate = LearningCandidateReference(
            candidateId = LearningCandidateId(candidateId),
            generation = LearningGeneration(candidateGeneration)
        ),
        disposition = disposition,
        rationale = rationale,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "LearningDecision")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val d = decision()
        val registration = assertIs<LearningDecisionRegistrationResult.Registered>(
            f.store.register(d, f.context("register"))
        ).registration

        assertEquals(d, f.store.find(d.id))
        assertEquals(registration.generation, f.store.inspect(d.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(d.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = decision(rationale = "first")
        val second = decision(rationale = "second", disposition = LearningDecisionDisposition.REJECT)

        assertIs<LearningDecisionRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<LearningDecisionRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<LearningDecisionRegistrationResult.Registered>(
            f.store.register(decision(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = decision(rationale = "replacement")
        val current = assertIs<LearningDecisionRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun candidate_reference_is_structural_without_lookup_or_application_semantics() {
        val f = fixture()
        val secret = "private-decision-rationale"
        val d = decision(
            rationale = secret,
            candidateId = "candidate-not-installed",
            candidateGeneration = 999L,
            disposition = LearningDecisionDisposition.APPROVE
        )

        assertIs<LearningDecisionRegistrationResult.Registered>(f.store.register(d, f.context("structural")))
        assertEquals(d, f.store.find(d.id))

        val events = f.logs.snapshot()
        assertTrue(events.any { event -> event.metadata["learningCandidateId"] == "candidate-not-installed" })
        assertTrue(events.any { event -> event.metadata["learningCandidateGeneration"] == "999" })
        assertTrue(events.any { event -> event.metadata["learningDecisionDisposition"] == "approve" })
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("applied", ignoreCase = true) ||
                    key.contains("consolidat", ignoreCase = true) ||
                    key.contains("authorized", ignoreCase = true) ||
                    key.contains("memory", ignoreCase = true) ||
                    key.contains("knowledge", ignoreCase = true) ||
                    key.contains("personality", ignoreCase = true) ||
                    key.contains("self", ignoreCase = true) ||
                    key.contains("truth", ignoreCase = true) ||
                    key.contains("confidence", ignoreCase = true) ||
                    key.contains("trust", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true)
            }
        })
    }

    @Test
    fun rationale_is_redacted_from_to_string_and_observability_metadata() {
        val f = fixture()
        val secret = "never-log-decision-rationale"
        val d = decision(rationale = secret)

        assertFalse(d.toString().contains(secret))
        assertTrue(d.toString().contains("<redacted>"))
        assertIs<LearningDecisionRegistrationResult.Registered>(f.store.register(d, f.context("redaction")))
        assertFalse(f.logs.snapshot().any { event -> event.metadata.values.any { value -> value == secret } })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val later = Instant.parse("2026-08-29T10:00:00Z")
        val earlier = Instant.parse("2026-08-29T09:00:00Z")

        listOf(
            decision(id = "b", createdAt = earlier),
            decision(id = "c", createdAt = later),
            decision(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<LearningDecisionRegistrationResult.Registered>(
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
                        decision(rationale = "rationale-$index"),
                        f.context("concurrent-$index")
                    )
                    if (result is LearningDecisionRegistrationResult.Registered) {
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
        assertTrue(f.store.contains(LearningDecisionId("decision-1")))
    }
}
