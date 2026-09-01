package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider

class CognitiveAuthoritativeRetrievalPortsContractTest {
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "retrieval-${sequence.incrementAndGet()}" }
        )
    }

    private fun turn() = CognitiveTurnReference(CognitiveTurnId("retrieval-turn"), CognitiveTurnGeneration(1))

    @Test
    fun memory_adapter_returns_bounded_recent_window_in_authoritative_order() {
        val memory = MemoryComposition(foundation())
        listOf("a", "b", "c").forEachIndexed { index, id ->
            memory.remember(
                MemoryRecord(
                    id = MemoryRecordId("memory-$id"),
                    provenance = MemoryProvenance(MemorySourceId("test")),
                    content = "content-$id",
                    createdAt = Instant.parse("2026-09-01T00:00:0${index + 1}Z")
                )
            )
        }

        val result = MemoryCompositionRetrievalPort(memory).retrieve(
            MemoryRetrievalRequest(turn(), CognitiveInput("input"), maxResults = 2)
        )

        assertEquals(listOf("memory-b", "memory-c"), result.items.map { it.record.id.value })
        assertEquals(listOf(2L, 3L), result.items.map { it.generation.value })
    }

    @Test
    fun knowledge_adapter_returns_bounded_recent_window_in_authoritative_order() {
        val knowledge = KnowledgeComposition(foundation())
        listOf("a", "b", "c").forEachIndexed { index, id ->
            knowledge.create(
                KnowledgeItem(
                    id = KnowledgeItemId("knowledge-$id"),
                    origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
                    content = "content-$id",
                    createdAt = Instant.parse("2026-09-01T00:00:0${index + 1}Z")
                )
            )
        }

        val result = KnowledgeCompositionRetrievalPort(knowledge).retrieve(
            KnowledgeRetrievalRequest(turn(), CognitiveInput("input"), maxResults = 2)
        )

        assertEquals(listOf("knowledge-b", "knowledge-c"), result.items.map { it.item.id.value })
        assertEquals(listOf(2L, 3L), result.items.map { it.generation.value })
    }

    @Test
    fun adapters_return_snapshot_values_not_live_mutation_handles() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        memory.remember(
            MemoryRecord(
                id = MemoryRecordId("memory-one"),
                provenance = MemoryProvenance(MemorySourceId("test")),
                content = "memory-content",
                createdAt = Instant.parse("2026-09-01T00:00:01Z")
            )
        )
        knowledge.create(
            KnowledgeItem(
                id = KnowledgeItemId("knowledge-one"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
                content = "knowledge-content",
                createdAt = Instant.parse("2026-09-01T00:00:01Z")
            )
        )

        val memoryResult = MemoryCompositionRetrievalPort(memory).retrieve(
            MemoryRetrievalRequest(turn(), CognitiveInput("input"), maxResults = 1)
        )
        val knowledgeResult = KnowledgeCompositionRetrievalPort(knowledge).retrieve(
            KnowledgeRetrievalRequest(turn(), CognitiveInput("input"), maxResults = 1)
        )

        assertEquals("memory-content", memoryResult.items.single().record.content)
        assertEquals("knowledge-content", knowledgeResult.items.single().item.content)
        assertEquals(1, memory.snapshotEntries().size)
        assertEquals(1, knowledge.snapshotEntries().size)
    }
}
