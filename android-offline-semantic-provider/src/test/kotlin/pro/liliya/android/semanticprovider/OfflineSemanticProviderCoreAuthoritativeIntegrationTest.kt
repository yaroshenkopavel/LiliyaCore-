package pro.liliya.android.semanticprovider

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveInferencePort
import pro.liliya.core.cognitive.CognitiveInferenceResult
import pro.liliya.core.cognitive.CognitiveRelevanceRetrievalLimits
import pro.liliya.core.cognitive.CognitiveRuntimeComposition
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnLifecycle
import pro.liliya.core.cognitive.CognitiveTurnRegistrationResult
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.KnowledgeCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.KnowledgeRetrievalRequest
import pro.liliya.core.cognitive.MemoryCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.MemoryRetrievalRequest
import pro.liliya.core.cognitive.PersonalitySnapshotPort
import pro.liliya.core.cognitive.RelevanceKnowledgeRetrievalPort
import pro.liliya.core.cognitive.RelevanceMemoryRetrievalPort
import pro.liliya.core.cognitive.SelfSnapshotPort
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

/**
 * Cross-module acceptance proof for the advisory semantic boundary.
 *
 * The provider intentionally keeps the generation it observed while indexing. If authoritative
 * Memory/Knowledge removes and recreates the same structural ID, Core must treat the old semantic
 * candidate as stale. The provider must never rebind the candidate to the newer authoritative
 * generation merely because the structural ID is equal.
 */
class OfflineSemanticProviderCoreAuthoritativeIntegrationTest {

