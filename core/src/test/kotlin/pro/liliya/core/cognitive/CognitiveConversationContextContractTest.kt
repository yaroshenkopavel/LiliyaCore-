package pro.liliya.core.cognitive

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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

class CognitiveConversationContextContractTest {

    @Test
    fun conversation_models_validate_and_redact_private_content() {
        assertFailsWith<IllegalArgumentException> {
            CognitiveConversationSessionId("")
        }
        assertFailsWith<IllegalArgumentException> {
            CognitiveConversationSequence(0)
        }
        assertFailsWith<IllegalArgumentException> {
            CognitiveConversationContextMessage(
                sequence = CognitiveConversationSequence(1),
                role = CognitiveConversationRole.USER,
                content = ""
            )
        }

        val session = CognitiveConversationSessionId("PRIVATE-SESSION-ID")
        val message = CognitiveConversationContextMessage(
            sequence = CognitiveConversationSequence(1),
            role = CognitiveConversationRole.USER,
            content = "PRIVATE-CONVERSATION-CONTENT"
        )
        val snapshot = CognitiveConversationContextSnapshot(session, listOf(message))

        assertFalse("PRIVATE-SESSION-ID" in session.toString())
        assertFalse("PRIVATE-SESSION-ID" in snapshot.toString())
        assertFalse("PRIVATE-CONVERSATION-CONTENT" in message.toString())
        assertFalse("PRIVATE-CONVERSATION-CONTENT" in snapshot.toString())
        assertTrue("USER" in message.toString())
    }

    @Test
    fun snapshot_detaches_source_list_and_requires_strict_sequence_order() {
        val messages = mutableListOf(
            message(1, CognitiveConversationRole.USER, "one")
        )
        val snapshot = CognitiveConversationContextSnapshot(
            CognitiveConversationSessionId("session"),
            messages
        )

        messages += message(2, CognitiveConversationRole.ASSISTANT, "two")

        assertEquals(1, snapshot.messages.size)
        assertEquals("one", snapshot.messages.single().content)

        assertFailsWith<IllegalArgumentException> {
            CognitiveConversationContextSnapshot(
                CognitiveConversationSessionId("session"),
                listOf(
                    message(2, CognitiveConversationRole.USER, "two"),
                    message(1, CognitiveConversationRole.ASSISTANT, "one")
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CognitiveConversationContextSnapshot(
                CognitiveConversationSessionId("session"),
                listOf(
                    message(1, CognitiveConversationRole.USER, "one"),
                    message(1, CognitiveConversationRole.ASSISTANT, "duplicate")
                )
            )
        }
    }

    @Test
    fun exact_turn_owns_detached_conversation_and_stale_reference_cannot_read_replacement() {
        val registry = CognitiveTurnRegistry(CognitiveRuntimeLimits())
        val source = mutableListOf(
            message(1, CognitiveConversationRole.USER, "prior user")
        )
        val snapshot = CognitiveConversationContextSnapshot(
            CognitiveConversationSessionId("session-exact"),
            source
        )

        val first = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(
                CognitiveTurnId("turn-first"),
                CognitiveInput("current"),
                snapshot
            )
        ).turn.reference

        source += message(2, CognitiveConversationRole.ASSISTANT, "late mutation")

        val stored = registry.conversationIfCurrent(first)
        assertEquals(1, stored?.messages?.size)
        assertEquals("prior user", stored?.messages?.single()?.content)

        registry.failIfCurrent(first)
        assertNull(registry.conversationIfCurrent(first))

        val replacement = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(
                CognitiveTurnId("turn-replacement"),
                CognitiveInput("replacement"),
                CognitiveConversationContextSnapshot(
                    CognitiveConversationSessionId("session-replacement"),
                    listOf(message(1, CognitiveConversationRole.ASSISTANT, "replacement prior"))
                )
            )
        ).turn.reference

        assertNull(registry.conversationIfCurrent(first))
        assertEquals(
            "replacement prior",
            registry.conversationIfCurrent(replacement)?.messages?.single()?.content
        )
    }

    @Test
    fun assembler_places_conversation_before_memory_and_knowledge_in_exact_order() {
        val registry = CognitiveTurnRegistry(
            CognitiveRuntimeLimits(
                maxContextItems = 8,
                maxContextItemChars = 128,
                maxRetrievalResults = 4
            )
        )
        val reference = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(
                CognitiveTurnId("turn-context"),
                CognitiveInput("current input"),
                CognitiveConversationContextSnapshot(
                    CognitiveConversationSessionId("session-context"),
                    listOf(
                        message(1, CognitiveConversationRole.USER, "prior user"),
                        message(2, CognitiveConversationRole.ASSISTANT, "prior assistant")
                    )
                )
            )
        ).turn.reference

