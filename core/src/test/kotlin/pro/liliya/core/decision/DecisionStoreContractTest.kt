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

class DecisionStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: DecisionStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "decision-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, DecisionStore(foundation.observability))
    }

    private fun reasoningInput() = DecisionInputReference.Reasoning(
        artifactId = ReasoningArtifactId("reason-1"),
        generation = ReasoningGeneration(7)
    )

    private fun planningInput() = DecisionInputReference.Planning(
        proposalId = PlanningProposalId("plan-1"),
        generation = PlanningGeneration(3)
    )

    private fun decision(
        id: String = "decision-1",
        inputs: List<DecisionInputReference> = listOf(reasoningInput(), planningInput()),
        options: List<DecisionOption> = listOf(
            DecisionOption(DecisionOptionId("option-a"), "private option A"),
            DecisionOption(DecisionOptionId("option-b"), "private option B")
        ),
        selectedOptionId: DecisionOptionId = DecisionOptionId("option-a"),
        rationale: String = "private rationale",
        createdAt: Instant = Instant.parse("2026-08-29T13:00:00Z")
    ) = DecisionRecord(
        id = DecisionId(id),
        inputs = inputs,
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
        val registration = assertIs<DecisionRegistrationResult.Registered>(
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

        assertIs<DecisionRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<DecisionRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<DecisionRegistrationResult.Registered>(
            f.store.register(decision(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = decision(rationale = "replacement private rationale")
        val current = assertIs<DecisionRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun caller_inputs_and_options_are_defensively_copied() {
        val inputs = mutableListOf<DecisionInputReference>(reasoningInput())
        val options = mutableListOf(DecisionOption(DecisionOptionId("option-a"), "private option"))
        val d = decision(inputs = inputs, options = options)

        inputs += planningInput()
        options += DecisionOption(DecisionOptionId("option-b"), "late option")

        assertEquals(1, d.inputs.size)
        assertEquals(listOf("option-a"), d.options.map { it.id.value })
    }

    @Test
    fun duplicate_exact_inputs_are_rejected() {
        val input = reasoningInput()
        assertFailsWith<IllegalArgumentException> {
            decision(inputs = listOf(input, input))
        }
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
    fun selected_option_must_exist_in_options() {
        assertFailsWith<IllegalArgumentException> {
            decision(selectedOptionId = DecisionOptionId("missing"))
        }
    }

    @Test
    fun option_descriptions_and_rationale_are_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secretOption = "never-log-decision-option"
        val secretRationale = "never-log-decision-rationale"
        val d = decision(
            options = listOf(DecisionOption(DecisionOptionId("option-a"), secretOption)),
            selectedOptionId = DecisionOptionId("option-a"),
            rationale = secretRationale
        )

        assertFalse(d.toString().contains(secretOption))
        assertFalse(d.toString().contains(secretRationale))
        assertFalse(d.options.single().toString().contains(secretOption))
        assertIs<DecisionRegistrationResult.Registered>(f.store.register(d, f.context("privacy")))
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value -> value == secretOption || value == secretRationale }
        })
    }

    @Test
    fun snapshot_order_is_deterministic() {
        val f = fixture()
        val later = decision(id = "b", createdAt = Instant.parse("2026-08-29T13:01:00Z"))
        val firstB = decision(id = "b-first", createdAt = Instant.parse("2026-08-29T13:00:00Z"))
        val firstA = decision(id = "a-first", createdAt = Instant.parse("2026-08-29T13:00:00Z"))

        assertIs<DecisionRegistrationResult.Registered>(f.store.register(later, f.context("later")))
        assertIs<DecisionRegistrationResult.Registered>(f.store.register(firstB, f.context("first-b")))
        assertIs<DecisionRegistrationResult.Registered>(f.store.register(firstA, f.context("first-a")))

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
                pool.submit<DecisionRegistrationResult> {
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
            assertEquals(1, results.count { it is DecisionRegistrationResult.Registered })
            assertEquals(workers - 1, results.count { it is DecisionRegistrationResult.Rejected })
            assertTrue(f.store.contains(DecisionId("decision-1")))
        } finally {
            pool.shutdownNow()
        }
    }
}
