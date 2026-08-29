package pro.liliya.core.decision

import java.time.Instant
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: DecisionComposition
    )

    private fun fixture(prefix: String = "decision"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, DecisionComposition(foundation))
    }

    private fun decision(
        id: String = "decision-1",
        rationale: String = "private rationale",
        optionDescription: String = "private option"
    ) = DecisionRecord(
        id = DecisionId(id),
        inputs = listOf(
            DecisionInputReference.Planning(PlanningProposalId("plan-1"), PlanningGeneration(3)),
            DecisionInputReference.Reasoning(ReasoningArtifactId("reason-1"), ReasoningGeneration(7))
        ),
        options = listOf(
            DecisionOption(DecisionOptionId("option-a"), optionDescription),
            DecisionOption(DecisionOptionId("option-b"), "private alternative")
        ),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = rationale,
        createdAt = Instant.parse("2026-08-29T13:00:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val d = decision()
        val ownership = assertIs<DecisionInstallResult.Installed>(f.composition.install(d)).ownership

        assertEquals(d, ownership.decision)
        assertEquals(ownership.generation, f.composition.inspect(d.id)?.generation)
        assertEquals(d, f.composition.find(d.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(d.id))
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = decision(rationale = "first private rationale")
        val second = decision(rationale = "second private rationale")

        assertIs<DecisionInstallResult.Installed>(f.composition.install(first))
        assertIs<DecisionInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<DecisionInstallResult.Installed>(f.composition.install(decision())).ownership
        assertTrue(stale.remove())

        val replacement = decision(rationale = "replacement private rationale")
        val current = assertIs<DecisionInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val d = decision()

        val firstOwnership = assertIs<DecisionInstallResult.Installed>(first.composition.install(d)).ownership
        val secondOwnership = assertIs<DecisionInstallResult.Installed>(second.composition.install(d)).ownership

        assertTrue(first.composition.contains(d.id))
        assertTrue(second.composition.contains(d.id))
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(d.id))
        assertTrue(second.composition.contains(d.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun option_description_and_rationale_are_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secretOption = "never-log-decision-option"
        val secretRationale = "never-log-decision-rationale"
        val d = decision(optionDescription = secretOption, rationale = secretRationale)

        val ownership = assertIs<DecisionInstallResult.Installed>(f.composition.install(d)).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value -> value == secretOption || value == secretRationale }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<DecisionInstallResult.Installed>(f.composition.install(decision())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.message == "decision registered" }
        val removed = f.logs.snapshot().first { it.message == "decision removed" }

        assertEquals("installDecision", registered.context.operation)
        assertEquals("removeDecision", removed.context.operation)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
    }
}
