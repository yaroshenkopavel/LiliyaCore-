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

class LearningApplicationStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: LearningApplicationStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "learning-application-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, LearningApplicationStore(foundation.observability))
    }

    private fun intent(
        id: String = "application-1",
        decisionId: String = "decision-1",
        decisionGeneration: Long = 3L,
        policyId: String = "policy-1",
        policyGeneration: Long = 4L,
        target: LearningApplicationTarget = LearningApplicationTarget.MEMORY,
        createdAt: Instant = Instant.parse("2026-08-29T00:00:00Z")
    ) = LearningApplicationIntent(
        id = LearningApplicationId(id),
        decision = LearningDecisionReference(
            decisionId = LearningDecisionId(decisionId),
            generation = LearningDecisionGeneration(decisionGeneration)
        ),
        policy = LearningPolicyReference(
            policyId = LearningPolicyId(policyId),
            generation = LearningPolicyGeneration(policyGeneration)
        ),
        target = target,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "LearningApplication")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val application = intent()
        val registration = assertIs<LearningApplicationRegistrationResult.Registered>(
            f.store.register(application, f.context("register"))
        ).registration

        assertEquals(application, f.store.find(application.id))
        assertEquals(registration.generation, f.store.inspect(application.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(application.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = intent(target = LearningApplicationTarget.MEMORY)
        val second = intent(target = LearningApplicationTarget.KNOWLEDGE)

        assertIs<LearningApplicationRegistrationResult.Registered>(
            f.store.register(first, f.context("first"))
        )
        assertIs<LearningApplicationRegistrationResult.Rejected>(
            f.store.register(second, f.context("second"))
        )
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<LearningApplicationRegistrationResult.Registered>(
            f.store.register(intent(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = intent(target = LearningApplicationTarget.KNOWLEDGE)
        val current = assertIs<LearningApplicationRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun exact_references_and_target_are_structural_only_without_hidden_lookup_or_mutation() {
        val f = fixture()
        val application = intent(
            decisionId = "missing-decision",
            decisionGeneration = 91L,
            policyId = "missing-policy",
            policyGeneration = 92L,
            target = LearningApplicationTarget.KNOWLEDGE
        )

        assertIs<LearningApplicationRegistrationResult.Registered>(
            f.store.register(application, f.context("structural"))
        )
        assertEquals(application, f.store.find(application.id))

        val forbiddenMetadataTokens = listOf(
            "authorized", "authorization", "applied", "executed", "executionResult",
            "consolidated", "consolidationResult", "memoryRecordId", "knowledgeItemId",
            "truth", "confidence", "trust", "capabilityGrant"
        )
        val keys = f.logs.snapshot().flatMap { event -> event.metadata.keys }
        assertFalse(keys.any { key ->
            forbiddenMetadataTokens.any { token -> key.contains(token, ignoreCase = true) }
        })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val earlier = Instant.parse("2026-08-28T23:00:00Z")
        val later = Instant.parse("2026-08-29T00:00:00Z")
        listOf(
            intent(id = "b", createdAt = earlier),
            intent(id = "c", createdAt = later),
            intent(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<LearningApplicationRegistrationResult.Registered>(
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
                    val target = if (index % 2 == 0) {
                        LearningApplicationTarget.MEMORY
                    } else {
                        LearningApplicationTarget.KNOWLEDGE
                    }
                    if (
                        f.store.register(
                            intent(target = target),
                            f.context("concurrent-$index")
                        ) is LearningApplicationRegistrationResult.Registered
                    ) {
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
        assertTrue(f.store.contains(LearningApplicationId("application-1")))
    }
}
