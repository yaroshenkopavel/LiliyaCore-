package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeCreateResult
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.knowledge.PersistentKnowledgeMutationResult
import pro.liliya.core.knowledge.PersistentKnowledgeOpenResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRememberResult
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference
import pro.liliya.core.memory.PersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryMutationResult
import pro.liliya.core.memory.PersistentMemoryOpenResult
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class CognitiveRelevanceAuthoritativeResolverContractTest {
    @Test
    fun discovery_contracts_redact_private_query_and_candidate_ids_and_copy_provider_lists() {
        val turn = turn()
        val privateInput = CognitiveInput("private semantic query")
        val memoryCandidate = MemoryRelevanceCandidate(
            MemoryRecordId("private-memory-id"),
            MemoryGeneration(7)
        )
        val knowledgeCandidate = KnowledgeRelevanceCandidate(
            KnowledgeItemId("private-knowledge-id"),
            KnowledgeGeneration(9)
        )
        val mutableMemory = mutableListOf(memoryCandidate)
        val mutableKnowledge = mutableListOf(knowledgeCandidate)
        val memoryResult = MemoryRelevanceDiscoveryResult(mutableMemory)
        val knowledgeResult = KnowledgeRelevanceDiscoveryResult(mutableKnowledge)
        mutableMemory.clear()
        mutableKnowledge.clear()

        assertEquals(listOf(memoryCandidate), memoryResult.candidates)
        assertEquals(listOf(knowledgeCandidate), knowledgeResult.candidates)
        assertFalse(memoryCandidate.toString().contains("private-memory-id"))
        assertFalse(knowledgeCandidate.toString().contains("private-knowledge-id"))
        assertFalse(memoryResult.toString().contains("private-memory-id"))
        assertFalse(knowledgeResult.toString().contains("private-knowledge-id"))

        val memoryRequest = MemoryRelevanceDiscoveryRequest(turn, privateInput, 4)
        val knowledgeRequest = KnowledgeRelevanceDiscoveryRequest(turn, privateInput, 4)
        assertFalse(memoryRequest.toString().contains("private semantic query"))
        assertFalse(knowledgeRequest.toString().contains("private semantic query"))
        assertTrue(memoryRequest.toString().contains("maxCandidates=4"))
        assertTrue(knowledgeRequest.toString().contains("maxCandidates=4"))
    }

    @Test
    fun in_memory_memory_resolver_requires_exact_current_generation_and_rejects_aba() {
        val memory = MemoryComposition(foundation("memory-resolver"))
        val firstRecord = memoryRecord("same-memory", "first private memory")
        val first = assertIs<MemoryRememberResult.Remembered>(memory.remember(firstRecord)).ownership
        val resolver = MemoryCompositionAuthoritativeResolver(memory)
        val firstCandidate = MemoryRelevanceCandidate(first.record.id, first.generation)

        val resolved = assertIs<MemoryAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(firstCandidate)
        )
        assertEquals(firstRecord, resolved.snapshot.record)
        assertEquals(first.generation, resolved.snapshot.generation)

        assertTrue(first.remove())
        assertIs<MemoryAuthoritativeResolutionResult.Stale>(resolver.resolveExact(firstCandidate))

        val replacementRecord = memoryRecord("same-memory", "replacement private memory")
        val replacement = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(replacementRecord)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<MemoryAuthoritativeResolutionResult.Stale>(resolver.resolveExact(firstCandidate))
        val replacementResolved = assertIs<MemoryAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(
                MemoryRelevanceCandidate(replacement.record.id, replacement.generation)
            )
        )
        assertEquals(replacementRecord, replacementResolved.snapshot.record)
    }

    @Test
    fun in_memory_knowledge_resolver_requires_exact_current_generation_and_rejects_aba() {
        val knowledge = KnowledgeComposition(foundation("knowledge-resolver"))
        val firstItem = knowledgeItem("same-knowledge", "first private knowledge")
        val first = assertIs<KnowledgeCreateResult.Created>(knowledge.create(firstItem)).ownership
        val resolver = KnowledgeCompositionAuthoritativeResolver(knowledge)
        val firstCandidate = KnowledgeRelevanceCandidate(first.item.id, first.generation)

        val resolved = assertIs<KnowledgeAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(firstCandidate)
        )
        assertEquals(firstItem, resolved.snapshot.item)
        assertEquals(first.generation, resolved.snapshot.generation)

        assertTrue(first.remove())
        assertIs<KnowledgeAuthoritativeResolutionResult.Stale>(resolver.resolveExact(firstCandidate))

        val replacementItem = knowledgeItem("same-knowledge", "replacement private knowledge")
        val replacement = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(replacementItem)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<KnowledgeAuthoritativeResolutionResult.Stale>(resolver.resolveExact(firstCandidate))
        val replacementResolved = assertIs<KnowledgeAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(
                KnowledgeRelevanceCandidate(replacement.item.id, replacement.generation)
            )
        )
        assertEquals(replacementItem, replacementResolved.snapshot.item)
    }

    @Test
    fun persistent_memory_resolver_uses_composition_authority_and_preserves_exact_generation_across_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val firstComposition = openPersistentMemory(backend)
        val record = memoryRecord("persistent-memory", "private durable memory")
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            firstComposition.remember(record)
        ).ownership

        val reopened = openPersistentMemory(backend)
        val resolver = PersistentMemoryCompositionAuthoritativeResolver(reopened)
        val candidate = MemoryRelevanceCandidate(record.id, remembered.generation)
        val resolved = assertIs<MemoryAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(candidate)
        )
        assertEquals(record, resolved.snapshot.record)
        assertEquals(remembered.generation, resolved.snapshot.generation)

        val liveOwner = assertIs<PersistentMemoryRememberResult.Rejected>(
            reopened.remember(record)
        )
        assertTrue(liveOwner.reason.isNotBlank())
    }

    @Test
    fun persistent_memory_resolver_rejects_removed_and_newer_same_id_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = openPersistentMemory(backend)
        val firstRecord = memoryRecord("persistent-aba-memory", "first durable memory")
        val first = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(firstRecord)
        ).ownership
        val staleCandidate = MemoryRelevanceCandidate(first.record.id, first.generation)
        assertIs<PersistentMemoryMutationResult.Committed>(first.remove())

        val replacementRecord = memoryRecord("persistent-aba-memory", "replacement durable memory")
        val replacement = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(replacementRecord)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        val resolver = PersistentMemoryCompositionAuthoritativeResolver(composition)
        assertIs<MemoryAuthoritativeResolutionResult.Stale>(resolver.resolveExact(staleCandidate))
        assertIs<MemoryAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(
                MemoryRelevanceCandidate(replacement.record.id, replacement.generation)
            )
        )
    }

    @Test
    fun persistent_knowledge_resolver_uses_composition_authority_and_preserves_exact_generation_across_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val firstComposition = openPersistentKnowledge(backend)
        val item = knowledgeItem("persistent-knowledge", "private durable knowledge")
        val created = assertIs<PersistentKnowledgeCreateResult.Created>(
            firstComposition.create(item)
        ).ownership

        val reopened = openPersistentKnowledge(backend)
        val resolver = PersistentKnowledgeCompositionAuthoritativeResolver(reopened)
        val candidate = KnowledgeRelevanceCandidate(item.id, created.generation)
        val resolved = assertIs<KnowledgeAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(candidate)
        )
        assertEquals(item, resolved.snapshot.item)
        assertEquals(created.generation, resolved.snapshot.generation)
    }

    @Test
    fun persistent_knowledge_resolver_rejects_removed_and_newer_same_id_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = openPersistentKnowledge(backend)
        val firstItem = knowledgeItem("persistent-aba-knowledge", "first durable knowledge")
        val first = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(firstItem)
        ).ownership
        val staleCandidate = KnowledgeRelevanceCandidate(first.item.id, first.generation)
        assertIs<PersistentKnowledgeMutationResult.Committed>(first.remove())

        val replacementItem = knowledgeItem(
            "persistent-aba-knowledge",
            "replacement durable knowledge"
        )
        val replacement = assertIs<PersistentKnowledgeCreateResult.Created>(
            composition.create(replacementItem)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        val resolver = PersistentKnowledgeCompositionAuthoritativeResolver(composition)
        assertIs<KnowledgeAuthoritativeResolutionResult.Stale>(resolver.resolveExact(staleCandidate))
        assertIs<KnowledgeAuthoritativeResolutionResult.Resolved>(
            resolver.resolveExact(
                KnowledgeRelevanceCandidate(replacement.item.id, replacement.generation)
            )
        )
    }

    @Test
    fun relevance_candidate_limit_is_explicit_and_positive() {
        assertEquals(64, CognitiveRelevanceRetrievalLimits(64).maxCandidatesPerSource)
        assertIs<IllegalArgumentException>(runCatching {
            CognitiveRelevanceRetrievalLimits(0)
        }.exceptionOrNull())
    }

    private fun foundation(prefix: String): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "$prefix-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun memoryRecord(id: String, content: String) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("private-turn")
        ),
        content = content,
        createdAt = Instant.parse("2026-09-02T20:00:00Z")
    )

    private fun knowledgeItem(id: String, content: String) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Memory(
            recordId = MemoryRecordId("memory-origin"),
            generation = MemoryGeneration(3)
        ),
        content = content,
        createdAt = Instant.parse("2026-09-02T20:01:00Z")
    )

    private fun turn() = CognitiveTurnReference(
        CognitiveTurnId("private-turn-id"),
        CognitiveTurnGeneration(1)
    )

    private fun openPersistentMemory(
        backend: InMemoryPersistentRecordBackend
    ): PersistentMemoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
        PersistentMemoryComposition.open(
            foundation = foundation("persistent-memory"),
            storeId = PersistentStoreId("semantic-memory-store"),
            backend = backend
        )
    ).composition

    private fun openPersistentKnowledge(
        backend: InMemoryPersistentRecordBackend
    ): PersistentKnowledgeComposition = assertIs<PersistentKnowledgeOpenResult.Opened>(
        PersistentKnowledgeComposition.open(
            foundation = foundation("persistent-knowledge"),
            storeId = PersistentStoreId("semantic-knowledge-store"),
            backend = backend
        )
    ).composition
}
