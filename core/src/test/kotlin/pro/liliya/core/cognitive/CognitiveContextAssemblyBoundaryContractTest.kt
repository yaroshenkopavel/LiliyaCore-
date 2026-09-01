package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CognitiveContextAssemblyBoundaryContractTest {
    private val instant = Instant.parse("2026-09-01T12:00:00Z")

    @Test
    fun provider_reentrancy_cannot_create_second_live_turn() {
        val limits = limits(maxContextItems = 4)
        val registry = CognitiveTurnRegistry(limits)
        val foundation = foundation("reentrant")
        lateinit var composition: CognitiveRuntimeComposition
        var nestedResult: CognitiveTurnRegistrationResult? = null

        composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("scope-reentrant"),
            memoryRetrieval = MemoryRetrievalPort {
                nestedResult = composition.beginTurn(
                    CognitiveTurnId("nested"),
                    CognitiveInput("nested-private")
                )
                MemoryRetrievalResult(emptyList())
            },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "unused")
            },
            limits = limits,
            registry = registry
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("outer"), CognitiveInput("outer-private"))
        ).turn

        assertIs<CognitiveContextAssemblyResult.Published>(
            composition.assembleContext(turn.reference)
        )
        val rejected = assertIs<CognitiveTurnRegistrationResult.Rejected>(nestedResult)
        assertEquals(CognitiveTurnRegistrationFailure.LIVE_TURN_EXISTS, rejected.reason)
        assertEquals(CognitiveTurnLifecycle.CONTEXT_READY, turn.lifecycle())
        assertEquals(turn.reference, composition.currentReference())
    }

    @Test
    fun total_context_item_count_over_limit_fails_closed_without_partial_publication() {
        val limits = limits(maxContextItems = 1)
        val registry = CognitiveTurnRegistry(limits)
        val composition = CognitiveRuntimeComposition(
            foundation = foundation("count"),
            scope = CognitiveRuntimeScopeId("scope-count"),
            memoryRetrieval = MemoryRetrievalPort {
                MemoryRetrievalResult(listOf(memorySnapshot()))
            },
            knowledgeRetrieval = KnowledgeRetrievalPort {
                KnowledgeRetrievalResult(listOf(knowledgeSnapshot()))
            },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "unused")
            },
            limits = limits,
            registry = registry
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("count-bound"), CognitiveInput("private"))
        ).turn

        val result = assertIs<CognitiveContextAssemblyResult.Rejected>(
            composition.assembleContext(turn.reference)
        )

        assertEquals(CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED, result.reason)
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(registry.contextIfCurrent(turn.reference))
    }

    private fun foundation(prefix: String): FoundationComposition {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
    }

    private fun limits(maxContextItems: Int) = CognitiveRuntimeLimits(
        maxRuntimeScopeIdChars = 64,
        maxTurnIdChars = 64,
        maxInputChars = 64,
        maxContextItems = maxContextItems,
        maxContextItemChars = 64,
        maxRetrievalResults = 2,
        maxInferenceOutputChars = 64
    )

    private fun memorySnapshot() = MemoryRecordSnapshot(
        record = MemoryRecord(
            id = MemoryRecordId("memory"),
            provenance = MemoryProvenance(MemorySourceId("test")),
            content = "memory",
            createdAt = instant
        ),
        generation = MemoryGeneration(1)
    )

    private fun knowledgeSnapshot() = KnowledgeItemSnapshot(
        item = KnowledgeItem(
            id = KnowledgeItemId("knowledge"),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
            content = "knowledge",
            createdAt = instant.plusSeconds(1)
        ),
        generation = KnowledgeGeneration(1)
    )
}
