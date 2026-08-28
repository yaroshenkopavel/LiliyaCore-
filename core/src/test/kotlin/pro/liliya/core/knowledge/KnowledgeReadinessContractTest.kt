package pro.liliya.core.knowledge

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KnowledgeReadinessContractTest {
    private fun composition(): KnowledgeComposition {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "knowledge-readiness-${sequence.incrementAndGet()}"
            }
        )
        return KnowledgeComposition(foundation)
    }

    @Test
    fun memory_origin_is_structural_reference_and_does_not_require_memory_lookup() {
        val knowledge = KnowledgeItem(
            id = KnowledgeItemId("knowledge-unverified-memory-origin"),
            origin = KnowledgeOrigin.Memory(
                recordId = MemoryRecordId("memory-not-present-in-any-composition"),
                generation = MemoryGeneration(999L)
            ),
            content = "structural reference only",
            createdAt = Instant.parse("2042-01-02T03:04:05Z")
        )
        val composition = composition()

        assertIs<KnowledgeCreateResult.Created>(composition.create(knowledge))
        assertEquals(knowledge, composition.find(knowledge.id))
    }

    @Test
    fun created_at_remains_caller_supplied_ordering_value() {
        val composition = composition()
        val later = KnowledgeItem(
            id = KnowledgeItemId("knowledge-later"),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("caller")),
            content = "later caller timestamp",
            createdAt = Instant.parse("2099-12-31T23:59:59Z")
        )
        val earlier = KnowledgeItem(
            id = KnowledgeItemId("knowledge-earlier"),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("caller")),
            content = "earlier caller timestamp",
            createdAt = Instant.parse("2001-01-01T00:00:00Z")
        )

        assertIs<KnowledgeCreateResult.Created>(composition.create(later))
        assertIs<KnowledgeCreateResult.Created>(composition.create(earlier))

        assertEquals(
            listOf("knowledge-earlier", "knowledge-later"),
            composition.snapshot().map { it.id.value }
        )
        assertEquals(
            Instant.parse("2001-01-01T00:00:00Z"),
            composition.find(earlier.id)?.createdAt
        )
        assertEquals(
            Instant.parse("2099-12-31T23:59:59Z"),
            composition.find(later.id)?.createdAt
        )
    }
}
