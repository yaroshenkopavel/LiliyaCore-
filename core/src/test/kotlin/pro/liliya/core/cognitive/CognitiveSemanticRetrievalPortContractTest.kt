package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

class CognitiveSemanticRetrievalPortContractTest {
    private val turn = CognitiveTurnReference(
        id = CognitiveTurnId("semantic-turn"),
        generation = CognitiveTurnGeneration(1)
    )
    private val input = CognitiveInput("private semantic query")
    private val limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 4)

    @Test
    fun memory_adapter_preserves_provider_order_omits_stale_and_forwards_exact_request() {
        val first = memorySnapshot("memory-first", 1, "first private memory")
        val stale = MemoryRelevanceCandidate(MemoryRecordId("memory-stale"), MemoryGeneration(2))
        val third = memorySnapshot("memory-third", 3, "third private memory")
        var observedRequest: MemoryRelevanceDiscoveryRequest? = null

        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort { request ->
                observedRequest = request
                MemoryRelevanceDiscoveryResult(
                    listOf(
                        candidate(first),
                        stale,
                        candidate(third)
                    )
                )
            },
            resolver = MemoryAuthoritativeResolverPort { candidate ->
                when (candidate.recordId.value) {
                    first.record.id.value -> MemoryAuthoritativeResolutionResult.Resolved(first)
                    stale.recordId.value -> MemoryAuthoritativeResolutionResult.Stale
                    third.record.id.value -> MemoryAuthoritativeResolutionResult.Resolved(third)
                    else -> error("unexpected candidate")
                }
            },
            limits = limits
        )

        val result = port.retrieve(
            MemoryRetrievalRequest(turn = turn, input = input, maxResults = 2)
        )

        assertEquals(listOf(first, third), result.items)
        assertEquals(turn, observedRequest?.turn)
        assertSame(input, observedRequest?.input)
        assertEquals(4, observedRequest?.maxCandidates)
    }

    @Test
    fun memory_adapter_rejects_same_id_multi_generation_before_any_resolution() {
        val calls = AtomicInteger(0)
        val id = MemoryRecordId("memory-aba")
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(
                    listOf(
                        MemoryRelevanceCandidate(id, MemoryGeneration(1)),
                        MemoryRelevanceCandidate(id, MemoryGeneration(2))
                    )
                )
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
    fun memory_adapter_rejects_oversized_discovery_before_any_resolution() {
        val calls = AtomicInteger(0)
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(
                    (1L..5L).map { generation ->
                        MemoryRelevanceCandidate(
                            recordId = MemoryRecordId("memory-$generation"),
                            generation = MemoryGeneration(generation)
                        )
                    }
                )
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
            CognitiveRelevanceRetrievalFailure.MEMORY_CANDIDATE_LIMIT_EXCEEDED,
            failure.failure
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun memory_adapter_rejects_resolver_snapshot_not_bound_to_exact_candidate() {
        val candidate = MemoryRelevanceCandidate(
            MemoryRecordId("memory-exact"),
            MemoryGeneration(7)
        )
        val wrong = memorySnapshot("memory-exact", 8, "newer private memory")
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(listOf(candidate))
            },
            resolver = MemoryAuthoritativeResolverPort {
                MemoryAuthoritativeResolutionResult.Resolved(wrong)
            },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(MemoryRetrievalRequest(turn, input, maxResults = 1))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.MEMORY_RESOLVER_CONTRACT_VIOLATION,
            failure.failure
        )
    }

    @Test
    fun knowledge_adapter_preserves_provider_order_and_omits_stale_without_fallback() {
        val first = knowledgeSnapshot("knowledge-first", 4, "first private knowledge")
        val stale = KnowledgeRelevanceCandidate(
            KnowledgeItemId("knowledge-stale"),
            KnowledgeGeneration(5)
        )
        val third = knowledgeSnapshot("knowledge-third", 6, "third private knowledge")
        val resolutionOrder = mutableListOf<String>()

        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(
                    listOf(candidate(first), stale, candidate(third))
                )
            },
            resolver = KnowledgeAuthoritativeResolverPort { candidate ->
                resolutionOrder += candidate.itemId.value
                when (candidate.itemId.value) {
                    first.item.id.value -> KnowledgeAuthoritativeResolutionResult.Resolved(first)
                    stale.itemId.value -> KnowledgeAuthoritativeResolutionResult.Stale
                    third.item.id.value -> KnowledgeAuthoritativeResolutionResult.Resolved(third)
                    else -> error("unexpected candidate")
                }
            },
            limits = limits
        )

        val result = port.retrieve(
            KnowledgeRetrievalRequest(turn = turn, input = input, maxResults = 2)
        )

        assertEquals(listOf(first, third), result.items)
        assertEquals(
            listOf("knowledge-first", "knowledge-stale", "knowledge-third"),
            resolutionOrder
        )
    }

    @Test
    fun knowledge_adapter_rejects_duplicate_entity_before_resolution() {
        val calls = AtomicInteger(0)
        val exact = KnowledgeRelevanceCandidate(
            KnowledgeItemId("knowledge-duplicate"),
            KnowledgeGeneration(9)
        )
        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(listOf(exact, exact))
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
    fun stale_only_candidates_produce_empty_result_and_never_invoke_hidden_fallback() {
        val calls = AtomicInteger(0)
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                calls.incrementAndGet()
                MemoryRelevanceDiscoveryResult(
                    listOf(
                        MemoryRelevanceCandidate(
                            MemoryRecordId("stale-only"),
                            MemoryGeneration(11)
                        )
                    )
                )
            },
            resolver = MemoryAuthoritativeResolverPort {
                MemoryAuthoritativeResolutionResult.Stale
            },
            limits = limits
        )

        val result = port.retrieve(MemoryRetrievalRequest(turn, input, maxResults = 3))

        assertTrue(result.items.isEmpty())
        assertEquals(1, calls.get())
    }

    @Test
    fun structural_failure_rendering_does_not_include_candidate_or_query_content() {
        val failure = CognitiveRelevanceRetrievalException(
            CognitiveRelevanceRetrievalFailure.MEMORY_DUPLICATE_ENTITY_CANDIDATE
        )

        val rendered = failure.toString()
        assertTrue(rendered.contains("MEMORY_DUPLICATE_ENTITY_CANDIDATE"))
        assertTrue(!rendered.contains("private semantic query"))
        assertTrue(!rendered.contains("memory-aba"))
    }

    private fun memorySnapshot(
        id: String,
        generation: Long,
        content: String
    ): MemoryRecordSnapshot = MemoryRecordSnapshot(
        record = MemoryRecord(
            id = MemoryRecordId(id),
            provenance = MemoryProvenance(MemorySourceId("test-source")),
            content = content,
            createdAt = Instant.parse("2026-09-03T12:00:00Z").plusSeconds(generation)
        ),
        generation = MemoryGeneration(generation)
    )

    private fun knowledgeSnapshot(
        id: String,
        generation: Long,
        content: String
    ): KnowledgeItemSnapshot = KnowledgeItemSnapshot(
        item = KnowledgeItem(
            id = KnowledgeItemId(id),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test-source")),
            content = content,
            createdAt = Instant.parse("2026-09-03T12:30:00Z").plusSeconds(generation)
        ),
        generation = KnowledgeGeneration(generation)
    )

    private fun candidate(snapshot: MemoryRecordSnapshot): MemoryRelevanceCandidate =
        MemoryRelevanceCandidate(snapshot.record.id, snapshot.generation)

    private fun candidate(snapshot: KnowledgeItemSnapshot): KnowledgeRelevanceCandidate =
        KnowledgeRelevanceCandidate(snapshot.item.id, snapshot.generation)
}
