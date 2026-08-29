package pro.liliya.core.orchestration

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrchestrationIntentStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: OrchestrationIntentStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "orchestration-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, OrchestrationIntentStore(foundation.observability))
    }

    private fun decisionReference() = OrchestrationDecisionReference(
        decisionId = DecisionId("decision-1"),
        generation = DecisionGeneration(5),
        selectedOptionId = DecisionOptionId("option-a")
    )

    private fun intent(
        id: String = "intent-1",
        decision: OrchestrationDecisionReference = decisionReference(),
        description: String = "private downstream intention",
        createdAt: Instant = Instant.parse("2026-08-29T14:00:00Z")
    ) = OrchestrationIntent(
        id = OrchestrationIntentId(id),
        decision = decision,
        description = description,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "Orchestration")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val value = intent()
        val registration = assertIs<OrchestrationRegistrationResult.Registered>(
            f.store.register(value, f.context("register"))
        ).registration

        assertEquals(value, f.store.find(value.id))
        assertEquals(registration.generation, f.store.inspect(value.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(value.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = intent(description = "first private intention")
        val second = intent(description = "second private intention")

        assertIs<OrchestrationRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<OrchestrationRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<OrchestrationRegistrationResult.Registered>(
            f.store.register(intent(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = intent(description = "replacement private intention")
        val current = assertIs<OrchestrationRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun invalid_identity_generation_and_description_are_rejected() {
        assertFailsWith<IllegalArgumentException> { OrchestrationIntentId(" ") }
        assertFailsWith<IllegalArgumentException> { OrchestrationGeneration(0) }
        assertFailsWith<IllegalArgumentException> { intent(description = " ") }
    }

    @Test
    fun description_is_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secret = "never-observe-orchestration-description"
        val value = intent(description = secret)

        assertFalse(value.toString().contains(secret))
        assertIs<OrchestrationRegistrationResult.Registered>(f.store.register(value, f.context("privacy")))
        assertFalse(f.logs.snapshot().any { event -> event.metadata.values.any { it == secret } })
    }

    @Test
    fun decision_reference_is_structural_and_preserved_exactly() {
        val reference = OrchestrationDecisionReference(
            decisionId = DecisionId("decision-exact"),
            generation = DecisionGeneration(17),
            selectedOptionId = DecisionOptionId("selected-exact")
        )
        val value = intent(decision = reference)

        assertEquals(reference, value.decision)
        assertEquals("decision-exact", value.decision.decisionId.value)
        assertEquals(17, value.decision.generation.value)
        assertEquals("selected-exact", value.decision.selectedOptionId.value)
    }

    @Test
    fun snapshot_order_is_deterministic() {
        val f = fixture()
        val later = intent(id = "b", createdAt = Instant.parse("2026-08-29T14:01:00Z"))
        val firstB = intent(id = "b-first", createdAt = Instant.parse("2026-08-29T14:00:00Z"))
        val firstA = intent(id = "a-first", createdAt = Instant.parse("2026-08-29T14:00:00Z"))

        assertIs<OrchestrationRegistrationResult.Registered>(f.store.register(later, f.context("later")))
        assertIs<OrchestrationRegistrationResult.Registered>(f.store.register(firstB, f.context("first-b")))
        assertIs<OrchestrationRegistrationResult.Registered>(f.store.register(firstA, f.context("first-a")))

        assertEquals(listOf("a-first", "b-first", "b"), f.store.snapshot().map { it.id.value })
    }

    @Test
    fun concurrent_same_id_has_exactly_one_winner() {
        val f = fixture()
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { index ->
                pool.submit<OrchestrationRegistrationResult> {
                    ready.countDown()
                    start.await()
                    f.store.register(
                        intent(description = "private intention $index"),
                        f.context("concurrent-$index")
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is OrchestrationRegistrationResult.Registered })
            assertEquals(workers - 1, results.count { it is OrchestrationRegistrationResult.Rejected })
            assertTrue(f.store.contains(OrchestrationIntentId("intent-1")))
        } finally {
            pool.shutdownNow()
        }
    }
}
