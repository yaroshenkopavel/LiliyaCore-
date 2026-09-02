package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class CognitiveRelevanceExplicitMalformedContractTest {
    private val turn = CognitiveTurnReference(
        CognitiveTurnId("explicit-malformed-turn"),
        CognitiveTurnGeneration(1)
    )
    private val input = CognitiveInput("private malformed query")
    private val limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 4)

    @Test
    fun duplicate_exact_memory_candidate_is_rejected_before_resolution() {
        val calls = AtomicInteger(0)
        val exact = MemoryRelevanceCandidate(
            MemoryRecordId("memory-duplicate-exact"),
            MemoryGeneration(3)
        )
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(listOf(exact, exact))
            },
            resolver = MemoryAuthoritativeResolverPort {
                calls.incrementAndGet()
                MemoryAuthoritativeResolutionResult.Stale
            },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(MemoryRetrievalRequest(turn, input, maxResults = 2))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.MEMORY_DUPLICATE_ENTITY_CANDIDATE,
            failure.failure
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun multiple_knowledge_generations_for_same_id_are_rejected_before_resolution() {
        val calls = AtomicInteger(0)
        val id = KnowledgeItemId("knowledge-multi-generation")
        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(
                    listOf(
                        KnowledgeRelevanceCandidate(id, KnowledgeGeneration(4)),
                        KnowledgeRelevanceCandidate(id, KnowledgeGeneration(5))
                    )
                )
            },
            resolver = KnowledgeAuthoritativeResolverPort {
                calls.incrementAndGet()
                KnowledgeAuthoritativeResolutionResult.Stale
            },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(KnowledgeRetrievalRequest(turn, input, maxResults = 2))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.KNOWLEDGE_DUPLICATE_ENTITY_CANDIDATE,
            failure.failure
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun knowledge_resolver_snapshot_not_bound_to_exact_candidate_is_rejected() {
        val candidate = KnowledgeRelevanceCandidate(
            KnowledgeItemId("knowledge-exact"),
            KnowledgeGeneration(7)
        )
        val wrong = KnowledgeItemSnapshot(
            item = KnowledgeItem(
                id = candidate.itemId,
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test-source")),
                content = "newer private knowledge",
                createdAt = Instant.parse("2026-09-03T13:00:00Z")
            ),
            generation = KnowledgeGeneration(8)
        )
        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(listOf(candidate))
            },
            resolver = KnowledgeAuthoritativeResolverPort {
                KnowledgeAuthoritativeResolutionResult.Resolved(wrong)
            },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(KnowledgeRetrievalRequest(turn, input, maxResults = 1))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.KNOWLEDGE_RESOLVER_CONTRACT_VIOLATION,
            failure.failure
        )
    }
}
