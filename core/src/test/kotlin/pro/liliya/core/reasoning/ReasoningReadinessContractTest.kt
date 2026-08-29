package pro.liliya.core.reasoning

import java.time.Instant
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ReasoningReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: ReasoningComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, ReasoningComposition(foundation))
    }

    private fun artifact(
        id: String = "reasoning-1",
        analysis: String = "private analysis",
        conclusion: String = "private conclusion"
    ) = ReasoningArtifact(
        id = ReasoningArtifactId(id),
        origin = ReasoningOrigin(
            sourceId = ReasoningSourceId("caller"),
            sourceReference = ReasoningSourceReference("readiness")
        ),
        premises = listOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private reasoning premise")
        ),
        analysis = analysis,
        conclusion = conclusion,
        createdAt = Instant.parse("2026-08-29T13:00:00Z")
    )

    @Test
    fun ownership_remove_is_one_shot_and_repeated_remove_fails_closed() {
        val f = fixture("one-shot")
        val ownership = assertIs<ReasoningInstallResult.Installed>(
            f.composition.install(artifact())
        ).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertFalse(f.composition.contains(ownership.artifact.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val a = artifact()

        val firstOwnership = assertIs<ReasoningInstallResult.Installed>(first.composition.install(a)).ownership
        val secondOwnership = assertIs<ReasoningInstallResult.Installed>(second.composition.install(a)).ownership

        assertNotSame(firstOwnership, secondOwnership)
        assertTrue(first.composition.contains(a.id))
        assertTrue(second.composition.contains(a.id))
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(a.id))
        assertTrue(second.composition.contains(a.id))
    }

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val f = fixture("snapshot")
        val first = artifact(id = "reasoning-a")
        val second = artifact(id = "reasoning-b")

        assertIs<ReasoningInstallResult.Installed>(f.composition.install(first))
        val snapshotBefore = f.composition.snapshot()
        assertEquals(listOf("reasoning-a"), snapshotBefore.map { it.id.value })

        assertIs<ReasoningInstallResult.Installed>(f.composition.install(second))
        assertEquals(listOf("reasoning-a"), snapshotBefore.map { it.id.value })
        assertEquals(listOf("reasoning-a", "reasoning-b"), f.composition.snapshot().map { it.id.value })
    }

    @Test
    fun reasoning_lifecycle_observability_contains_no_decision_authority_or_execution_semantics() {
        val f = fixture("semantics")
        val secretAnalysis = "never-observe-this-analysis"
        val secretConclusion = "never-observe-this-conclusion"
        val ownership = assertIs<ReasoningInstallResult.Installed>(
            f.composition.install(
                artifact(
                    analysis = secretAnalysis,
                    conclusion = secretConclusion
                )
            )
        ).ownership
        ownership.remove()

        val forbiddenTokens = listOf(
            "decision",
            "approved",
            "approval",
            "authority",
            "authorized",
            "capability",
            "execution",
            "execute",
            "executed",
            "truth",
            "confidence",
            "trusted"
        )

        f.logs.snapshot().forEach { event ->
            assertFalse(event.metadata.values.any { it == secretAnalysis || it == secretConclusion })
            val keys = event.metadata.keys.map { it.lowercase() }
            assertFalse(keys.any { key -> forbiddenTokens.any { token -> key.contains(token) } })
        }
    }
}
