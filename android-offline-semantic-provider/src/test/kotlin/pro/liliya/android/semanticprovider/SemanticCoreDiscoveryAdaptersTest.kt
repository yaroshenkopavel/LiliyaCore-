package pro.liliya.android.semanticprovider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryResult
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryResult
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticCoreDiscoveryAdaptersTest {

    @Test
    fun memory_adapter_preserves_ranked_exact_identity_and_generation() {
        val first = SemanticIndexSourceReference.Memory(
            MemoryRecordId("memory-a"),
            MemoryGeneration(7)
        )
        val second = SemanticIndexSourceReference.Memory(
            MemoryRecordId("memory-b"),
            MemoryGeneration(3)
        )
        val provider = recordingProvider(
            SemanticCandidateDiscoveryResult.Candidates(listOf(first, second))
        )

        val result = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider.port).discover(
            MemoryRelevanceDiscoveryRequest(
                turn = CognitiveTurnId("turn-memory"),
                input = CognitiveInput("where are my keys?"),
                maxCandidates = 2
            )
        )

        val candidates = assertIs<MemoryRelevanceDiscoveryResult.Candidates>(result).candidates
        assertEquals(listOf("memory-a", "memory-b"), candidates.map { it.recordId.value })
        assertEquals(listOf(7L, 3L), candidates.map { it.generation.value })
        assertEquals(SemanticIndexDomain.MEMORY, provider.domain)
        assertEquals("where are my keys?", provider.input)
        assertEquals(2, provider.maxCandidates)
    }

    @Test
    fun knowledge_adapter_preserves_ranked_exact_identity_and_generation() {
        val provider = recordingProvider(
            SemanticCandidateDiscoveryResult.Candidates(
                listOf(
                    SemanticIndexSourceReference.Knowledge(
                        KnowledgeItemId("knowledge-a"),
                        KnowledgeGeneration(11)
                    )
                )
            )
        )

        val result = OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(provider.port).discover(
            KnowledgeRelevanceDiscoveryRequest(
                turn = CognitiveTurnId("turn-knowledge"),
                input = CognitiveInput("what did we learn?"),
                maxCandidates = 4
            )
        )

        val candidates = assertIs<KnowledgeRelevanceDiscoveryResult.Candidates>(result).candidates
        assertEquals(1, candidates.size)
        assertEquals("knowledge-a", candidates.single().itemId.value)
        assertEquals(11L, candidates.single().generation.value)
        assertEquals(SemanticIndexDomain.KNOWLEDGE, provider.domain)
        assertEquals("what did we learn?", provider.input)
        assertEquals(4, provider.maxCandidates)
    }

    @Test
    fun memory_adapter_fails_closed_on_cross_domain_candidate() {
        val provider = SemanticCandidateDiscoveryPort { _, _, _ ->
            SemanticCandidateDiscoveryResult.Candidates(
                listOf(
                    SemanticIndexSourceReference.Knowledge(
                        KnowledgeItemId("wrong-domain"),
                        KnowledgeGeneration(1)
                    )
                )
            )
        }

        val result = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider).discover(
            MemoryRelevanceDiscoveryRequest(
                turn = CognitiveTurnId("turn-domain"),
                input = CognitiveInput("query"),
                maxCandidates = 1
            )
        )

        assertEquals(
            "CandidateDomainMismatch",
            assertIs<MemoryRelevanceDiscoveryResult.ProviderFailure>(result).className
        )
    }

    @Test
    fun adapter_fails_closed_when_provider_exceeds_requested_bound() {
        val provider = SemanticCandidateDiscoveryPort { _, _, _ ->
            SemanticCandidateDiscoveryResult.Candidates(
                listOf(
                    SemanticIndexSourceReference.Memory(MemoryRecordId("a"), MemoryGeneration(1)),
                    SemanticIndexSourceReference.Memory(MemoryRecordId("b"), MemoryGeneration(1))
                )
            )
        }

        val result = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider).discover(
            MemoryRelevanceDiscoveryRequest(
                turn = CognitiveTurnId("turn-bound"),
                input = CognitiveInput("query"),
                maxCandidates = 1
            )
        )

        assertEquals(
            "CandidateCountExceeded",
            assertIs<MemoryRelevanceDiscoveryResult.ProviderFailure>(result).className
        )
    }

    @Test
    fun adapter_maps_provider_exception_without_raw_message() {
        val provider = SemanticCandidateDiscoveryPort { _, _, _ ->
            throw IllegalStateException("private query or model detail")
        }

        val result = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider).discover(
            MemoryRelevanceDiscoveryRequest(
                turn = CognitiveTurnId("turn-failure"),
                input = CognitiveInput("private query"),
                maxCandidates = 1
            )
        )

        assertEquals(
            "IllegalStateException",
            assertIs<MemoryRelevanceDiscoveryResult.ProviderFailure>(result).className
        )
    }

    private fun recordingProvider(result: SemanticCandidateDiscoveryResult): RecordingProvider =
        RecordingProvider(result)

    private class RecordingProvider(
        private val result: SemanticCandidateDiscoveryResult
    ) {
        var domain: SemanticIndexDomain? = null
        var input: String? = null
        var maxCandidates: Int? = null

        val port = SemanticCandidateDiscoveryPort { requestedDomain, requestedInput, requestedMaxCandidates ->
            domain = requestedDomain
            input = requestedInput
            maxCandidates = requestedMaxCandidates
            result
        }
    }
}
