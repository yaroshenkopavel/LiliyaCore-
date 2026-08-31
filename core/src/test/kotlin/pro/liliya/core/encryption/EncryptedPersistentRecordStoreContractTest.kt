package pro.liliya.core.encryption

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class EncryptedPersistentRecordStoreContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val version = CognitiveEnvelopeVersion(1)
    private val storeId = PersistentStoreId("encrypted-memory")
    private val dekRef = CognitiveDekReference(CognitiveDekId("memory-dek"), CognitiveDekGeneration(1))
    private val dek = CognitiveDekMaterial(ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun durable_payload_is_encrypted_and_round_trips_after_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val first = adapter(openStore(backend), dek)
        val plaintext = "private cognitive memory payload".encodeToByteArray()

        val installed = assertIs<CognitiveEncryptionResult.Success<*>>(
            first.install(draft("memory-1", plaintext))
        )
        assertTrue(installed.value != null)

        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val durableBytes = loaded.state.entries.getValue(PersistentEntityId("memory-1")).record.payload.copyBytes()
        assertFalse(containsSubsequence(durableBytes, plaintext))

        val reopened = adapter(openStore(backend), dek)
        val opened = assertIs<CognitiveEncryptionResult.Success<CognitivePlaintext>>(
            reopened.open(PersistentEntityId("memory-1"))
        )
        assertContentEquals(plaintext, opened.value.copyBytes())
    }

    @Test
    fun copied_ciphertext_cannot_cross_exact_persistent_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val store = openStore(backend)
        val encrypted = adapter(store, dek)
        val first = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.persistence.PersistentRecordOwnership>>(
            encrypted.install(draft("same", "first-secret".encodeToByteArray()))
        ).value
        val firstPayload = first.record.payload.copyBytes()
        assertIs<pro.liliya.core.persistence.PersistentMutationResult.Committed>(first.remove())

        val second = assertIs<CognitiveEncryptionResult.Success<pro.liliya.core.persistence.PersistentRecordOwnership>>(
            encrypted.install(draft("same", "second-secret".encodeToByteArray()))
        ).value
        assertNotEquals(first.generation, second.generation)

        val current = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val entry = current.state.entries.getValue(PersistentEntityId("same"))
        val substituted = current.state.copy(
            entries = current.state.entries + (
                PersistentEntityId("same") to entry.copy(
                    record = entry.record.copy(payload = PersistentPayload(firstPayload))
                )
            )
        )
        backend.forceLoad(storeId, PersistentBackendLoadResult.Loaded(current.revision, substituted))

        val reopened = adapter(openStore(backend), dek)
        val result = reopened.open(PersistentEntityId("same"))
        assertIs<CognitiveEncryptionResult.Rejected>(result)
    }

    @Test
    fun authenticated_payload_tamper_fails_closed_instead_of_becoming_empty_state() {
        val backend = InMemoryPersistentRecordBackend()
        val encrypted = adapter(openStore(backend), dek)
        assertIs<CognitiveEncryptionResult.Success<*>>(
            encrypted.install(draft("tamper", "sensitive-state".encodeToByteArray()))
        )

        val current = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
        val id = PersistentEntityId("tamper")
        val entry = current.state.entries.getValue(id)
        val bytes = entry.record.payload.copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        val tampered = current.state.copy(
            entries = current.state.entries + (id to entry.copy(record = entry.record.copy(payload = PersistentPayload(bytes))))
        )
        backend.forceLoad(storeId, PersistentBackendLoadResult.Loaded(current.revision, tampered))

        val reopened = adapter(openStore(backend), dek)
        val result = reopened.open(id)
        val rejected = assertIs<CognitiveEncryptionResult.Rejected>(result)
        assertTrue(
            rejected.category == CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED ||
                rejected.category == CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
        )
    }

    @Test
    fun backend_commit_failure_does_not_publish_plaintext_or_claim_success() {
        val backend = InMemoryPersistentRecordBackend()
        backend.failNextCommit(IllegalStateException("private-backend-message"))
        val encrypted = adapter(openStore(backend), dek)
        val result = encrypted.install(draft("failed", "never-durable-plaintext".encodeToByteArray()))

        val failed = assertIs<CognitiveEncryptionResult.Failed>(result)
        assertTrue(failed.category == CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED)
        assertIs<PersistentBackendLoadResult.Missing>(backend.load(storeId))
        assertFalse(failed.toString().contains("private-backend-message"))
    }

    private fun draft(id: String, plaintext: ByteArray) = CognitivePersistentRecordDraft(
        id = PersistentEntityId(id),
        schemaId = PersistentSchemaId("memory-record"),
        schemaVersion = PersistentSchemaVersion(1),
        plaintext = CognitivePlaintext(plaintext),
        createdAt = Instant.parse("2026-08-31T08:30:00Z"),
        dek = dekRef
    )

    private fun adapter(
        store: PersistentRecordStore,
        material: CognitiveDekMaterial
    ): EncryptedPersistentRecordStore = EncryptedPersistentRecordStore(
        store = store,
        profile = profile,
        envelopeVersion = version,
        nonceSource = DeterministicNonceSource(),
        aead = DeterministicAeadProvider(),
        dekResolver = object : CognitiveDekMaterialResolver {
            override fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial> =
                if (reference == dekRef) CognitiveEncryptionResult.Success(material)
                else CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
        }
    )

    private fun openStore(backend: InMemoryPersistentRecordBackend): PersistentRecordStore {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "encrypted-persistence-${sequence.incrementAndGet()}" }
        )
        return assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation, storeId, backend)
        ).store
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(profile: CognitiveEncryptionProfile): CognitiveEncryptionResult<CognitiveNonce> {
            val seed = next++
            return CognitiveEncryptionResult.Success(
                CognitiveNonce(profile, ByteArray(profile.nonceSizeBytes) { (seed + it).toByte() })
            )
        }
    }

    /** Test-only deterministic authenticated transform; never production cryptography. */
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
                CognitiveAeadSealedData(cipher, tag(key, n, associatedData.copyBytes(), cipher))
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
                CognitivePlaintext(ByteArray(cipher.size) { i ->
                    (cipher[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
                })
            )
        }

        private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key)
            digest.update(nonce)
            digest.update(aad)
            digest.update(cipher)
            return digest.digest().copyOf(16)
        }
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
