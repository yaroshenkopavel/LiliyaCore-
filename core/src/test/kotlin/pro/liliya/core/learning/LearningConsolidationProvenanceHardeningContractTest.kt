package pro.liliya.core.learning

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LearningConsolidationProvenanceHardeningContractTest {
    private data class Fixture(
        val learning: LearningComposition,
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "consolidation-provenance-${sequence.incrementAndGet()}" }
        )
        return Fixture(LearningComposition(foundation), logs, diagnostics)
    }

    @Test
    fun public_learning_install_rejects_direct_consolidation_origin() {
        val f = fixture()
        val candidate = LearningCandidate(
            id = LearningCandidateId("forged-consolidation-candidate"),
            origin = LearningOrigin.Consolidation(
                consolidationId = LearningConsolidationId("nonexistent-consolidation"),
                generation = LearningConsolidationGeneration(1L)
            ),
            proposal = "forged consolidation provenance",
            createdAt = Instant.parse("2026-08-29T11:30:00Z")
        )

        val rejected = assertIs<LearningInstallResult.Rejected>(f.learning.install(candidate))

        assertFalse(f.learning.contains(candidate.id))
        assertTrue(rejected.reason.contains("consolidation bridge"))
        assertTrue(f.logs.snapshot().any { it.marker == "LEARNING_CANDIDATE_CONSOLIDATION_ORIGIN_REJECTED" })
        assertTrue(f.diagnostics.snapshot().any { it.code == "LEARNING_CANDIDATE_CONSOLIDATION_ORIGIN_REJECTED" })
    }

    @Test
    fun ordinary_declared_candidate_install_remains_compatible() {
        val f = fixture()
        val candidate = LearningCandidate(
            id = LearningCandidateId("ordinary-candidate"),
            origin = LearningOrigin.Declared(LearningSourceId("test")),
            proposal = "ordinary candidate",
            createdAt = Instant.parse("2026-08-29T11:31:00Z")
        )

        assertIs<LearningInstallResult.Installed>(f.learning.install(candidate))
        assertTrue(f.learning.contains(candidate.id))
    }
}
