package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeCreateResult
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
import pro.liliya.core.memory.MemoryRememberResult
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider

class CognitiveRelevanceContextIntegrationContractTest {
    private val baseInstant = Instant.parse("2026-09-02T21:30:00Z")

    private fun foundation(): FoundationComposition {
        val correlation = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "relevance-context-${correlation.incrementAndGet()}"
            }
        )
    }

    private fun memoryRecord(id: String, content: String, seconds: Long) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(MemorySourceId("test-memory")),
        content = content,
        createdAt = baseInstant.plusSeconds(seconds)
    )

    private fun knowledgeItem(id: String, content: String, seconds: Long) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test-knowledge")),
        content = content,
        createdAt = baseInstant.plusSeconds(seconds)
    )

    private fun runtime(
        foundation: FoundationComposition,
        registry: CognitiveTurnRegistry,
        memory: MemoryRetrievalPort,
        knowledge: KnowledgeRetrievalPort
    ) = CognitiveRuntimeComposition(
        foundation = foundation,
        scope = CognitiveRuntimeScopeId("semantic-context-scope"),
        memoryRetrieval = memory,
        knowledgeRetrieval = knowledge,
        selfSnapshots = SelfSnapshotPort { null },
        personalitySnapshots = PersonalitySnapshotPort { emptyList() },
        inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "unused")
        },
        limits = registryLimits,
        registry = registry
    )

    @Test
    fun relevance_provider_order_is_preserved_into_context_after_exact_authoritative_resolution() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)

        val memoryFirst = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord("memory-first", "memory first", 1))
        ).ownership
        val memorySecond = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord("memory-second", "memory second", 2))
        ).ownership
        val knowledgeFirst = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(knowledgeItem("knowledge-first", "knowledge first", 3))
        ).ownership
        val knowledgeSecond = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(knowledgeItem("knowledge-second", "knowledge second", 4))
        ).ownership

        val memoryPort = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(
                    listOf(
                        MemoryRelevanceCandidate(memorySecond.record.id, memorySecond.generation),
                        MemoryRelevanceCandidate(memoryFirst.record.id, memoryFirst.generation)
                    )
                )
            },
            resolver = MemoryCompositionAuthoritativeResolver(memory),
            limits = relevanceLimits
        )
        val knowledgePort = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(
                    listOf(
                        KnowledgeRelevanceCandidate(knowledgeSecond.item.id, knowledgeSecond.generation),
                        KnowledgeRelevanceCandidate(knowledgeFirst.item.id, knowledgeFirst.generation)
                    )
                )
            },
            resolver = KnowledgeCompositionAuthoritativeResolver(knowledge),
            limits = relevanceLimits
        )

        val registry = CognitiveTurnRegistry(registryLimits)
        val composition = runtime(foundation, registry, memoryPort, knowledgePort)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("turn-order"), CognitiveInput("semantic query"))
        ).turn.reference

        assertIs<CognitiveContextAssemblyResult.Published>(composition.assembleContext(turn))
        val context = requireNotNull(registry.contextIfCurrent(turn))

        assertEquals(
            listOf("memory second", "memory first", "knowledge second", "knowledge first"),
            context.items.map { it.content }
        )
        assertEquals(
            listOf(
                CognitiveContextSourceReference.Memory(memorySecond.record.id, memorySecond.generation),
                CognitiveContextSourceReference.Memory(memoryFirst.record.id, memoryFirst.generation),
                CognitiveContextSourceReference.Knowledge(knowledgeSecond.item.id, knowledgeSecond.generation),
                CognitiveContextSourceReference.Knowledge(knowledgeFirst.item.id, knowledgeFirst.generation)
            ),
            context.items.map { it.source }
        )
    }

    @Test
    fun stale_semantic_candidate_is_omitted_without_rebinding_or_recency_fallback() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val selected = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord("selected", "selected content", 1))
        ).ownership
        val unselected = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord("unselected", "must not fallback", 2))
        ).ownership

        val staleGeneration = pro.liliya.core.memory.MemoryGeneration(selected.generation.value + 100)
        val memoryPort = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(
                    listOf(
                        MemoryRelevanceCandidate(selected.record.id, staleGeneration),
                        MemoryRelevanceCandidate(unselected.record.id, unselected.generation)
                    )
                )
            },
            resolver = MemoryCompositionAuthoritativeResolver(memory),
            limits = relevanceLimits
        )

        val registry = CognitiveTurnRegistry(registryLimits)
        val composition = runtime(
            foundation = foundation,
            registry = registry,
            memory = memoryPort,
            knowledge = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("turn-stale"), CognitiveInput("semantic query"))
        ).turn.reference

        assertIs<CognitiveContextAssemblyResult.Published>(composition.assembleContext(turn))
        val context = requireNotNull(registry.contextIfCurrent(turn))
        assertEquals(listOf("must not fallback"), context.items.map { it.content })
        assertTrue(context.items.none { it.content == "selected content" })
    }

    @Test
    fun duplicate_memory_candidates_fail_closed_as_memory_provider_failure_before_context_publication() {
        val candidate = MemoryRelevanceCandidate(
            MemoryRecordId("duplicate-memory"),
            pro.liliya.core.memory.MemoryGeneration(1)
        )
        val resolverCalls = AtomicInteger(0)
        val memoryPort = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(listOf(candidate, candidate))
            },
            resolver = MemoryAuthoritativeResolverPort {
                resolverCalls.incrementAndGet()
                MemoryAuthoritativeResolutionResult.Stale
            },
            limits = relevanceLimits
        )

        val foundation = foundation()
        val registry = CognitiveTurnRegistry(registryLimits)
        val composition = runtime(
            foundation,
            registry,
            memoryPort,
            KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("turn-memory-duplicate"), CognitiveInput("query"))
        ).turn.reference

        val rejected = assertIs<CognitiveContextAssemblyResult.Rejected>(
            composition.assembleContext(turn)
        )
        assertEquals(CognitiveContextAssemblyFailure.MEMORY_PROVIDER_FAILED, rejected.reason)
        assertEquals(0, resolverCalls.get())
        assertEquals(CognitiveTurnLifecycle.CREATED, composition.currentLifecycle())
    }

    @Test
    fun duplicate_knowledge_candidates_fail_closed_as_knowledge_provider_failure_before_context_publication() {
        val candidate = KnowledgeRelevanceCandidate(
            KnowledgeItemId("duplicate-knowledge"),
            pro.liliya.core.knowledge.KnowledgeGeneration(1)
        )
        val resolverCalls = AtomicInteger(0)
        val knowledgePort = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(listOf(candidate, candidate))
            },
            resolver = KnowledgeAuthoritativeResolverPort {
                resolverCalls.incrementAndGet()
                KnowledgeAuthoritativeResolutionResult.Stale
            },
            limits = relevanceLimits
        )

        val foundation = foundation()
        val registry = CognitiveTurnRegistry(registryLimits)
        val composition = runtime(
            foundation,
            registry,
            MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgePort
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("turn-knowledge-duplicate"), CognitiveInput("query"))
        ).turn.reference

        val rejected = assertIs<CognitiveContextAssemblyResult.Rejected>(
            composition.assembleContext(turn)
        )
        assertEquals(CognitiveContextAssemblyFailure.KNOWLEDGE_PROVIDER_FAILED, rejected.reason)
        assertEquals(0, resolverCalls.get())
        assertEquals(CognitiveTurnLifecycle.CREATED, composition.currentLifecycle())
    }

    private companion object {
        val registryLimits = CognitiveRuntimeLimits(
            maxInputChars = 256,
            maxContextItems = 16,
            maxContextItemChars = 256,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 256
        )
        val relevanceLimits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 8)
    }
}
