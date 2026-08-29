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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DecisionReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: DecisionComposition
    )

    private fun fixture(prefix: String): Fixture {
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
        optionDescription: String = "private option description"
    ) = DecisionRecord(
        id = DecisionId(id),
        inputs = listOf(
            DecisionInputReference.Planning(
                proposalId = PlanningProposalId("plan-1"),
                generation = PlanningGeneration(3)
            ),
            DecisionInputReference.Reasoning(
                artifactId = ReasoningArtifactId("reason-1"),
                generation = ReasoningGeneration(7)
            )
        ),
        options = listOf(
            DecisionOption(DecisionOptionId("option-a"), optionDescription),
            DecisionOption(DecisionOptionId("option-b"), "private alternative")
        ),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = rationale,
        createdAt = Instant.parse("2026-08-29T13:30:00Z")
    )

    @Test
    fun ownership_remove_is_one_shot_and_repeated_remove_fails_closed() {
        val f = fixture("one-shot")
        val ownership = assertIs<DecisionInstallResult.Installed>(
            f.composition.install(decision())
        ).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertFalse(f.composition.contains(ownership.decision.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val d = decision()

        val firstOwnership = assertIs<DecisionInstallResult.Installed>(first.composition.install(d)).ownership
        val secondOwnership = assertIs<DecisionInstallResult.Installed>(second.composition.install(d)).ownership

        assertNotSame(firstOwnership, secondOwnership)
        assertTrue(first.composition.contains(d.id))
        assertTrue(second.composition.contains(d.id))
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(d.id))
        assertTrue(second.composition.contains(d.id))
    }

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val f = fixture("snapshot")
        val first = decision(id = "decision-a")
        val second = decision(id = "decision-b")

        assertIs<DecisionInstallResult.Installed>(f.composition.install(first))
        val snapshotBefore = f.composition.snapshot()
        assertEquals(listOf("decision-a"), snapshotBefore.map { it.id.value })

        assertIs<DecisionInstallResult.Installed>(f.composition.install(second))
        assertEquals(listOf("decision-a"), snapshotBefore.map { it.id.value })
        assertEquals(listOf("decision-a", "decision-b"), f.composition.snapshot().map { it.id.value })
    }

    @Test
    fun decision_lifecycle_observability_contains_no_authority_capability_or_execution_semantics() {
        val f = fixture("semantics")
        val secretOption = "never-observe-this-option"
        val secretRationale = "never-observe-this-rationale"
        val ownership = assertIs<DecisionInstallResult.Installed>(
            f.composition.install(
                decision(
                    optionDescription = secretOption,
                    rationale = secretRationale
                )
            )
        ).ownership
        ownership.remove()

        val forbiddenTokens = listOf(
            "approved",
            "approval",
            "authority",
            "authorized",
            "capability",
            "permission",
            "execution",
            "execute",
            "executed",
            "executor",
            "scheduled",
            "truth",
            "confidence",
            "trusted"
        )

        f.logs.snapshot().forEach { event ->
            assertFalse(event.metadata.values.any { it == secretOption || it == secretRationale })
            val keys = event.metadata.keys.map { it.lowercase() }
            assertFalse(keys.any { key -> forbiddenTokens.any { token -> key.contains(token) } })
        }
    }
}
