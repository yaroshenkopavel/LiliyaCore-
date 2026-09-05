package pro.liliya.core.encryption

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeOpenResult
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
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
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentMemoryKnowledgeCompositionContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("domain-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 13 + 5).toByte() })

    @Test
    fun memory_and_knowledge_reopen_exact_generation_over_encrypted_store() {
        val backend = InMemoryPersistentRecordBackend()

        val memoryStoreId = PersistentStoreId("encrypted-memory-domain")
        val memoryEncrypted = encryptedStore(backend, memoryStoreId, resolver(material))
        val firstMemory = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                memoryEncrypted,
                dekRef
            )
        ).composition
        val memory = MemoryRecord(
            id = MemoryRecordId("memory-1"),
            provenance = MemoryProvenance(MemorySourceId("conversation")),
            content = "private memory",
            createdAt = Instant.parse("2026-09-05T16:00:00Z")
        )
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            firstMemory.remember(memory)
        )

        val reopenedMemory = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                encryptedStore(backend, memoryStoreId, resolver(material)),
                dekRef
            )
        ).composition
        assertEquals(memory, reopenedMemory.find(memory.id))
        assertEquals(
            remembered.ownership.generation,
            reopenedMemory.inspect(memory.id)?.generation
        )

        val knowledgeStoreId = PersistentStoreId("encrypted-knowledge-domain")
        val firstKnowledge = assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation(),
                encryptedStore(backend, knowledgeStoreId, resolver(material)),
                dekRef
            )
        ).composition
        val knowledge = KnowledgeItem(
            id = KnowledgeItemId("knowledge-1"),
            origin = KnowledgeOrigin.Declared(KnowledgeSourceId("declared")),
            content = "private knowledge",
            createdAt = Instant.parse("2026-09-05T16:01:00Z")
        )
        val created = assertIs<PersistentKnowledgeCreateResult.Created>(
            firstKnowledge.create(knowledge)
        )

        val reopenedKnowledge = assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation(),
                encryptedStore(backend, knowledgeStoreId, resolver(material)),
                dekRef
            )
        ).composition
        assertEquals(knowledge, reopenedKnowledge.find(knowledge.id))
        assertEquals(
            created.ownership.generation,
            reopenedKnowledge.inspect(knowledge.id)?.generation
        )
    }

    @Test
    fun missing_dek_fails_reopen_closed_instead_of_restoring_empty_memory() {
        val backend = InMemoryPersistentRecordBackend()
        val storeId = PersistentStoreId("encrypted-memory-missing-dek")
        val first = assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                encryptedStore(backend, storeId, resolver(material)),
                dekRef
            )
        ).composition
        assertIs<PersistentMemoryRememberResult.Remembered>(
            first.remember(
                MemoryRecord(
                    id = MemoryRecordId("memory-secret"),
                    provenance = MemoryProvenance(MemorySourceId("conversation")),
                    content = "must not disappear into empty state",
                    createdAt = Instant.parse("2026-09-05T16:02:00Z")
                )
            )
        )

        val unavailable = assertIs<EncryptedPersistentMemoryOpenResult.EncryptionUnavailable>(
            EncryptedPersistentMemoryComposition.open(
                foundation(),
                encryptedStore(
                    backend,
                    storeId,
                    object : CognitiveDekMaterialResolver {
                        override fun resolve(
                            reference: CognitiveDekReference
                        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
                            CognitiveEncryptionResult.Rejected(
                                CognitiveEncryptionFailureCategory.DEK_MISSING
                            )
                    }
                ),
                dekRef
            )
        )
        assertEquals(CognitiveEncryptionFailureCategory.DEK_MISSING, unavailable.category)
    }

    private fun encryptedStore(
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId,
        resolver: CognitiveDekMaterialResolver
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver
        )
    }

    private fun resolver(
        material: CognitiveDekMaterial
    ) = object : CognitiveDekMaterialResolver {
        override fun resolve(
            reference: CognitiveDekReference
        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) CognitiveEncryptionResult.Success(material)
            else CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.DEK_MISSING
            )
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "encrypted-domain-${sequence.incrementAndGet()}"
            }
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> {
            val seed = next++
            return CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) { (seed + it).toByte() }
                )
            )
        }
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
