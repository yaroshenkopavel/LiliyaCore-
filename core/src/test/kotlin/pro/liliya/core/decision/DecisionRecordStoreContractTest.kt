package pro.liliya.core.decision

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
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionRecordStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: DecisionRecordStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "decision-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, DecisionRecordStore(foundation.observability))
    }

    private fun decision(
        id: String = "decision-1",
        rationale: String = "private rationale",
        options: List<DecisionOption> = listOf(
            DecisionOption(DecisionOptionId("option-a"), "private option A"),
            DecisionOption(DecisionOptionId("option-b"), "private option B")
        ),
        selectedOptionId: DecisionOptionId = DecisionOptionId("option-a"),
        createdAt: Instant = Instant.parse("2026-08-29T14:00:00Z")
    ) = DecisionRecord(
        id = DecisionId(id),
        inputs = listOf(
            DecisionInputReference.Planning(PlanningProposalId("plan-1"), PlanningGeneration(1)),
            DecisionInputReference.Reasoning(ReasoningArtifactId("reason-1"), ReasoningGeneration(1))
        ),
        options = options,
        selectedOptionId = selectedOptionId,
        rationale = rationale,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "Decision")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val d = decision()
        val registration = assertIs<DecisionRecordRegistrationResult.Registered>(
            f.store.register(d, f.context("register"))
        ).registration

        assertEquals(d, f.store.find(d.id))
        assertEquals(registration.generation, f.store.inspect(d.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(d.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = decision(rationale = "first private rationale")
        val second = decision(rationale = "second private rationale")

        assertIs<DecisionRecordRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<DecisionRecordRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<DecisionRecordRegistrationResult.Registered>(
            f.store.register(decision(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = decision(rationale = "replacement private rationale")
        val current = assertIs<DecisionRecordRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun caller_option_list_is_defensively_copied() {
        val mutable = mutableListOf(
            DecisionOption(DecisionOptionId("option-a"), "private option A")
        )
        val d = decision(options = mutable, selectedOptionId = DecisionOptionId("option-a"))
        mutable += DecisionOption(DecisionOptionId("option-b"), "late mutation")
        assertEquals(listOf("option-a"), d.options.map { it.id.value })
    }

    @Test
    fun duplicate_option_ids_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            decision(
                options = listOf(
                    DecisionOption(DecisionOptionId("same"), "one"),
                    DecisionOption(DecisionOptionId("same"), "two")
                ),
                selectedOptionId = DecisionOptionId("same")
            )
        }
    }

    @Test
    fun selected_option_must_exist() {
        assertFailsWith<IllegalArgumentException> {
            decision(selectedOptionId = DecisionOptionId("missing"))
        }
    }

    @Test
    fun option_and_rationale_payloads_are_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secretOption = "never-log-decision-option"
        val secretRationale = "never-log-decision-rationale"
        val d = decision(
            options = listOf(DecisionOption(DecisionOptionId("private"), secretOption)),
            selectedOptionId = DecisionOptionId("private"),
            rationale = secretRationale
        )

        assertFalse(d.toString().contains(secretOption))
        assertFalse(d.toString().contains(secretRationale))
        assertFalse(d.options.single().toString().contains(secretOption))
        assertIs<DecisionRecordRegistrationResult.Registered>(f.store.register(d, f.context("privacy")))
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value -> value == secretOption || value == secretRationale }
        })
    }

    @Test
    fun snapshot_order_is_deterministic() {
        val f = fixture()
        val later = decision(id = "b", createdAt = Instant.parse("2026-08-29T14:01:00Z"))
        val firstB = decision(id = "b-first", createdAt = Instant.parse("2026-08-29T14:00:00Z"))
        val firstA = decision(id = "a-first", createdAt = Instant.parse("2026-08-29T14:00:00Z"))

        assertIs<DecisionRecordRegistrationResult.Registered>(f.store.register(later, f.context("later")))
        assertIs<DecisionRecordRegistrationResult.Registered>(f.store.register(firstB, f.context("first-b")))
        assertIs<DecisionRecordRegistrationResult.Registered>(f.store.register(firstA, f.context("first-a")))

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
                pool.submit<DecisionRecordRegistrationResult> {
                    ready.countDown()
                    start.await()
                    f.store.register(
                        decision(rationale = "private rationale $index"),
                        f.context("concurrent-$index")
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is DecisionRecordRegistrationResult.Registered })
            assertEquals(workers - 1, results.count { it is DecisionRecordRegistrationResult.Rejected })
            assertTrue(f.store.contains(DecisionId("decision-1")))
        } finally {
            pool.shutdownNow()
        }
    }
}
