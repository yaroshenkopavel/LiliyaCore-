package pro.liliya.core.knowledge

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

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

    @Test
    fun concurrent_distinct_creates_on_one_composition_commit_without_local_generation_races() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val attempts = 16
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val created = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        if (
                            composition.create(
                                item(
                                    id = "knowledge-$index",
                                    content = "private-$index",
                                    createdAt = Instant.parse("2026-08-30T15:20:00Z")
                                        .plusSeconds(index.toLong())
                                )
                            ) is PersistentKnowledgeCreateResult.Created
                        ) {
                            created.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(attempts, created.get())
            assertEquals(attempts, composition.snapshotEntries().size)
            assertEquals(
                (1L..attempts.toLong()).toSet(),
                composition.snapshotEntries().map { it.generation.value }.toSet()
            )

            val reopened = open(backend)
            assertEquals(composition.snapshotEntries(), reopened.snapshotEntries())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun shared_backend_stale_composition_conflict_does_not_publish_knowledge_locally() {
        val backend = InMemoryPersistentRecordBackend()
        val first = open(backend)
        val stale = open(backend)
        val committed = item(id = "committed")
        val rejected = item(id = "rejected")

        assertIs<PersistentKnowledgeCreateResult.Created>(first.create(committed))
        assertIs<PersistentKnowledgeCreateResult.Rejected>(stale.create(rejected))
        assertFalse(stale.contains(rejected.id))

        val reopened = open(backend)
        assertEquals(committed, reopened.find(committed.id))
        assertFalse(reopened.contains(rejected.id))
    }

    @Test
    fun corrupt_payload_after_valid_item_fails_reopen_without_partial_composition() {
        val backend = InMemoryPersistentRecordBackend()
        val valid = open(backend)
        val validItem = item(id = "valid")
        assertIs<PersistentKnowledgeCreateResult.Created>(valid.create(validItem))

        val rawStore = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(
                foundation = foundation(),
                storeId = PersistentStoreId("knowledge-store"),
                backend = backend
            )
        ).store
        assertIs<PersistentInstallResult.Installed>(
            rawStore.install(
                PersistentRecord(
                    id = PersistentEntityId("corrupt"),
                    schemaId = PersistentSchemaId("knowledge-item"),
                    schemaVersion = PersistentSchemaVersion(1),
                    payload = PersistentPayload(byteArrayOf(1, 2, 3)),
                    createdAt = Instant.parse("2026-08-30T15:21:00Z")
                )
            )
        )

        assertIs<PersistentKnowledgeOpenResult.Corrupt>(
            PersistentKnowledgeComposition.open(
                foundation = foundation(),
                storeId = PersistentStoreId("knowledge-store"),
                backend = backend
            )
        )
    }

    @Test
    fun failure_rendering_redacts_backend_throwable_message_and_knowledge_content() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val privateContent = "ultra-private-knowledge-content"
        backend.failNextCommit(IllegalStateException("backend leaked $privateContent"))

        val failed = assertIs<PersistentKnowledgeCreateResult.Failed>(
            composition.create(item(content = privateContent))
        )
        val rendered = failed.toString()

        assertFalse(rendered.contains(privateContent))
        assertFalse(rendered.contains("backend leaked"))
        assertTrue(rendered.contains("java.lang.IllegalStateException"))
        assertEquals("persistent knowledge durable install failed", failed.reason)
    }
}
