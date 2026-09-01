package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentity
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.identity.SelfIdentitySnapshot
import pro.liliya.core.identity.SelfName
import pro.liliya.core.identity.SelfOrigin
import pro.liliya.core.identity.SelfSourceId
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
import pro.liliya.core.personality.PersonalityAttribute
import pro.liliya.core.personality.PersonalityAttributeKey
import pro.liliya.core.personality.PersonalityAttributeValue
import pro.liliya.core.personality.PersonalityGeneration
import pro.liliya.core.personality.PersonalityProfile
import pro.liliya.core.personality.PersonalityProfileId
import pro.liliya.core.personality.PersonalityProfileSnapshot
import pro.liliya.core.personality.PersonalityProvenance
import pro.liliya.core.personality.PersonalitySourceId
import pro.liliya.core.personality.PersonalityTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveContextAssemblerContractTest {
    private val baseInstant: Instant = Instant.parse("2026-09-01T12:00:00Z")

    private data class Fixture(
        val logs: InMemoryLogWriter,
        val registry: CognitiveTurnRegistry,
        val composition: CognitiveRuntimeComposition
    )

    private fun fixture(
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(
            maxInputChars = 128,
            maxContextItems = 8,
            maxContextItemChars = 128,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 128
        ),
        memory: MemoryRetrievalPort = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
        knowledge: KnowledgeRetrievalPort = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
        self: SelfSnapshotPort = SelfSnapshotPort { null },
        personality: PersonalitySnapshotPort = PersonalitySnapshotPort { emptyList() },
        inferenceCalls: AtomicInteger = AtomicInteger(0)
    ): Fixture {
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "context-${correlation.incrementAndGet()}" }
        )
        val registry = CognitiveTurnRegistry(limits)
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            memoryRetrieval = memory,
            knowledgeRetrieval = knowledge,
            selfSnapshots = self,
            personalitySnapshots = personality,
            inference = CognitiveInferencePort { request ->
                inferenceCalls.incrementAndGet()
                CognitiveInferenceResult.Succeeded(request.turn, "not-used-by-slice-2")
            },
            limits = limits,
            registry = registry
        )
        return Fixture(logs, registry, composition)
    }

    @Test
    fun context_assembly_preserves_fixed_category_order_provider_order_and_exact_generations() {
        val self = selfSnapshot()
        val applicableFirst = personalitySnapshot(
            id = "personality-2",
            generation = 5,
            self = self,
            attributes = listOf("tone" to "warm", "style" to "concise"),
            seconds = 2
        )
        val applicableSecond = personalitySnapshot(
            id = "personality-1",
            generation = 6,
            self = self,
            attributes = listOf("tempo" to "calm"),
            seconds = 3
        )
        val stalePersonality = personalitySnapshot(
            id = "personality-stale",
            generation = 7,
            self = self.copy(generation = SelfGeneration(self.generation.value + 1)),
            attributes = listOf("ignored" to "yes"),
            seconds = 4
        )
        val memory = listOf(
            memorySnapshot("memory-2", 12, "memory-two", 5),
            memorySnapshot("memory-1", 13, "memory-one", 6)
        )
        val knowledge = listOf(
            knowledgeSnapshot("knowledge-2", 20, "knowledge-two", 7),
            knowledgeSnapshot("knowledge-1", 21, "knowledge-one", 8)
        )
        var memoryRequest: MemoryRetrievalRequest? = null
        var knowledgeRequest: KnowledgeRetrievalRequest? = null
        val inferenceCalls = AtomicInteger(0)
        val f = fixture(
            memory = MemoryRetrievalPort { request ->
                memoryRequest = request
                MemoryRetrievalResult(memory)
            },
            knowledge = KnowledgeRetrievalPort { request ->
                knowledgeRequest = request
                KnowledgeRetrievalResult(knowledge)
            },
            self = SelfSnapshotPort { self },
            personality = PersonalitySnapshotPort {
                listOf(applicableFirst, applicableSecond, stalePersonality)
            },
            inferenceCalls = inferenceCalls
        )
        val input = CognitiveInput("private turn input")
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("ordered"), input)
        ).turn

        val published = assertIs<CognitiveContextAssemblyResult.Published>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(7, published.itemCount)
        assertEquals(CognitiveTurnLifecycle.CONTEXT_READY, turn.lifecycle())
        assertEquals(0, inferenceCalls.get())
        assertEquals(turn.reference, memoryRequest?.turn)
        assertEquals(turn.reference, knowledgeRequest?.turn)
        assertEquals(input, memoryRequest?.input)
        assertEquals(input, knowledgeRequest?.input)
        assertEquals(4, memoryRequest?.maxResults)
        assertEquals(4, knowledgeRequest?.maxResults)

        val context = f.registry.contextIfCurrent(turn.reference)
        requireNotNull(context)
        assertEquals(
            listOf(
                "Liliya",
                "tone=warm\nstyle=concise",
                "tempo=calm",
                "memory-two",
                "memory-one",
                "knowledge-two",
                "knowledge-one"
            ),
            context.items.map { it.content }
        )
        assertEquals(
            listOf(
                CognitiveContextSourceReference.Self(self.identity.id, self.generation),
                CognitiveContextSourceReference.Personality(applicableFirst.profile.id, applicableFirst.generation),
                CognitiveContextSourceReference.Personality(applicableSecond.profile.id, applicableSecond.generation),
                CognitiveContextSourceReference.Memory(memory[0].record.id, memory[0].generation),
                CognitiveContextSourceReference.Memory(memory[1].record.id, memory[1].generation),
                CognitiveContextSourceReference.Knowledge(knowledge[0].item.id, knowledge[0].generation),
                CognitiveContextSourceReference.Knowledge(knowledge[1].item.id, knowledge[1].generation)
            ),
            context.items.map { it.source }
        )
    }

    @Test
    fun memory_result_over_bound_fails_closed_before_knowledge_and_without_publication() {
        val knowledgeCalls = AtomicInteger(0)
        val limits = CognitiveRuntimeLimits(
            maxInputChars = 64,
            maxContextItems = 8,
            maxContextItemChars = 64,
            maxRetrievalResults = 1,
            maxInferenceOutputChars = 64
        )
        val f = fixture(
            limits = limits,
            memory = MemoryRetrievalPort {
                MemoryRetrievalResult(
                    listOf(
                        memorySnapshot("m1", 1, "one", 1),
                        memorySnapshot("m2", 2, "two", 2)
                    )
                )
            },
            knowledge = KnowledgeRetrievalPort {
                knowledgeCalls.incrementAndGet()
                KnowledgeRetrievalResult(emptyList())
            }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("memory-bound"), CognitiveInput("input"))
        ).turn

        val result = assertIs<CognitiveContextAssemblyResult.Rejected>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(CognitiveContextAssemblyFailure.MEMORY_RESULT_LIMIT_REJECTED, result.reason)
        assertEquals(0, knowledgeCalls.get())
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(f.registry.contextIfCurrent(turn.reference))
    }

    @Test
    fun memory_success_then_knowledge_failure_has_no_partial_publication_and_does_not_log_exception_message() {
        val secret = "private-provider-secret"
        val f = fixture(
            memory = MemoryRetrievalPort {
                MemoryRetrievalResult(listOf(memorySnapshot("m1", 1, "private-memory", 1)))
            },
            knowledge = KnowledgeRetrievalPort {
                throw IllegalStateException(secret)
            }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("provider-failure"), CognitiveInput("private-input"))
        ).turn

        val result = assertIs<CognitiveContextAssemblyResult.Rejected>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(CognitiveContextAssemblyFailure.KNOWLEDGE_PROVIDER_FAILED, result.reason)
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(f.registry.contextIfCurrent(turn.reference))
        f.logs.snapshot().forEach { event ->
            assertFalse(event.message.contains(secret))
            assertFalse(event.metadata.values.any { it.contains(secret) })
            assertFalse(event.message.contains("private-memory"))
            assertFalse(event.metadata.values.any { it.contains("private-memory") })
            assertFalse(event.message.contains("private-input"))
            assertFalse(event.metadata.values.any { it.contains("private-input") })
        }
    }

    @Test
    fun stale_replacement_turn_cannot_receive_prior_provider_results_and_later_provider_is_not_called() {
        val limits = CognitiveRuntimeLimits(
            maxInputChars = 64,
            maxContextItems = 8,
            maxContextItemChars = 64,
            maxRetrievalResults = 2,
            maxInferenceOutputChars = 64
        )
        val registry = CognitiveTurnRegistry(limits)
        val knowledgeCalls = AtomicInteger(0)
        lateinit var replacement: CognitiveTurnHandle
        val memory = MemoryRetrievalPort { request ->
            assertIs<CognitiveTurnTransitionResult.Failed>(registry.failIfCurrent(request.turn))
            replacement = assertIs<CognitiveTurnRegistrationResult.Registered>(
                registry.register(CognitiveTurnId("same"), CognitiveInput("replacement"))
            ).turn
            MemoryRetrievalResult(listOf(memorySnapshot("old", 1, "old-private", 1)))
        }
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "stale-${sequence.incrementAndGet()}" }
        )
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            memoryRetrieval = memory,
            knowledgeRetrieval = KnowledgeRetrievalPort {
                knowledgeCalls.incrementAndGet()
                KnowledgeRetrievalResult(emptyList())
            },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "unused")
            },
            limits = limits,
            registry = registry
        )
        val original = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("same"), CognitiveInput("original"))
        ).turn

        assertIs<CognitiveContextAssemblyResult.Stale>(composition.assembleContext(original.reference))

        assertEquals(0, knowledgeCalls.get())
        assertEquals(CognitiveTurnLifecycle.FAILED, original.lifecycle())
        assertEquals(2L, replacement.reference.generation.value)
        assertEquals(CognitiveTurnLifecycle.CREATED, replacement.lifecycle())
        assertNull(registry.contextIfCurrent(replacement.reference))
    }

    @Test
    fun missing_self_skips_personality_projection_and_context_limit_failure_stays_unpublished() {
        val personalityCalls = AtomicInteger(0)
        val limits = CognitiveRuntimeLimits(
            maxInputChars = 64,
            maxContextItems = 1,
            maxContextItemChars = 5,
            maxRetrievalResults = 2,
            maxInferenceOutputChars = 64
        )
        val f = fixture(
            limits = limits,
            memory = MemoryRetrievalPort {
                MemoryRetrievalResult(listOf(memorySnapshot("m1", 1, "too-long", 1)))
            },
            self = SelfSnapshotPort { null },
            personality = PersonalitySnapshotPort {
                personalityCalls.incrementAndGet()
                emptyList()
            }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("no-self"), CognitiveInput("input"))
        ).turn

        val result = assertIs<CognitiveContextAssemblyResult.Rejected>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED, result.reason)
        assertEquals(0, personalityCalls.get())
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(f.registry.contextIfCurrent(turn.reference))
    }

    @Test
    fun knowledge_result_over_bound_fails_closed_without_context_publication() {
        val limits = CognitiveRuntimeLimits(
            maxInputChars = 64,
            maxContextItems = 8,
            maxContextItemChars = 64,
            maxRetrievalResults = 1,
            maxInferenceOutputChars = 64
        )
        val f = fixture(
            limits = limits,
            knowledge = KnowledgeRetrievalPort {
                KnowledgeRetrievalResult(
                    listOf(
                        knowledgeSnapshot("k1", 1, "one", 1),
                        knowledgeSnapshot("k2", 2, "two", 2)
                    )
                )
            }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("knowledge-bound"), CognitiveInput("input"))
        ).turn

        val result = assertIs<CognitiveContextAssemblyResult.Rejected>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(CognitiveContextAssemblyFailure.KNOWLEDGE_RESULT_LIMIT_REJECTED, result.reason)
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(f.registry.contextIfCurrent(turn.reference))
    }

    private fun selfSnapshot(): SelfIdentitySnapshot = SelfIdentitySnapshot(
        identity = SelfIdentity(
            id = SelfIdentityId("self-1"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Declared(SelfSourceId("test")),
            createdAt = baseInstant
        ),
        generation = SelfGeneration(7)
    )

    private fun personalitySnapshot(
        id: String,
        generation: Long,
        self: SelfIdentitySnapshot,
        attributes: List<Pair<String, String>>,
        seconds: Long
    ): PersonalityProfileSnapshot = PersonalityProfileSnapshot(
        profile = PersonalityProfile(
            id = PersonalityProfileId(id),
            target = PersonalityTarget.Self(self.identity.id, self.generation),
            attributes = attributes.map { (key, value) ->
                PersonalityAttribute(
                    key = PersonalityAttributeKey(key),
                    value = PersonalityAttributeValue(value)
                )
            },
            provenance = PersonalityProvenance(PersonalitySourceId("test")),
            createdAt = baseInstant.plusSeconds(seconds)
        ),
        generation = PersonalityGeneration(generation)
    )

    private fun memorySnapshot(
        id: String,
        generation: Long,
        content: String,
        seconds: Long
    ): MemoryRecordSnapshot = MemoryRecordSnapshot(
        record = MemoryRecord(
            id = MemoryRecordId(id),
            provenance = MemoryProvenance(MemorySourceId("test")),
            content = content,
            createdAt = baseInstant.plusSeconds(seconds)
        ),
        generation = MemoryGeneration(generation)
    )

    private fun knowledgeSnapshot(
        id: String,
        generation: Long,
        content: String,
        seconds: Long
    ): KnowledgeItemSnapshot = KnowledgeItemSnapshot(
        item = KnowledgeItem(
            id = KnowledgeItemId(id),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
            content = content,
            createdAt = baseInstant.plusSeconds(seconds)
        ),
        generation = KnowledgeGeneration(generation)
    )
}
