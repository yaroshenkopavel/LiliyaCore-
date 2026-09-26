package pro.liliya.core.persistence

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.cognitive.KnowledgeRelevanceCandidate
import pro.liliya.core.cognitive.MemoryRelevanceCandidate
import pro.liliya.core.cognitive.PersistentKnowledgeCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.PersistentMemoryCompositionAuthoritativeResolver
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgePersistentRecordCodec
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeInspectResult
import pro.liliya.core.knowledge.PersistentKnowledgeOpenResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryPersistentRecordCodec
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.PersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryInspectResult
import pro.liliya.core.memory.PersistentMemoryOpenResult
import pro.liliya.core.observability.LoggerProvider

class PersistentDomainExactReadContractTest {
    private class ExactReadFixtureBackend : IndexedPersistentRecordMutationBackend {
        var exactResult: PersistentBackendEntryLoadResult = PersistentBackendEntryLoadResult.Missing
        var legacyLoadCalls: Int = 0

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
            legacyLoadCalls += 1
            return PersistentBackendLoadResult.Failed("legacy load must not be used")
        }

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            PersistentBackendCommitResult.Failed("unused")

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            PersistentBackendMetadataLoadResult.Missing

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult = exactResult

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult = PersistentBackendPageLoadResult.Missing

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unused")

