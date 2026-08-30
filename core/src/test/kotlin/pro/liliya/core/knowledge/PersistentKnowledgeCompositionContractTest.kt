package pro.liliya.core.knowledge

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentKnowledgeCompositionContractTest {
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "persistent-knowledge-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun item(
        id: String = "knowledge-1",
        content: String = "private durable knowledge",
        createdAt: Instant = Instant.parse("2026-08-30T15:20:00Z")
    ) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Memory(
            recordId = MemoryRecordId("memory-42"),
            generation = MemoryGeneration(7)
        ),
        content = content,
        createdAt = createdAt
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        storeId: String = "knowledge-store"
    ): PersistentKnowledgeComposition = assertIs<PersistentKnowledgeOpenResult.Opened>(
        PersistentKnowledgeComposition.open(
            foundation = foundation(),
            storeId = PersistentStoreId(storeId),
            backend = backend
        )
    ).composition

    @Test
    fun create_is_visible_only_after_durable_commit_and_reopen_restores_exact_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val knowledge = item()
        val first = open(backend)

        val created = assertIs<PersistentKnowledgeCreateResult.Created>(first.create(knowledge))
        assertEquals(knowledge, first.find(knowledge.id))
        assertEquals(created.ownership.generation, first.inspect(knowledge.id)?.generation)

        val reopened = open(backend)
        assertEquals(knowledge, reopened.find(knowledge.id))
        assertEquals(created.ownership.generation, reopened.inspect(knowledge.id)?.generation)
    }

    @Test
    fun failed_durable_create_keeps_knowledge_locally_absent_and_reopen_empty() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val knowledge = item()
        backend.failNextCommit()

        assertIs<PersistentKnowledgeCreateResult.Failed>(composition.create(knowledge))
        assertNull(composition.find(knowledge.id))
        assertTrue(composition.snapshot().isEmpty())

        val reopened = open(backend)
        assertFalse(reopened.contains(knowledge.id))
        assertTrue(reopened.snapshot().isEmpty())
    }

    @Test
    fun durable_remove_commits_before_local_exact_removal() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val knowledge = item()
        val ownership = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(knowledge)
        ).ownership

        assertIs<PersistentKnowledgeMutationResult.Committed>(ownership.remove())
        assertFalse(composition.contains(knowledge.id))
        assertFalse(open(backend).contains(knowledge.id))
    }

    @Test
    fun failed_durable_remove_keeps_local_and_reopened_knowledge_live() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val knowledge = item()
        val ownership = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(knowledge)
        ).ownership
        backend.failNextCommit()

        assertIs<PersistentKnowledgeMutationResult.Failed>(ownership.remove())
        assertEquals(knowledge, composition.find(knowledge.id))

        val reopened = open(backend)
        assertEquals(knowledge, reopened.find(knowledge.id))
        assertEquals(ownership.generation, reopened.inspect(knowledge.id)?.generation)
    }

    @Test
    fun stale_owner_cannot_remove_newer_persisted_replacement_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val firstItem = item(content = "first private knowledge")
        val first = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(firstItem)
        ).ownership
        assertIs<PersistentKnowledgeMutationResult.Committed>(first.remove())

        val replacementItem = item(content = "replacement private knowledge")
        val replacement = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(replacementItem)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<PersistentKnowledgeMutationResult.Rejected>(first.remove())
        assertEquals(replacementItem, composition.find(replacementItem.id))
        assertEquals(replacement.generation, composition.inspect(replacementItem.id)?.generation)

        val reopened = open(backend)
        assertEquals(replacementItem, reopened.find(replacementItem.id))
        assertEquals(replacement.generation, reopened.inspect(replacementItem.id)?.generation)
    }
}
