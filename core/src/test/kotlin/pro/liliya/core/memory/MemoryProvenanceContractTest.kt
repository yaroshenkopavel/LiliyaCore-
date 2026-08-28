package pro.liliya.core.memory

import java.time.Instant
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryProvenanceContractTest {
    private fun composition(): MemoryComposition {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "memory-provenance" }
        )
        return MemoryComposition(foundation)
    }

    @Test
    fun provenance_is_structural_and_preserved_through_composition() {
        val provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("message-42")
        )
        val record = MemoryRecord(
            id = MemoryRecordId("memory-1"),
            provenance = provenance,
            content = "user prefers concise status updates",
            createdAt = Instant.parse("2026-08-28T19:00:00Z")
        )
        val memory = composition()

        val remembered = memory.remember(record) as MemoryRememberResult.Remembered

        assertEquals(provenance, remembered.ownership.record.provenance)
        assertEquals(provenance, memory.find(record.id)?.provenance)
        assertEquals(MemorySourceId("conversation"), record.sourceId)
    }

    @Test
    fun legacy_source_id_constructor_preserves_compatibility_without_inventing_reference() {
        val record = MemoryRecord(
            id = MemoryRecordId("memory-legacy"),
            sourceId = MemorySourceId("import"),
            content = "legacy-compatible memory",
            createdAt = Instant.parse("2026-08-28T19:01:00Z")
        )

        assertEquals(MemorySourceId("import"), record.provenance.sourceId)
        assertNull(record.provenance.sourceReference)
    }

    @Test
    fun blank_source_reference_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            MemorySourceReference("   ")
        }
    }

    @Test
    fun provenance_surface_contains_origin_only_not_trust_or_authority_semantics() {
        val forbiddenFragments = listOf(
            "trust",
            "confidence",
            "truth",
            "authority",
            "permission",
            "verified"
        )
        val publicMethodNames = MemoryProvenance::class.java.methods
            .map { it.name.lowercase() }

        assertTrue(
            forbiddenFragments.none { fragment ->
                publicMethodNames.any { methodName -> fragment in methodName }
            }
        )
    }
}
