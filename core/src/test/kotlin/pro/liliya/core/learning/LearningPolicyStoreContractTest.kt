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

class LearningPolicyStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: LearningPolicyStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "learning-policy-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, LearningPolicyStore(foundation.observability))
    }

    private fun policy(
        id: String = "policy-1",
        rule: String = "caller supplied learning policy rule",
        createdAt: Instant = Instant.parse("2026-08-29T12:00:00Z")
    ) = LearningPolicy(
        id = LearningPolicyId(id),
        rule = rule,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "LearningPolicy")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val p = policy()
        val registration = assertIs<LearningPolicyRegistrationResult.Registered>(
            f.store.register(p, f.context("register"))
        ).registration

        assertEquals(p, f.store.find(p.id))
        assertEquals(registration.generation, f.store.inspect(p.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(p.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = policy(rule = "first")
        val second = policy(rule = "second")

        assertIs<LearningPolicyRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<LearningPolicyRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<LearningPolicyRegistrationResult.Registered>(
            f.store.register(policy(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = policy(rule = "replacement")
        val current = assertIs<LearningPolicyRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun rule_is_redacted_and_policy_presence_creates_no_evaluation_or_application_semantics() {
        val f = fixture()
        val secret = "never expose policy rule"
        val p = policy(rule = secret)

        assertFalse(p.toString().contains(secret))
        assertTrue(p.toString().contains("<redacted>"))
        assertIs<LearningPolicyRegistrationResult.Registered>(f.store.register(p, f.context("boundary")))

        val events = f.logs.snapshot()
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        val forbidden = listOf(
            "decision", "approve", "reject", "evaluat", "applied", "application",
            "consolidat", "authorized", "authorization", "memory", "knowledge",
            "personality", "self", "truth", "confidence", "trust", "authority",
            "capability", "execution"
        )
        assertFalse(events.any { event ->
            event.metadata.keys.any { key -> forbidden.any { token -> key.contains(token, ignoreCase = true) } }
        })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val earlier = Instant.parse("2026-08-29T11:00:00Z")
        val later = Instant.parse("2026-08-29T12:00:00Z")
        listOf(
            policy(id = "b", createdAt = earlier),
            policy(id = "c", createdAt = later),
            policy(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<LearningPolicyRegistrationResult.Registered>(
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
                    if (f.store.register(policy(rule = "rule-$index"), f.context("concurrent-$index")) is LearningPolicyRegistrationResult.Registered) {
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
        assertTrue(f.store.contains(LearningPolicyId("policy-1")))
    }
}
