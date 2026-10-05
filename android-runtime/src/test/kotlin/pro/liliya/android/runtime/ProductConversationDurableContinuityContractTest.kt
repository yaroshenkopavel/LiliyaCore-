package pro.liliya.android.runtime

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveConversationContextSnapshot
import pro.liliya.core.cognitive.CognitiveConversationRole
import pro.liliya.core.cognitive.CognitiveConversationSessionId
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveReflectionReference
import pro.liliya.core.cognitive.CognitiveResult
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.DecisionReference
import pro.liliya.core.cognitive.EncryptedPersistentConversationStore
import pro.liliya.core.cognitive.PersistentConversationOpenResult
import pro.liliya.core.cognitive.PlanningReference
import pro.liliya.core.cognitive.ReasoningReference
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveAeadProvider
import pro.liliya.core.encryption.CognitiveAeadSealedData
import pro.liliya.core.encryption.CognitiveAssociatedData
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekMaterialResolver
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionProfile
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveNonce
import pro.liliya.core.encryption.CognitiveNonceSource
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningGeneration
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

class ProductConversationDurableContinuityContractTest {
    private val storeId = PersistentStoreId("product-conversation-continuity")
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("product-conversation-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun completed_pair_reopens_after_product_host_recreation_and_sequences_continue() {
        val backend = TestPersistentRecordBackend()
        val session = CognitiveConversationSessionId("stable-product-session")
        val firstStore = openConversation(backend)
        val firstSnapshots = mutableListOf<CognitiveConversationContextSnapshot>()

        val first = host(
            session = session,
            store = firstStore,
            snapshots = firstSnapshots,
            reply = "first reply",
            timestamp = Instant.parse("2026-09-18T08:00:00Z")
        )

        val firstResult = assertIs<ProductConversationResult.Completed>(
            first.send(ProductChatRequest("first user", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(ProductConversationCommitStatus.COMMITTED, firstResult.conversationCommit)
        assertEquals(0, firstSnapshots.single().messages.size)

        val reopenedStore = openConversation(backend)
        val restored = assertNotNull(reopenedStore.reopen(session))
        assertEquals(
            listOf("first user", "first reply"),
            restored.messages.map { it.content }
        )
        assertEquals(listOf(1L, 2L), restored.messages.map { it.sequence.value })

        val secondSnapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        val second = host(
            session = session,
            store = reopenedStore,
            snapshots = secondSnapshots,
            reply = "second reply",
            timestamp = Instant.parse("2026-09-18T08:01:00Z"),
            initial = restored
        )

        val secondResult = assertIs<ProductConversationResult.Completed>(
            second.send(ProductChatRequest("second user", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(ProductConversationCommitStatus.COMMITTED, secondResult.conversationCommit)
        assertEquals(
            listOf("first user", "first reply"),
            secondSnapshots.single().messages.map { it.content }
        )

        val finalStore = openConversation(backend)
        val final = assertNotNull(finalStore.reopen(session))
        assertEquals(
            listOf("first user", "first reply", "second user", "second reply"),
            final.messages.map { it.content }
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), final.messages.map { it.sequence.value })
        assertEquals(
            listOf(
                CognitiveConversationRole.USER,
                CognitiveConversationRole.ASSISTANT,
                CognitiveConversationRole.USER,
                CognitiveConversationRole.ASSISTANT
            ),
            final.messages.map { it.role }
        )
    }


    @Test
    fun restart_restores_only_conversation_context_and_does_not_replay_prior_learning_evidence() {
        val backend = TestPersistentRecordBackend()
        val session = CognitiveConversationSessionId("restart-descriptive-only-session")
        val firstStore = openConversation(backend)
        val firstSnapshots = mutableListOf<CognitiveConversationContextSnapshot>()

        val first = host(
            session = session,
            store = firstStore,
            snapshots = firstSnapshots,
            reply = "first reply",
            timestamp = Instant.parse("2026-09-18T08:10:00Z"),
            learningCandidateId = "learning-before-restart"
        )
        val beforeRestart = assertIs<ProductConversationResult.Completed>(
            first.send(ProductChatRequest("first user", ProductChatGenerationMode.ONE_SHOT))
        )
        val oldLearning = assertNotNull(beforeRestart.learningFollowUpReference())

        val reopenedStore = openConversation(backend)
        val restored = assertNotNull(reopenedStore.reopen(session))
        assertEquals(
            listOf("first user", "first reply"),
            restored.messages.map { it.content }
        )

        val afterRestartSnapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var postRestartTurnCalls = 0
        val recreated = host(
            session = session,
            store = reopenedStore,
            snapshots = afterRestartSnapshots,
            reply = "second reply",
            timestamp = Instant.parse("2026-09-18T08:11:00Z"),
            initial = restored,
            learningCandidateId = "learning-after-restart",
            onTurn = { postRestartTurnCalls += 1 }
        )

        // Reopen itself is descriptive-only: it restores context but executes no turn and
        // manufactures no new learning/permission side effect.
        assertEquals(0, postRestartTurnCalls)
        assertEquals(0, afterRestartSnapshots.size)

        val afterRestart = assertIs<ProductConversationResult.Completed>(
            recreated.send(ProductChatRequest("second user", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(1, postRestartTurnCalls)
        assertEquals(
            listOf("first user", "first reply"),
            afterRestartSnapshots.single().messages.map { it.content }
        )
        val freshLearning = assertNotNull(afterRestart.learningFollowUpReference())
        assertNotEquals(oldLearning.cognitive.id, freshLearning.cognitive.id)
    }


    private fun host(
        session: CognitiveConversationSessionId,
        store: EncryptedPersistentConversationStore,
        snapshots: MutableList<CognitiveConversationContextSnapshot>,
        reply: String,
        timestamp: Instant,
        initial: CognitiveConversationContextSnapshot? = null,
        learningCandidateId: String = "learning",
        onTurn: () -> Unit = {}
    ): ProductConversationHost =
        ProductConversationHost(
            sessionId = session,
            maxInputChars = 128,
            maxTurnIdChars = 64,
            maxRetainedMessages = 8,
            maxRetainedCharacters = 1_024,
            maxMessageCharacters = 256,
            turnIds = ProductConversationTurnIdSource { "durable-product-turn" },
            turns = ProductConversationTurnRunner { request, conversation, _ ->
                onTurn()
                snapshots += conversation
                completed(request.input, reply, learningCandidateId)
            },
            initialSnapshot = initial,
            persistence = object : ProductConversationPersistencePort {
                override fun reopen(
                    sessionId: CognitiveConversationSessionId
                ): CognitiveConversationContextSnapshot? = store.reopen(sessionId)

                override fun appendPair(
                    sessionId: CognitiveConversationSessionId,
                    user: pro.liliya.core.cognitive.CognitiveConversationContextMessage,
                    assistant: pro.liliya.core.cognitive.CognitiveConversationContextMessage,
                    persistedAt: Instant
                ): ProductConversationPersistenceAppendResult =
                    when (
                        store.appendPair(
                            sessionId = sessionId,
                            user = user,
                            assistant = assistant,
                            persistedAt = persistedAt
                        )
                    ) {
                        is pro.liliya.core.cognitive.PersistentConversationAppendPairResult.Appended,
                        is pro.liliya.core.cognitive.PersistentConversationAppendPairResult.AlreadyPresent ->
                            ProductConversationPersistenceAppendResult.Committed
                        else -> ProductConversationPersistenceAppendResult.Rejected
                    }
            },
            timestamps = CognitiveTimestampSource { timestamp }
        )

    private fun completed(
        input: CognitiveInput,
        reply: String,
        learningCandidateId: String = "learning"
    ): ProductTurnResult.Completed {
        val turn = CognitiveTurnReference(
            CognitiveTurnId("durable-product-turn"),
            CognitiveTurnGeneration(1)
        )
        require(input.text.isNotBlank())
        return ProductTurnResult.Completed(
            turn = turn,
            finalization = CognitiveFinalizationResult.Completed(
                result = CognitiveResult(
                    turn = turn,
                    planning = PlanningReference(PlanningProposalId("planning"), PlanningGeneration(1)),
                    reasoning = ReasoningReference(ReasoningArtifactId("reasoning"), ReasoningGeneration(1)),
                    decision = DecisionReference(DecisionId("decision"), DecisionGeneration(1)),
                    content = reply,
                    createdAt = Instant.parse("2026-09-18T08:00:00Z")
                ),
                reflection = CognitiveReflectionReference(
                    ReflectionRecordId("reflection"),
                    ReflectionGeneration(1)
                ),
                learning = CognitiveLearningReference(
                    LearningCandidateId(learningCandidateId),
                    LearningGeneration(1)
                )
            ),
            streamedChunkCount = 0,
            streamedCharacterCount = 0
        )
    }

    private fun openConversation(
        backend: TestPersistentRecordBackend
    ): EncryptedPersistentConversationStore =
        assertIs<PersistentConversationOpenResult.Opened>(
            EncryptedPersistentConversationStore.open(
                encryptedStore = encryptedStore(backend),
                activeDek = dekRef,
                maxRetainedMessages = 8,
                maxMessageChars = 256
            )
        ).store

    private fun encryptedStore(
        backend: TestPersistentRecordBackend
    ): EncryptedPersistentRecordStore {
        val raw = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = raw,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = object : CognitiveDekMaterialResolver {
                override fun resolve(
                    reference: CognitiveDekReference
                ): CognitiveEncryptionResult<CognitiveDekMaterial> =
                    if (reference == dekRef) {
                        CognitiveEncryptionResult.Success(material)
                    } else {
                        CognitiveEncryptionResult.Rejected(
                            CognitiveEncryptionFailureCategory.DEK_MISSING
                        )
                    }
            }
        )
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "product-conversation-" + sequence.incrementAndGet()
            }
        )
    }

    private class TestPersistentRecordBackend : PersistentRecordBackend {
        private data class Stored(
            val revision: Long,
            val state: PersistentBackendState
        )

        private val stores = mutableMapOf<PersistentStoreId, Stored>()

        @Synchronized
        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
            val stored = stores[storeId] ?: return PersistentBackendLoadResult.Missing
            return PersistentBackendLoadResult.Loaded(
                revision = stored.revision,
                state = stored.state.detached()
            )
        }

        @Synchronized
        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult {
            val currentRevision = stores[storeId]?.revision ?: 0L
            if (currentRevision != expectedRevision) {
                return PersistentBackendCommitResult.Conflict
            }
            val nextRevision = currentRevision + 1L
            stores[storeId] = Stored(nextRevision, state.detached())
            return PersistentBackendCommitResult.Committed(nextRevision)
        }

        private fun PersistentBackendState.detached(): PersistentBackendState =
            copy(
                entries = entries.mapValues { (_, entry) ->
                    entry.copy(record = entry.record.detached())
                }.toMap()
            )

        private fun PersistentRecord.detached(): PersistentRecord =
            copy(payload = PersistentPayload(payload.copyBytes()))
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) { (next + it).toByte() }
                )
            ).also { next++ }
    }

    private class DeterministicAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val plain = plaintext.copyBytes()
            val cipher = ByteArray(plain.size) { i ->
                (plain[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
            }
            return CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    cipher,
                    tag(key, n, associatedData.copyBytes(), cipher)
                )
            )
        }

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val cipher = sealed.copyCiphertext()
            val expected = tag(key, n, associatedData.copyBytes(), cipher)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (cipher[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
                    }
                )
            )
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            cipher: ByteArray
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key)
            digest.update(nonce)
            digest.update(aad)
            digest.update(cipher)
            return digest.digest().copyOf(16)
        }
    }
}
