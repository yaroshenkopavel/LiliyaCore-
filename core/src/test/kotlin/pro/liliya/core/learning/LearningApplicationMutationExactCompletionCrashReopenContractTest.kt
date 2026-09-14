package pro.liliya.core.learning

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import pro.liliya.core.authority.AuthorityPrincipal
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
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeOpenResult
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.EncryptedPersistentMemoryOpenResult
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class LearningApplicationMutationExactCompletionCrashReopenContractTest {

    private val dekRef = CognitiveDekReference(
        CognitiveDekId("p2-recovery-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 11 + 5).toByte() })

    @Test
    fun memory_crash_after_downstream_write_reopens_and_completes_exact_receipt_without_replay() {
        val backend = InMemoryPersistentRecordBackend()
        val memoryStoreId = PersistentStoreId("p2-memory-crash")
        val knowledgeStoreId = PersistentStoreId("p2-memory-unused-knowledge")
        val mutationStoreId = PersistentStoreId("p2-memory-mutation")
        val firstFoundation = foundation("memory-first")
        val memory = openMemory(firstFoundation, backend, memoryStoreId)
        val mutations = openMutations(firstFoundation, backend, mutationStoreId)
        val record = MemoryRecord(
            id = MemoryRecordId("p2-memory-record"),
            provenance = MemoryProvenance(
                MemorySourceId("cognitive-runtime-learning"),
                MemorySourceReference("p2-memory")
            ),
            content = "durable governed-learning memory",
            createdAt = Instant.parse("2026-09-13T20:00:00Z")
        )
        val plan = plan(
            suffix = "memory",
            target = LearningApplicationTarget.MEMORY,
            payload = LearningApplicationMutationPayload.Memory(record)
        )
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            mutations.prepare(plan)
        ).ownership
        val downstream = assertIs<PersistentMemoryRememberResult.Remembered>(
            memory.remember(record)
        ).ownership

        // Simulated crash boundary: durable downstream and durable Prepared exist, but no
        // claim.complete(receipt) has run. New compositions reopen only from durable state.
        val reopenedFoundation = foundation("memory-reopen")
        val reopenedMemory = openMemory(reopenedFoundation, backend, memoryStoreId)
        val reopenedKnowledge = openKnowledge(reopenedFoundation, backend, knowledgeStoreId)
        val reopenedMutations = openMutations(reopenedFoundation, backend, mutationStoreId)
        assertNotNull(reopenedMutations.inspect(plan.id))
        assertEquals(downstream.generation, assertNotNull(reopenedMemory.inspect(record.id)).generation)

        val completed = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.Completed>(
            LearningApplicationMutationExactCompletionRecovery(
                reopenedMutations,
                reopenedMemory,
                reopenedKnowledge
            ).recover()
        )
        assertEquals(1, completed.receipts.size)
        val receipt = completed.receipts.single()
        assertEquals(
            LearningApplicationMutationReference(plan.id, prepared.generation),
            receipt.mutation
        )
        assertEquals(
            LearningApplicationDownstreamReference.Memory(record.id, downstream.generation),
            receipt.downstream
        )

        val verifiedFoundation = foundation("memory-verify")
        val verifiedMemory = openMemory(verifiedFoundation, backend, memoryStoreId)
        val verifiedKnowledge = openKnowledge(verifiedFoundation, backend, knowledgeStoreId)
        val verifiedMutations = openMutations(verifiedFoundation, backend, mutationStoreId)
        assertEquals(emptyList(), verifiedMutations.snapshotEntries())
        assertEquals(receipt, verifiedMutations.completedOutcomeByMutationId(plan.id))
        assertEquals(1, verifiedMemory.snapshotEntries().size)
        val restored = assertNotNull(verifiedMemory.inspect(record.id))
        assertEquals(record, restored.record)
        assertEquals(downstream.generation, restored.generation)
        assertIs<LearningApplicationMutationExactCompletionRecoveryResult.NoRecoveryRequired>(
            LearningApplicationMutationExactCompletionRecovery(
                verifiedMutations,
                verifiedMemory,
                verifiedKnowledge
            ).recover()
        )
    }

    @Test
    fun knowledge_crash_after_downstream_write_reopens_and_completes_exact_receipt_without_replay() {
        val backend = InMemoryPersistentRecordBackend()
        val memoryStoreId = PersistentStoreId("p2-knowledge-unused-memory")
        val knowledgeStoreId = PersistentStoreId("p2-knowledge-crash")
        val mutationStoreId = PersistentStoreId("p2-knowledge-mutation")
        val firstFoundation = foundation("knowledge-first")
        val knowledge = openKnowledge(firstFoundation, backend, knowledgeStoreId)
        val mutations = openMutations(firstFoundation, backend, mutationStoreId)
        val item = KnowledgeItem(
            id = KnowledgeItemId("p2-knowledge-item"),
            origin = KnowledgeOrigin.Declared(
                KnowledgeSourceId("cognitive-runtime-learning"),
                KnowledgeSourceReference("p2-knowledge")
            ),
            content = "durable governed-learning knowledge",
            createdAt = Instant.parse("2026-09-13T20:10:00Z")
        )
        val plan = plan(
            suffix = "knowledge",
            target = LearningApplicationTarget.KNOWLEDGE,
            payload = LearningApplicationMutationPayload.Knowledge(item)
        )
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            mutations.prepare(plan)
        ).ownership
        val downstream = assertIs<PersistentKnowledgeCreateResult.Created>(
            knowledge.create(item)
        ).ownership

        val reopenedFoundation = foundation("knowledge-reopen")
        val reopenedMemory = openMemory(reopenedFoundation, backend, memoryStoreId)
        val reopenedKnowledge = openKnowledge(reopenedFoundation, backend, knowledgeStoreId)
        val reopenedMutations = openMutations(reopenedFoundation, backend, mutationStoreId)
        assertNotNull(reopenedMutations.inspect(plan.id))
        assertEquals(downstream.generation, assertNotNull(reopenedKnowledge.inspect(item.id)).generation)

        val completed = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.Completed>(
            LearningApplicationMutationExactCompletionRecovery(
                reopenedMutations,
                reopenedMemory,
                reopenedKnowledge
            ).recover()
        )
        assertEquals(1, completed.receipts.size)
        val receipt = completed.receipts.single()
        assertEquals(
            LearningApplicationMutationReference(plan.id, prepared.generation),
            receipt.mutation
        )
        assertEquals(
            LearningApplicationDownstreamReference.Knowledge(item.id, downstream.generation),
            receipt.downstream
        )

        val verifiedFoundation = foundation("knowledge-verify")
        val verifiedMemory = openMemory(verifiedFoundation, backend, memoryStoreId)
        val verifiedKnowledge = openKnowledge(verifiedFoundation, backend, knowledgeStoreId)
        val verifiedMutations = openMutations(verifiedFoundation, backend, mutationStoreId)
        assertEquals(emptyList(), verifiedMutations.snapshotEntries())
        assertEquals(receipt, verifiedMutations.completedOutcomeByMutationId(plan.id))
        assertEquals(1, verifiedKnowledge.snapshotEntries().size)
        val restored = assertNotNull(verifiedKnowledge.inspect(item.id))
        assertEquals(item, restored.item)
        assertEquals(downstream.generation, restored.generation)
        assertIs<LearningApplicationMutationExactCompletionRecoveryResult.NoRecoveryRequired>(
            LearningApplicationMutationExactCompletionRecovery(
                verifiedMutations,
                verifiedMemory,
                verifiedKnowledge
            ).recover()
        )
    }

    private fun plan(
        suffix: String,
        target: LearningApplicationTarget,
        payload: LearningApplicationMutationPayload
    ): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId("p2-mutation-$suffix"),
        application = LearningApplicationIntentReference(
            LearningApplicationId("p2-application-$suffix"),
            LearningApplicationGeneration(1)
        ),
        principal = AuthorityPrincipal("p2-original-authority-owner"),
        target = target,
        idempotencyKey = LearningApplicationIdempotencyKey("p2-idempotency-$suffix"),
        payload = payload,
        createdAt = Instant.parse("2026-09-13T20:20:00Z")
    )

    private fun openMutations(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentLearningApplicationMutationComposition =
        assertIs<EncryptedPersistentLearningApplicationMutationOpenResult.Opened>(
            EncryptedPersistentLearningApplicationMutationComposition.open(
                foundation = foundation,
                encryptedStore = encryptedStore(foundation, backend, storeId),
                dek = dekRef
            )
        ).composition

    private fun openMemory(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentMemoryComposition =
        assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation,
                encryptedStore(foundation, backend, storeId),
                dekRef
            )
        ).composition

    private fun openKnowledge(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentKnowledgeComposition =
        assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation,
                encryptedStore(foundation, backend, storeId),
                dekRef
            )
        ).composition

    private fun encryptedStore(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation, storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = CognitiveEncryptionProfile.AES_256_GCM,
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

    private fun foundation(label: String): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "p2-$label-" + sequence.incrementAndGet()
            }
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) { (next++ + it).toByte() }
                )
            )
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
        ): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(key + nonce + aad + cipher)
            .copyOf(16)
    }
}
