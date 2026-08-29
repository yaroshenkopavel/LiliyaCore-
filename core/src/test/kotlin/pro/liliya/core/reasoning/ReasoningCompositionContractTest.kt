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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: ReasoningComposition
    )

    private fun fixture(prefix: String = "reasoning"): Fixture {
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
            ReasoningSourceId("caller"),
            ReasoningSourceReference("contract")
        ),
        premises = listOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private first premise"),
            ReasoningPremise(ReasoningPremiseId("premise-2"), "private second premise")
        ),
        analysis = analysis,
        conclusion = conclusion,
        createdAt = Instant.parse("2026-08-29T12:00:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val a = artifact()
        val ownership = assertIs<ReasoningInstallResult.Installed>(f.composition.install(a)).ownership

        assertEquals(a, ownership.artifact)
        assertEquals(ownership.generation, f.composition.inspect(a.id)?.generation)
        assertEquals(a, f.composition.find(a.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(a.id))
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = artifact(analysis = "first private analysis")
        val second = artifact(analysis = "second private analysis")

        assertIs<ReasoningInstallResult.Installed>(f.composition.install(first))
        assertIs<ReasoningInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<ReasoningInstallResult.Installed>(f.composition.install(artifact())).ownership
        assertTrue(stale.remove())

        val replacement = artifact(conclusion = "replacement private conclusion")
        val current = assertIs<ReasoningInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun compositions_are_isolated() {
        val first = fixture("first")
        val second = fixture("second")
        val a = artifact()

        assertIs<ReasoningInstallResult.Installed>(first.composition.install(a))
        assertTrue(first.composition.contains(a.id))
        assertFalse(second.composition.contains(a.id))
    }

    @Test
    fun premise_analysis_and_conclusion_are_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secretPremise = "never-log-reasoning-premise"
        val secretAnalysis = "never-log-reasoning-analysis"
        val secretConclusion = "never-log-reasoning-conclusion"
        val a = ReasoningArtifact(
            id = ReasoningArtifactId("private-reasoning"),
            origin = ReasoningOrigin(ReasoningSourceId("caller")),
            premises = listOf(ReasoningPremise(ReasoningPremiseId("private-premise"), secretPremise)),
            analysis = secretAnalysis,
            conclusion = secretConclusion,
            createdAt = Instant.parse("2026-08-29T12:00:00Z")
        )

        val ownership = assertIs<ReasoningInstallResult.Installed>(f.composition.install(a)).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value ->
                value == secretPremise || value == secretAnalysis || value == secretConclusion
            }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<ReasoningInstallResult.Installed>(f.composition.install(artifact())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.message == "reasoning artifact registered" }
        val removed = f.logs.snapshot().first { it.message == "reasoning artifact removed" }

        assertEquals("installReasoningArtifact", registered.context.operation)
        assertEquals("removeReasoningArtifact", removed.context.operation)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
    }
}