    @Test
    fun provider_candidates_are_exactly_resolved_and_published_into_cognitive_context() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        val memoryOwnership = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord(MemoryRecordId("memory-context"), "memory context", 1))
        ).ownership
        val knowledgeOwnership = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(
                knowledgeItem(KnowledgeItemId("knowledge-context"), "knowledge context", 2)
            )
        ).ownership

        val provider = readyProvider()
        assertEquals(
            OfflineSemanticRebuildResult.Published(2),
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(
                        SemanticIndexSourceReference.Memory(
                            memoryOwnership.record.id,
                            memoryOwnership.generation
                        ),
                        memoryOwnership.record.content
                    ),
                    SemanticSourceObservation(
                        SemanticIndexSourceReference.Knowledge(
                            knowledgeOwnership.item.id,
                            knowledgeOwnership.generation
                        ),
                        knowledgeOwnership.item.content
                    )
                )
            )
        )

        val cognitive = cognitiveRuntime(foundation, memory, knowledge, provider)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            cognitive.beginTurn(CognitiveTurnId("provider-context-turn"), CognitiveInput("query"))
        ).turn.reference

        assertEquals(
            CognitiveContextAssemblyResult.Published(2),
            cognitive.assembleContext(turn)
        )
        assertEquals(CognitiveTurnLifecycle.CONTEXT_READY, cognitive.currentLifecycle())
    }

    @Test
    fun same_id_aba_candidate_is_omitted_from_published_cognitive_context() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        val id = MemoryRecordId("memory-context-aba")
        val original = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord(id, "original context memory", 1))
        ).ownership
        val provider = readyProvider()
        assertEquals(
            OfflineSemanticRebuildResult.Published(1),
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(
                        SemanticIndexSourceReference.Memory(id, original.generation),
                        original.record.content
                    )
                )
            )
        )

        assertEquals(true, original.remove())
        val replacement = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord(id, "replacement context memory", 2))
        ).ownership
        assertNotEquals(original.generation, replacement.generation)

        val cognitive = cognitiveRuntime(foundation, memory, knowledge, provider)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            cognitive.beginTurn(CognitiveTurnId("provider-context-aba-turn"), CognitiveInput("query"))
        ).turn.reference

        assertEquals(
            CognitiveContextAssemblyResult.Published(0),
            cognitive.assembleContext(turn)
        )
        assertEquals(CognitiveTurnLifecycle.CONTEXT_READY, cognitive.currentLifecycle())
        assertEquals(replacement.generation, memory.inspect(id)?.generation)
        assertEquals("replacement context memory", memory.inspect(id)?.record?.content)
    }

    @Test
    fun memory_provider_candidate_is_omitted_after_real_same_id_aba_replacement() {
        val foundation = foundation()
        val memory = MemoryComposition(foundation)
        val id = MemoryRecordId("memory-aba")
        val original = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord(id, "original memory", 1))
        ).ownership

        val provider = readyProvider()
        assertEquals(
            OfflineSemanticRebuildResult.Published(1),
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(
                        source = SemanticIndexSourceReference.Memory(id, original.generation),
                        content = original.record.content
                    )
                )
            )
        )

        assertEquals(true, original.remove())
        val replacement = assertIs<MemoryRememberResult.Remembered>(
            memory.remember(memoryRecord(id, "replacement memory", 2))
        ).ownership
        assertNotEquals(original.generation, replacement.generation)

        val retrieval = RelevanceMemoryRetrievalPort(
            discovery = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider),
            resolver = MemoryCompositionAuthoritativeResolver(memory),
            limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 4)
        ).retrieve(
            MemoryRetrievalRequest(
                turn = turn(),
                input = CognitiveInput("original memory"),
                maxResults = 1
            )
        )

        assertEquals(emptyList(), retrieval.items)
        assertEquals(replacement.generation, memory.inspect(id)?.generation)
        assertEquals("replacement memory", memory.inspect(id)?.record?.content)
    }

    @Test
    fun knowledge_provider_candidate_is_omitted_after_real_same_id_aba_replacement() {
        val foundation = foundation()
        val knowledge = KnowledgeComposition(foundation)
        val id = KnowledgeItemId("knowledge-aba")
        val original = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(knowledgeItem(id, "original knowledge", 1))
        ).ownership

        val provider = readyProvider()
        assertEquals(
            OfflineSemanticRebuildResult.Published(1),
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(
                        source = SemanticIndexSourceReference.Knowledge(id, original.generation),
                        content = original.item.content
                    )
                )
            )
        )

        assertEquals(true, original.remove())
        val replacement = assertIs<KnowledgeCreateResult.Created>(
            knowledge.create(knowledgeItem(id, "replacement knowledge", 2))
        ).ownership
        assertNotEquals(original.generation, replacement.generation)

        val retrieval = RelevanceKnowledgeRetrievalPort(
            discovery = OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(provider),
            resolver = KnowledgeCompositionAuthoritativeResolver(knowledge),
            limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 4)
        ).retrieve(
            KnowledgeRetrievalRequest(
                turn = turn(),
                input = CognitiveInput("original knowledge"),
                maxResults = 1
            )
        )

        assertEquals(emptyList(), retrieval.items)
        assertEquals(replacement.generation, knowledge.inspect(id)?.generation)
        assertEquals("replacement knowledge", knowledge.inspect(id)?.item?.content)
    }

    private fun readyProvider(): OfflineSemanticProviderComposition {
        val session = UnitVectorSession()
        val provider = OfflineSemanticProviderComposition(
            profileGeneration = SemanticProfileGeneration(1),
            sessionLoader = SemanticProviderSessionLoader {
                SemanticProviderSessionLoadResult.Loaded(session)
            }
        )
        assertEquals(OfflineSemanticProviderLoadResult.Ready, provider.load(artifact()))
        return provider
    }

    private fun cognitiveRuntime(
        foundation: FoundationComposition,
        memory: MemoryComposition,
        knowledge: KnowledgeComposition,
        provider: OfflineSemanticProviderComposition
    ): CognitiveRuntimeComposition {
        val retrievalLimits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 4)
        return CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("offline-semantic-provider-e2e"),
            memoryRetrieval = RelevanceMemoryRetrievalPort(
                discovery = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider),
                resolver = MemoryCompositionAuthoritativeResolver(memory),
                limits = retrievalLimits
            ),
            knowledgeRetrieval = RelevanceKnowledgeRetrievalPort(
                discovery = OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(provider),
                resolver = KnowledgeCompositionAuthoritativeResolver(knowledge),
                limits = retrievalLimits
            ),
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "unused")
            },
            limits = CognitiveRuntimeLimits(
                maxInputChars = 256,
                maxContextItems = 8,
                maxContextItemChars = 256,
                maxRetrievalResults = 2,
                maxInferenceOutputChars = 256
            )
        )
    }

    private fun artifact(): ValidatedSemanticModelArtifact =
        TestSemanticModelArtifacts.validated(File("/private/test/semantic-model.gguf"))

    private fun turn(): CognitiveTurnReference = CognitiveTurnReference(
        CognitiveTurnId("semantic-provider-aba-turn"),
        CognitiveTurnGeneration(1)
    )

    private fun memoryRecord(id: MemoryRecordId, content: String, seconds: Long): MemoryRecord =
        MemoryRecord(
            id = id,
            provenance = MemoryProvenance(MemorySourceId("semantic-provider-test")),
            content = content,
            createdAt = BASE_INSTANT.plusSeconds(seconds)
        )

    private fun knowledgeItem(id: KnowledgeItemId, content: String, seconds: Long): KnowledgeItem =
        KnowledgeItem(
            id = id,
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("semantic-provider-test")),
            content = content,
            createdAt = BASE_INSTANT.plusSeconds(seconds)
        )

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator {
                "semantic-provider-aba-${sequence.incrementAndGet()}"
            }
        )
    }

    private class UnitVectorSession : SemanticProviderEmbeddingSession {
        override fun embed(preparedText: String): SemanticEmbeddingResult {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[0] = 1f
            return SemanticEmbeddingResult.Embedded(SemanticEmbeddingVector(values))
        }

        override fun close(): SemanticEmbeddingCloseResult = SemanticEmbeddingCloseResult.Closed
    }

    private companion object {
        val BASE_INSTANT: Instant = Instant.parse("2026-09-03T05:40:00Z")
    }
}