        override fun transitionEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            sourceId: PersistentEntityId,
            sourceGeneration: PersistentGeneration,
            replacement: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unused")

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unused")
    }

    @Test
    fun persistent_memory_exact_read_preserves_indexed_result_states_without_legacy_load() {
        val backend = ExactReadFixtureBackend()
        val composition = assertIs<PersistentMemoryOpenResult.Opened>(
            PersistentMemoryComposition.open(
                foundation(),
                PersistentStoreId("memory-exact-read"),
                backend
            )
        ).composition
        assertEquals(0, backend.legacyLoadCalls)

        val id = MemoryRecordId("memory-1")
        assertEquals(PersistentMemoryInspectResult.Missing, composition.inspectResult(id))

        val record = MemoryRecord(
            id = id,
            provenance = MemoryProvenance(MemorySourceId("conversation")),
            content = "durable memory",
            createdAt = Instant.parse("2026-09-25T10:00:00Z")
        )
        backend.exactResult = PersistentBackendEntryLoadResult.Loaded(
            PersistentRecordSnapshot(
                MemoryPersistentRecordCodec.encode(record),
                PersistentGeneration(7)
            )
        )
        val found = assertIs<PersistentMemoryInspectResult.Found>(composition.inspectResult(id))
        assertEquals(record, found.snapshot.record)
        assertEquals(MemoryGeneration(7), found.snapshot.generation)

        backend.exactResult = PersistentBackendEntryLoadResult.Corrupt
        assertEquals(PersistentMemoryInspectResult.Corrupt, composition.inspectResult(id))

        backend.exactResult = PersistentBackendEntryLoadResult.Incompatible("future memory format")
        assertEquals(
            PersistentMemoryInspectResult.Incompatible("future memory format"),
            composition.inspectResult(id)
        )

        val failure = IllegalStateException("private backend detail")
        backend.exactResult = PersistentBackendEntryLoadResult.Failed("memory exact read failed", failure)
        val failed = assertIs<PersistentMemoryInspectResult.Failed>(composition.inspectResult(id))
        assertEquals("memory exact read failed", failed.reason)
        assertEquals(failure, failed.throwable)
        assertFalse(failed.toString().contains("private backend detail"))
        assertTrue(failed.toString().contains("java.lang.IllegalStateException"))
        assertEquals(0, backend.legacyLoadCalls)
    }

    @Test
    fun persistent_knowledge_exact_read_preserves_indexed_result_states_without_legacy_load() {
        val backend = ExactReadFixtureBackend()
        val composition = assertIs<PersistentKnowledgeOpenResult.Opened>(
            PersistentKnowledgeComposition.open(
                foundation(),
                PersistentStoreId("knowledge-exact-read"),
                backend
            )
        ).composition
        assertEquals(0, backend.legacyLoadCalls)

        val id = KnowledgeItemId("knowledge-1")
        assertEquals(PersistentKnowledgeInspectResult.Missing, composition.inspectResult(id))

        val item = KnowledgeItem(
            id = id,
            origin = KnowledgeOrigin.Declared(
                pro.liliya.core.knowledge.KnowledgeSourceId("declared")
            ),
            content = "durable knowledge",
            createdAt = Instant.parse("2026-09-25T10:01:00Z")
        )
        backend.exactResult = PersistentBackendEntryLoadResult.Loaded(
            PersistentRecordSnapshot(
                KnowledgePersistentRecordCodec.encode(item),
                PersistentGeneration(11)
            )
        )
        val found = assertIs<PersistentKnowledgeInspectResult.Found>(composition.inspectResult(id))
        assertEquals(item, found.snapshot.item)
        assertEquals(KnowledgeGeneration(11), found.snapshot.generation)

        backend.exactResult = PersistentBackendEntryLoadResult.Corrupt
        assertEquals(PersistentKnowledgeInspectResult.Corrupt, composition.inspectResult(id))

        backend.exactResult = PersistentBackendEntryLoadResult.Incompatible("future knowledge format")
        assertEquals(
            PersistentKnowledgeInspectResult.Incompatible("future knowledge format"),
            composition.inspectResult(id)
        )

        val failure = IllegalStateException("private backend detail")
        backend.exactResult = PersistentBackendEntryLoadResult.Failed("knowledge exact read failed", failure)
        val failed = assertIs<PersistentKnowledgeInspectResult.Failed>(composition.inspectResult(id))
        assertEquals("knowledge exact read failed", failed.reason)
        assertEquals(failure, failed.throwable)
        assertFalse(failed.toString().contains("private backend detail"))
        assertTrue(failed.toString().contains("java.lang.IllegalStateException"))
        assertEquals(0, backend.legacyLoadCalls)
    }

    @Test
    fun persistent_authoritative_resolvers_fail_closed_on_exact_backend_failure() {
        val memoryBackend = ExactReadFixtureBackend()
        val memoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
            PersistentMemoryComposition.open(
                foundation(),
                PersistentStoreId("memory-resolver-failure"),
                memoryBackend
            )
        ).composition
        val memoryFailure = IllegalStateException("private memory backend detail")
        memoryBackend.exactResult = PersistentBackendEntryLoadResult.Failed(
            "memory exact read failed",
            memoryFailure
        )
        val memoryException = assertFailsWith<IllegalStateException> {
            PersistentMemoryCompositionAuthoritativeResolver(memoryComposition).resolveExact(
                MemoryRelevanceCandidate(
                    MemoryRecordId("memory-resolver-failure"),
                    MemoryGeneration(1)
                )
            )
        }
        assertEquals("memory exact read failed", memoryException.message)
        assertEquals(memoryFailure, memoryException.cause)

        val knowledgeBackend = ExactReadFixtureBackend()
        val knowledgeComposition = assertIs<PersistentKnowledgeOpenResult.Opened>(
            PersistentKnowledgeComposition.open(
                foundation(),
                PersistentStoreId("knowledge-resolver-failure"),
                knowledgeBackend
            )
        ).composition
        val knowledgeFailure = IllegalStateException("private knowledge backend detail")
        knowledgeBackend.exactResult = PersistentBackendEntryLoadResult.Failed(
            "knowledge exact read failed",
            knowledgeFailure
        )
        val knowledgeException = assertFailsWith<IllegalStateException> {
            PersistentKnowledgeCompositionAuthoritativeResolver(knowledgeComposition).resolveExact(
                KnowledgeRelevanceCandidate(
                    KnowledgeItemId("knowledge-resolver-failure"),
                    KnowledgeGeneration(1)
                )
            )
        }
        assertEquals("knowledge exact read failed", knowledgeException.message)
        assertEquals(knowledgeFailure, knowledgeException.cause)
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "domain-exact-read-" + sequence.incrementAndGet()
            }
        )
    }
}
