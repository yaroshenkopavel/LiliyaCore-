package pro.liliya.core.cognitive

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class CognitiveConversationReconstructionBudgetContractTest {
    @Test
    fun reconstruction_uses_newest_bounded_tail_and_preserves_original_order() {
        val limits = limits(maxItems = 2, maxChars = 128)
        val f = fixture(limits)
        val conversation = conversation(
            msg(1, CognitiveConversationRole.USER, "one"),
            msg(2, CognitiveConversationRole.ASSISTANT, "two"),
            msg(3, CognitiveConversationRole.USER, "three"),
            msg(4, CognitiveConversationRole.ASSISTANT, "four")
        )
        val turn = begin(f, "bounded-items", conversation)

        val published = assertIs<CognitiveContextAssemblyResult.Published>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(2, published.itemCount)
        assertEquals(listOf(3L, 4L), conversationSequences(f, turn.reference))
        assertEquals(listOf("three", "four"), conversationContents(f, turn.reference))
        assertEquals(listOf(1L, 2L, 3L, 4L), conversation.messages.map { it.sequence.value })
    }

    @Test
    fun reconstruction_char_budget_keeps_maximal_contiguous_newest_tail_without_truncating_messages() {
        val limits = limits(maxItems = 8, maxChars = 5)
        val f = fixture(limits)
        val conversation = conversation(
            msg(1, CognitiveConversationRole.USER, "1111"),
            msg(2, CognitiveConversationRole.ASSISTANT, "22"),
            msg(3, CognitiveConversationRole.USER, "333")
        )
        val turn = begin(f, "bounded-chars", conversation)

        assertIs<CognitiveContextAssemblyResult.Published>(f.composition.assembleContext(turn.reference))

        assertEquals(listOf(2L, 3L), conversationSequences(f, turn.reference))
        assertEquals(listOf("22", "333"), conversationContents(f, turn.reference))
    }

    @Test
    fun newest_message_over_reconstruction_budget_fails_closed_without_context_publication() {
        val limits = limits(maxItems = 8, maxChars = 3)
        val f = fixture(limits)
        val conversation = conversation(
            msg(1, CognitiveConversationRole.USER, "ok"),
            msg(2, CognitiveConversationRole.ASSISTANT, "four")
        )
        val turn = begin(f, "newest-over-budget", conversation)

        val rejected = assertIs<CognitiveContextAssemblyResult.Rejected>(
            f.composition.assembleContext(turn.reference)
        )

        assertEquals(
            CognitiveContextAssemblyFailure.CONVERSATION_RECONSTRUCTION_LIMIT_REJECTED,
            rejected.reason
        )
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(f.registry.contextIfCurrent(turn.reference))
    }

    @Test
    fun same_reopened_snapshot_reconstructs_identically_without_duplicate_replay() {
        val limits = limits(maxItems = 3, maxChars = 9)
        val reopened = conversation(
            msg(7, CognitiveConversationRole.USER, "old"),
            msg(8, CognitiveConversationRole.ASSISTANT, "reply"),
            msg(9, CognitiveConversationRole.USER, "new"),
            msg(10, CognitiveConversationRole.ASSISTANT, "done")
        )

        val first = fixture(limits)
        val firstTurn = begin(first, "restart-a", reopened)
        assertIs<CognitiveContextAssemblyResult.Published>(
            first.composition.assembleContext(firstTurn.reference)
        )

        val second = fixture(limits)
        val secondTurn = begin(second, "restart-b", reopened)
        assertIs<CognitiveContextAssemblyResult.Published>(
            second.composition.assembleContext(secondTurn.reference)
        )

        val firstSequences = conversationSequences(first, firstTurn.reference)
        val secondSequences = conversationSequences(second, secondTurn.reference)
        assertEquals(listOf(9L, 10L), firstSequences)
        assertEquals(firstSequences, secondSequences)
        assertEquals(firstSequences.size, firstSequences.distinct().size)
        assertEquals(listOf(7L, 8L, 9L, 10L), reopened.messages.map { it.sequence.value })
    }

    private data class Fixture(
        val registry: CognitiveTurnRegistry,
        val composition: CognitiveRuntimeComposition
    )

    private fun fixture(limits: CognitiveRuntimeLimits): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "conversation-reconstruction-${sequence.incrementAndGet()}" }
        )
        val registry = CognitiveTurnRegistry(limits)
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("scope-conversation-reconstruction"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "unused")
            },
            limits = limits,
            registry = registry
        )
        return Fixture(registry, composition)
    }

    private fun limits(maxItems: Int, maxChars: Int): CognitiveRuntimeLimits =
        CognitiveRuntimeLimits(
            maxInputChars = 128,
            maxContextItems = 8,
            maxContextItemChars = 128,
            maxRetrievalResults = 2,
            maxInferenceOutputChars = 128,
            maxConversationReconstructionItems = maxItems,
            maxConversationReconstructionChars = maxChars
        )

    private fun begin(
        fixture: Fixture,
        id: String,
        conversation: CognitiveConversationContextSnapshot
    ): CognitiveTurnHandle = assertIs<CognitiveTurnRegistrationResult.Registered>(
        fixture.composition.beginTurn(
            CognitiveTurnId(id),
            CognitiveInput("current input"),
            conversation
        )
    ).turn

    private fun conversation(vararg messages: CognitiveConversationContextMessage) =
        CognitiveConversationContextSnapshot(
            CognitiveConversationSessionId("durable-session"),
            messages.toList()
        )

    private fun msg(sequence: Long, role: CognitiveConversationRole, content: String) =
        CognitiveConversationContextMessage(CognitiveConversationSequence(sequence), role, content)

    private fun conversationSequences(fixture: Fixture, reference: CognitiveTurnReference): List<Long> =
        fixture.registry.contextIfCurrent(reference)!!.items
            .filter { it.source is CognitiveContextSourceReference.Conversation }
            .map { (it.source as CognitiveContextSourceReference.Conversation).sequence.value }

    private fun conversationContents(fixture: Fixture, reference: CognitiveTurnReference): List<String> =
        fixture.registry.contextIfCurrent(reference)!!.items
            .filter { it.source is CognitiveContextSourceReference.Conversation }
            .map { it.content }
}
