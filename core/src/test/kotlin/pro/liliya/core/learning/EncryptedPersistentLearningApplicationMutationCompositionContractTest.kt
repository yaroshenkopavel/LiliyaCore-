package pro.liliya.core.learning

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentLearningApplicationMutationCompositionContractTest {

    private val storeId = PersistentStoreId("encrypted-learning-mutations")
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("encrypted-learning-dek"),
        CognitiveDekGeneration(1)
    )
    private val material =
        CognitiveDekMaterial(ByteArray(32) { (it * 11 + 7).toByte() })

    @Test
    fun prepared_and_completed_learning_payload_never_appears_plaintext_and_reopens() {
        val backend = InMemoryPersistentRecordBackend()
        val foundation = foundation()
        val encrypted = encryptedStore(foundation, backend)

        val mutations =
            assertIs<EncryptedPersistentLearningApplicationMutationOpenResult.Opened>(
                EncryptedPersistentLearningApplicationMutationComposition.open(
                    foundation = foundation,
                    encryptedStore = encrypted,
                    activeDek = dekRef
                )
            ).composition

        val learnedContent = "private durable learned evidence must stay encrypted"
        val plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("encrypted-mutation-1"),
            application = LearningApplicationIntentReference(
                LearningApplicationId("encrypted-application-1"),
                LearningApplicationGeneration(1)
            ),
            principal = AuthorityPrincipal("encrypted-learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey("encrypted-idem-1"),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId("encrypted-learned-memory"),
                    provenance = MemoryProvenance(
                        MemorySourceId("cognitive-learning")
                    ),
                    content = learnedContent,
                    createdAt = Instant.parse("2026-09-07T01:00:00Z")
                )
            ),
            createdAt = Instant.parse("2026-09-07T01:01:00Z")
        )

        val prepared =
            assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
                mutations.prepare(plan)
            ).ownership

        assertBackendDoesNotContain(backend, learnedContent)

        val reference = LearningApplicationMutationReference(
            plan.id,
            prepared.generation
        )
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            mutations.claim(reference)
        ).claim
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = reference,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("encrypted-learned-memory"),
                generation = MemoryGeneration(7)
            )
        )

        assertEquals(
            PersistentLearningApplicationMutationResult.Committed,
            claim.complete(receipt)
        )
        assertBackendDoesNotContain(backend, learnedContent)

        val raw = assertIs<PersistentBackendLoadResult.Loaded>(
            backend.load(storeId)
        )
        assertEquals(1, raw.state.entries.size)
        assertEquals(
            "learning-mutation:completed:encrypted-mutation-1",
            raw.state.entries.keys.single().value
        )

        val reopened =
            assertIs<EncryptedPersistentLearningApplicationMutationOpenResult.Opened>(
                EncryptedPersistentLearningApplicationMutationComposition.open(
                    foundation = foundation(),
                    encryptedStore = encryptedStore(foundation(), backend),
                    activeDek = dekRef
                )
            ).composition

        assertEquals(
            receipt,
            reopened.completedOutcomeByMutationId(plan.id)
        )
        assertEquals(
            receipt,
            reopened.completedOutcomeByIdempotencyKey(plan.idempotencyKey)
        )
        assertNotNull(reopened.findByIdempotencyKey(plan.idempotencyKey))
    }

    private fun assertBackendDoesNotContain(
        backend: InMemoryPersistentRecordBackend,
        secret: String
    ) {
        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(
            backend.load(storeId)
        )
        val needle = secret.encodeToByteArray()
        loaded.state.entries.values.forEach { entry ->
            val bytes = entry.record.payload.copyBytes()
            try {
                assertFalse(containsSubsequence(bytes, needle))
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray
    ): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    private fun encryptedStore(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(
                foundation = foundation,
                storeId = storeId,
                backend = backend
            )
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

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, writer)
            },
            correlationIds = CorrelationIdGenerator {
                "encrypted-learning-mutation-" + sequence.incrementAndGet()
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
                    ByteArray(profile.nonceSizeBytes) {
                        (seed + it).toByte()
                    }
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
                (
                    plain[i].toInt() xor
                        key[i % key.size].toInt() xor
                        n[i % n.size].toInt()
                    ).toByte()
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
            val expected = tag(
                key,
                n,
                associatedData.copyBytes(),
                cipher
            )
            if (!MessageDigest.isEqual(
                    expected,
                    sealed.copyAuthenticationTag()
                )
            ) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory
                        .CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }

            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (
                            cipher[i].toInt() xor
                                key[i % key.size].toInt() xor
                                n[i % n.size].toInt()
                            ).toByte()
                    }
                )
            )
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            cipher: ByteArray
        ): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(key + nonce + aad + cipher)
                .copyOf(16)
    }
}
