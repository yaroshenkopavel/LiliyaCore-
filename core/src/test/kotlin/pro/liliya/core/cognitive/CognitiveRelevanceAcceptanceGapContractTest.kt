package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider

class CognitiveRelevanceAcceptanceGapContractTest {
    private val baseInstant = Instant.parse("2026-09-03T10:00:00Z")
    private val input = CognitiveInput("private relevance query")
    private val limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 8)

    @Test
    fun discovery_provider_failure_is_structural_and_redacts_provider_message() {
        val secret = "private-discovery-secret"
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort { throw IllegalStateException(secret) },
            resolver = MemoryAuthoritativeResolverPort { MemoryAuthoritativeResolutionResult.Stale },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(memoryRequest(maxResults = 4))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.MEMORY_DISCOVERY_PROVIDER_FAILED,
            failure.failure
        )
        assertTrue(failure.toString().contains("java.lang.IllegalStateException"))
        assertTrue(!failure.toString().contains(secret))
        assertTrue(!failure.message.orEmpty().contains(secret))
    }

    @Test
    fun resolver_provider_failure_is_structural_and_redacts_provider_message() {
        val secret = "private-resolver-secret"
        val candidate = KnowledgeRelevanceCandidate(
            KnowledgeItemId("knowledge-private-id"),
            KnowledgeGeneration(1)
        )
        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                KnowledgeRelevanceDiscoveryResult(listOf(candidate))
            },
            resolver = KnowledgeAuthoritativeResolverPort { throw IllegalArgumentException(secret) },
            limits = limits
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(knowledgeRequest(maxResults = 4))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.KNOWLEDGE_RESOLVER_PROVIDER_FAILED,
            failure.failure
        )
        assertTrue(failure.toString().contains("java.lang.IllegalArgumentException"))
        assertTrue(!failure.toString().contains(secret))
        assertTrue(!failure.message.orEmpty().contains(secret))
    }

    @Test
    fun configured_candidate_bound_must_cover_final_request_bound_before_discovery() {
        val discoveryCalls = AtomicInteger(0)
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                discoveryCalls.incrementAndGet()
                MemoryRelevanceDiscoveryResult(emptyList())
            },
            resolver = MemoryAuthoritativeResolverPort { MemoryAuthoritativeResolutionResult.Stale },
            limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource = 2)
        )

        val failure = assertFailsWith<CognitiveRelevanceRetrievalException> {
            port.retrieve(memoryRequest(maxResults = 3))
        }

        assertEquals(
            CognitiveRelevanceRetrievalFailure.MEMORY_CANDIDATE_BOUND_INSUFFICIENT,
            failure.failure
        )
        assertEquals(0, discoveryCalls.get())
    }

    @Test
    fun resolution_stops_after_final_result_bound() {
        val snapshots = (1L..4L).map { generation ->
            memorySnapshot("memory-$generation", generation, "memory-$generation-content")
        }
        val resolverCalls = AtomicInteger(0)
        val port = RelevanceMemoryRetrievalPort(
            discovery = MemoryRelevanceDiscoveryPort {
                MemoryRelevanceDiscoveryResult(snapshots.map(::memoryCandidate))
            },
            resolver = MemoryAuthoritativeResolverPort { candidate ->
                resolverCalls.incrementAndGet()
                MemoryAuthoritativeResolutionResult.Resolved(
                    snapshots.first { snapshot -> snapshot.record.id == candidate.recordId }
                )
            },
            limits = limits
        )

        val result = port.retrieve(memoryRequest(maxResults = 2))

        assertEquals(snapshots.take(2), result.items)
        assertEquals(2, resolverCalls.get())
    }

    @Test
    fun all_stale_knowledge_returns_empty_without_retry_or_fallback() {
        val discoveryCalls = AtomicInteger(0)
        val resolverCalls = AtomicInteger(0)
        val candidates = listOf(
            KnowledgeRelevanceCandidate(KnowledgeItemId("stale-k1"), KnowledgeGeneration(1)),
            KnowledgeRelevanceCandidate(KnowledgeItemId("stale-k2"), KnowledgeGeneration(2))
        )
        val port = RelevanceKnowledgeRetrievalPort(
            discovery = KnowledgeRelevanceDiscoveryPort {
                discoveryCalls.incrementAndGet()
                KnowledgeRelevanceDiscoveryResult(candidates)
            },
            resolver = KnowledgeAuthoritativeResolverPort {
                resolverCalls.incrementAndGet()
                KnowledgeAuthoritativeResolutionResult.Stale
            },
            limits = limits
        )

        val result = port.retrieve(knowledgeRequest(maxResults = 4))

        assertTrue(result.items.isEmpty())
        assertEquals(1, discoveryCalls.get())
        assertEquals(2, resolverCalls.get())
    }

    @Test
    fun stale_turn_during_memory_discovery_cannot_publish_context() {
        val fixture = contextFixture(
            memoryFactory = { registry, turn ->
                RelevanceMemoryRetrievalPort(
                    discovery = MemoryRelevanceDiscoveryPort {
                        registry.failIfCurrent(turn)
                        MemoryRelevanceDiscoveryResult(emptyList())
                    },
                    resolver = MemoryAuthoritativeResolverPort { MemoryAuthoritativeResolutionResult.Stale },
                    limits = limits
                )
            }
        )

        assertEquals(CognitiveContextAssemblyResult.Stale, fixture.composition.assembleContext(fixture.turn))
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
    }

    @Test
    fun stale_turn_during_knowledge_discovery_cannot_publish_context() {
        val fixture = contextFixture(
            knowledgeFactory = { registry, turn ->
                RelevanceKnowledgeRetrievalPort(
                    discovery = KnowledgeRelevanceDiscoveryPort {
                        registry.failIfCurrent(turn)
                        KnowledgeRelevanceDiscoveryResult(emptyList())
                    },
                    resolver = KnowledgeAuthoritativeResolverPort { KnowledgeAuthoritativeResolutionResult.Stale },
                    limits = limits
                )
            }
        )

        assertEquals(CognitiveContextAssemblyResult.Stale, fixture.composition.assembleContext(fixture.turn))
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
    }

    @Test
    fun stale_turn_during_memory_resolution_cannot_publish_context() {
        val snapshot = memorySnapshot("memory-resolution", 1, "private memory")
        val fixture = contextFixture(
            memoryFactory = { registry, turn ->
                RelevanceMemoryRetrievalPort(
                    discovery = MemoryRelevanceDiscoveryPort {
                        MemoryRelevanceDiscoveryResult(listOf(memoryCandidate(snapshot)))
                    },
                    resolver = MemoryAuthoritativeResolverPort {
                        registry.failIfCurrent(turn)
                        MemoryAuthoritativeResolutionResult.Resolved(snapshot)
                    },
                    limits = limits
                )
            }
        )

        assertEquals(CognitiveContextAssemblyResult.Stale, fixture.composition.assembleContext(fixture.turn))
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
    }

    @Test
    fun stale_turn_during_knowledge_resolution_cannot_publish_context() {
        val snapshot = knowledgeSnapshot("knowledge-resolution", 1, "private knowledge")
        val fixture = contextFixture(
            knowledgeFactory = { registry, turn ->
                RelevanceKnowledgeRetrievalPort(
                    discovery = KnowledgeRelevanceDiscoveryPort {
                        KnowledgeRelevanceDiscoveryResult(listOf(knowledgeCandidate(snapshot)))
                    },
                    resolver = KnowledgeAuthoritativeResolverPort {
                        registry.failIfCurrent(turn)
                        KnowledgeAuthoritativeResolutionResult.Resolved(snapshot)
                    },
                    limits = limits
                )
            }
        )

        assertEquals(CognitiveContextAssemblyResult.Stale, fixture.composition.assembleContext(fixture.turn))
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
    }

    @Test
    fun memory_provider_failure_cannot_partially_publish_context() {
        val fixture = contextFixture(
            memoryFactory = { _, _ ->
                RelevanceMemoryRetrievalPort(
                    discovery = MemoryRelevanceDiscoveryPort {
                        throw IllegalStateException("private-memory-provider-message")
                    },
                    resolver = MemoryAuthoritativeResolverPort { MemoryAuthoritativeResolutionResult.Stale },
                    limits = limits
                )
            },
            knowledgeFactory = { _, _ ->
                KnowledgeRetrievalPort {
                    KnowledgeRetrievalResult(listOf(knowledgeSnapshot("knowledge-ready", 1, "ready knowledge")))
                }
            }
        )

        val rejected = assertIs<CognitiveContextAssemblyResult.Rejected>(
            fixture.composition.assembleContext(fixture.turn)
        )
        assertEquals(CognitiveContextAssemblyFailure.MEMORY_PROVIDER_FAILED, rejected.reason)
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
        assertEquals(CognitiveTurnLifecycle.CREATED, fixture.composition.currentLifecycle())
    }

    @Test
    fun knowledge_resolver_failure_cannot_partially_publish_already_collected_memory() {
        val memory = memorySnapshot("memory-ready", 1, "ready memory")
        val candidate = KnowledgeRelevanceCandidate(KnowledgeItemId("knowledge-fails"), KnowledgeGeneration(1))
        val fixture = contextFixture(
            memoryFactory = { _, _ -> MemoryRetrievalPort { MemoryRetrievalResult(listOf(memory)) } },
            knowledgeFactory = { _, _ ->
                RelevanceKnowledgeRetrievalPort(
                    discovery = KnowledgeRelevanceDiscoveryPort {
                        KnowledgeRelevanceDiscoveryResult(listOf(candidate))
                    },
                    resolver = KnowledgeAuthoritativeResolverPort {
                        throw IllegalStateException("private-knowledge-resolver-message")
                    },
                    limits = limits
                )
            }
        )

        val rejected = assertIs<CognitiveContextAssemblyResult.Rejected>(
            fixture.composition.assembleContext(fixture.turn)
        )
        assertEquals(CognitiveContextAssemblyFailure.KNOWLEDGE_PROVIDER_FAILED, rejected.reason)
        assertNull(fixture.registry.contextIfCurrent(fixture.turn))
        assertEquals(CognitiveTurnLifecycle.CREATED, fixture.composition.currentLifecycle())
    }

    @Test
    fun semantic_retrieval_contract_types_expose_no_authority_license_execution_permission_surface() {
        val contractTypes = listOf(
            MemoryRelevanceCandidate::class.java,
            KnowledgeRelevanceCandidate::class.java,
            MemoryRelevanceDiscoveryRequest::class.java,
            KnowledgeRelevanceDiscoveryRequest::class.java,
            MemoryRelevanceDiscoveryResult::class.java,
            KnowledgeRelevanceDiscoveryResult::class.java,
            MemoryRelevanceDiscoveryPort::class.java,
            KnowledgeRelevanceDiscoveryPort::class.java,
            MemoryAuthoritativeResolverPort::class.java,
            KnowledgeAuthoritativeResolverPort::class.java,
            RelevanceMemoryRetrievalPort::class.java,
            RelevanceKnowledgeRetrievalPort::class.java
        )
        val forbidden = listOf("authority", "license", "execution", "permission")

        for (type in contractTypes) {
            val exposedNames = buildList {
                addAll(type.declaredFields.map { it.name.lowercase() })
                addAll(type.declaredMethods.map { it.name.lowercase() })
            }
            for (word in forbidden) {
                assertTrue(
                    exposedNames.none { it.contains(word) },
                    "${type.simpleName} must not expose $word surface"
                )
            }
        }
    }

    private data class ContextFixture(
        val registry: CognitiveTurnRegistry,
        val composition: CognitiveRuntimeComposition,
        val turn: CognitiveTurnReference
    )

    private fun contextFixture(
        memoryFactory: (CognitiveTurnRegistry, CognitiveTurnReference) -> MemoryRetrievalPort = { _, _ ->
            MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) }
        },
        knowledgeFactory: (CognitiveTurnRegistry, CognitiveTurnReference) -> KnowledgeRetrievalPort = { _, _ ->
            KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) }
        }
    ): ContextFixture {
        val foundation = foundation()
        val registry = CognitiveTurnRegistry(runtimeLimits)
        val placeholderMemory = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) }
        val placeholderKnowledge = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) }
        val first = runtime(foundation, registry, placeholderMemory, placeholderKnowledge)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            first.beginTurn(CognitiveTurnId("acceptance-gap-turn-${turnSequence.incrementAndGet()}"), input)
        ).turn.reference
        val composition = runtime(
            foundation,
            registry,
            memoryFactory(registry, turn),
            knowledgeFactory(registry, turn)
        )
        return ContextFixture(registry, composition, turn)
    }

    private fun runtime(
        foundation: FoundationComposition,
        registry: CognitiveTurnRegistry,
        memory: MemoryRetrievalPort,
        knowledge: KnowledgeRetrievalPort
    ): CognitiveRuntimeComposition = CognitiveRuntimeComposition(
        foundation = foundation,
        scope = CognitiveRuntimeScopeId("acceptance-gap-scope"),
        memoryRetrieval = memory,
        knowledgeRetrieval = knowledge,
        selfSnapshots = SelfSnapshotPort { null },
        personalitySnapshots = PersonalitySnapshotPort { emptyList() },
        inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "unused")
        },
        limits = runtimeLimits,
        registry = registry
    )

    private fun foundation(): FoundationComposition {
        val correlation = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "acceptance-gap-${correlation.incrementAndGet()}" }
        )
    }

    private fun memoryRequest(maxResults: Int) = MemoryRetrievalRequest(turn(), input, maxResults)

    private fun knowledgeRequest(maxResults: Int) = KnowledgeRetrievalRequest(turn(), input, maxResults)

    private fun turn() = CognitiveTurnReference(
        CognitiveTurnId("direct-relevance-turn"),
        CognitiveTurnGeneration(1)
    )

    private fun memorySnapshot(id: String, generation: Long, content: String) = MemoryRecordSnapshot(
        MemoryRecord(
            id = MemoryRecordId(id),
            provenance = MemoryProvenance(MemorySourceId("acceptance-source")),
            content = content,
            createdAt = baseInstant.plusSeconds(generation)
        ),
        MemoryGeneration(generation)
    )

    private fun knowledgeSnapshot(id: String, generation: Long, content: String) = KnowledgeItemSnapshot(
        KnowledgeItem(
            id = KnowledgeItemId(id),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("acceptance-source")),
            content = content,
            createdAt = baseInstant.plusSeconds(generation)
        ),
        KnowledgeGeneration(generation)
    )

    private fun memoryCandidate(snapshot: MemoryRecordSnapshot) =
        MemoryRelevanceCandidate(snapshot.record.id, snapshot.generation)

    private fun knowledgeCandidate(snapshot: KnowledgeItemSnapshot) =
        KnowledgeRelevanceCandidate(snapshot.item.id, snapshot.generation)

    private companion object {
        val runtimeLimits = CognitiveRuntimeLimits(
            maxInputChars = 256,
            maxContextItems = 16,
            maxContextItemChars = 256,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 256
        )
        val turnSequence = AtomicInteger(0)
    }
}