        val assembler = CognitiveContextAssembler(
            turns = registry,
            memoryRetrieval = MemoryRetrievalPort {
                MemoryRetrievalResult(listOf(memorySnapshot()))
            },
            knowledgeRetrieval = KnowledgeRetrievalPort {
                KnowledgeRetrievalResult(listOf(knowledgeSnapshot()))
            },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            limits = CognitiveRuntimeLimits(
                maxContextItems = 8,
                maxContextItemChars = 128,
                maxRetrievalResults = 4
            )
        )

        assertEquals(
            CognitiveContextAssemblyResult.Published(4),
            assembler.assemble(reference)
        )

        val items = requireNotNull(registry.contextIfCurrent(reference)).items
        assertEquals(
            listOf("prior user", "prior assistant", "memory evidence", "knowledge evidence"),
            items.map { it.content }
        )
        assertIs<CognitiveContextSourceReference.Conversation>(items[0].source)
        assertEquals(
            CognitiveConversationRole.USER,
            (items[0].source as CognitiveContextSourceReference.Conversation).role
        )
        assertEquals(
            CognitiveConversationRole.ASSISTANT,
            (items[1].source as CognitiveContextSourceReference.Conversation).role
        )
        assertIs<CognitiveContextSourceReference.Memory>(items[2].source)
        assertIs<CognitiveContextSourceReference.Knowledge>(items[3].source)
    }

    @Test
    fun assembler_rejects_conversation_that_exhausts_global_context_bound() {
        val limits = CognitiveRuntimeLimits(
            maxContextItems = 1,
            maxContextItemChars = 128,
            maxRetrievalResults = 1
        )
        val registry = CognitiveTurnRegistry(limits)
        val reference = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(
                CognitiveTurnId("turn-limit"),
                CognitiveInput("current"),
                CognitiveConversationContextSnapshot(
                    CognitiveConversationSessionId("session-limit"),
                    listOf(
                        message(1, CognitiveConversationRole.USER, "one"),
                        message(2, CognitiveConversationRole.ASSISTANT, "two")
                    )
                )
            )
        ).turn.reference

        val assembler = CognitiveContextAssembler(
            turns = registry,
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            limits = limits
        )

        assertEquals(
            CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED
            ),
            assembler.assemble(reference)
        )
    }

    @Test
    fun deterministic_compiler_projects_roles_without_session_identity() {
        val turn = CognitiveTurnReference(
            CognitiveTurnId("turn-compiler"),
            CognitiveTurnGeneration(1)
        )
        val context = CognitiveContextSnapshot(
            turn,
            listOf(
                CognitiveContextItem(
                    CognitiveContextSourceReference.Conversation(
                        CognitiveConversationSessionId("PRIVATE-SESSION-COMPILER"),
                        CognitiveConversationSequence(11),
                        CognitiveConversationRole.USER
                    ),
                    "prior user text"
                ),
                CognitiveContextItem(
                    CognitiveContextSourceReference.Conversation(
                        CognitiveConversationSessionId("PRIVATE-SESSION-COMPILER"),
                        CognitiveConversationSequence(12),
                        CognitiveConversationRole.ASSISTANT
                    ),
                    "prior assistant text"
                )
            )
        )
        val inference = CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput("current input"),
            context = context,
            maxOutputChars = 4_096
        )
        val result = assertIs<CognitiveModelRequestCompilerResult.Compiled>(
            DeterministicCognitiveModelRequestCompiler().compile(
                CognitiveModelRequestCompilerRequest(
                    inference = inference,
                    maxPromptChars = 16_384,
                    responseBudgets = CognitiveStructuredResponseBudgets.from(
                        CognitiveRuntimeLimits()
                    )
                )
            )
        )

        val prompt = result.request.prompt
        assertTrue(prompt.contains("1:CONVERSATION_USER\nprior user text"))
        assertTrue(prompt.contains("2:CONVERSATION_ASSISTANT\nprior assistant text"))
        assertTrue(prompt.indexOf("prior user text") < prompt.indexOf("prior assistant text"))
        assertFalse(prompt.contains("PRIVATE-SESSION-COMPILER"))
        assertTrue(prompt.indexOf("\nINPUT\ncurrent input") < prompt.indexOf("\nCONTEXT"))
    }

    private fun message(
        sequence: Long,
        role: CognitiveConversationRole,
        content: String
    ): CognitiveConversationContextMessage =
        CognitiveConversationContextMessage(
            CognitiveConversationSequence(sequence),
            role,
            content
        )

    private fun memorySnapshot(): MemoryRecordSnapshot =
        MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId("memory-1"),
                provenance = MemoryProvenance(MemorySourceId("test")),
                content = "memory evidence",
                createdAt = Instant.parse("2026-09-08T00:00:00Z")
            ),
            generation = MemoryGeneration(1)
        )

    private fun knowledgeSnapshot(): KnowledgeItemSnapshot =
        KnowledgeItemSnapshot(
            item = KnowledgeItem(
                id = KnowledgeItemId("knowledge-1"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
                content = "knowledge evidence",
                createdAt = Instant.parse("2026-09-08T00:00:01Z")
            ),
            generation = KnowledgeGeneration(1)
        )
}
