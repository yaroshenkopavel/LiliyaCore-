package pro.liliya.android.semanticprovider

import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.KnowledgeAuthoritativeResolutionResult
import pro.liliya.core.cognitive.KnowledgeRelevanceCandidate
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryPort
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryResult
import pro.liliya.core.cognitive.KnowledgeRetrievalRequest
import pro.liliya.core.cognitive.MemoryAuthoritativeResolutionResult
import pro.liliya.core.cognitive.MemoryRelevanceCandidate
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryPort
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryResult
import pro.liliya.core.cognitive.MemoryRetrievalRequest
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId

class AndroidOfflineSemanticCognitiveRetrievalAssemblyContractTest {

    @Test
    fun production_wiring_preserves_provider_order_and_omits_stale_exact_generations() {
        val staleMemory = MemoryRelevanceCandidate(
            MemoryRecordId("memory-stale"),
            MemoryGeneration(1)
        )
        val currentMemory = MemoryRelevanceCandidate(
            MemoryRecordId("memory-current"),
            MemoryGeneration(2)
        )
        val currentKnowledge = KnowledgeRelevanceCandidate(
            KnowledgeItemId("knowledge-current"),
            KnowledgeGeneration(3)
        )
        val resolvedMemory = memorySnapshot(
            id = currentMemory.recordId,
            generation = currentMemory.generation,
            content = "current memory"
        )
        val resolvedKnowledge = knowledgeSnapshot(
            id = currentKnowledge.itemId,
            generation = currentKnowledge.generation,
            content = "current knowledge"
        )

        val assembly = AndroidOfflineSemanticCognitiveRetrievalAssembly.createFromPorts(
            memoryDiscovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(listOf(staleMemory, currentMemory))
            },
            knowledgeDiscovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(listOf(currentKnowledge))
            },
            memoryResolver = { candidate ->
                if (candidate == currentMemory) {
                    MemoryAuthoritativeResolutionResult.Resolved(resolvedMemory)
                } else {
                    MemoryAuthoritativeResolutionResult.Stale
                }
            },
            knowledgeResolver = { candidate ->
                if (candidate == currentKnowledge) {
                    KnowledgeAuthoritativeResolutionResult.Resolved(resolvedKnowledge)
                } else {
                    KnowledgeAuthoritativeResolutionResult.Stale
                }
            },
            maxCandidatesPerSource = 4
        )

        val memoryResult = assembly.memoryRetrieval.retrieve(
            MemoryRetrievalRequest(
                turn = turn(),
                input = CognitiveInput("query"),
                maxResults = 2
            )
        )
        val knowledgeResult = assembly.knowledgeRetrieval.retrieve(
            KnowledgeRetrievalRequest(
                turn = turn(),
                input = CognitiveInput("query"),
                maxResults = 2
            )
        )

        assertEquals(listOf(resolvedMemory), memoryResult.items)
        assertEquals(listOf(resolvedKnowledge), knowledgeResult.items)
    }

    private fun memorySnapshot(
        id: MemoryRecordId,
        generation: MemoryGeneration,
        content: String
    ): MemoryRecordSnapshot = MemoryRecordSnapshot(
        record = MemoryRecord(
            id = id,
            provenance = MemoryProvenance(MemorySourceId("semantic-cognitive-wiring")),
            content = content,
            createdAt = BASE
        ),
        generation = generation
    )

    private fun knowledgeSnapshot(
        id: KnowledgeItemId,
        generation: KnowledgeGeneration,
        content: String
    ): KnowledgeItemSnapshot = KnowledgeItemSnapshot(
        item = KnowledgeItem(
            id = id,
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("semantic-cognitive-wiring")),
            content = content,
            createdAt = BASE.plusSeconds(1)
        ),
        generation = generation
    )

    private fun turn(): CognitiveTurnReference = CognitiveTurnReference(
        CognitiveTurnId("semantic-cognitive-wiring-turn"),
        CognitiveTurnGeneration(1)
    )

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-05T14:00:00Z")
    }
}
