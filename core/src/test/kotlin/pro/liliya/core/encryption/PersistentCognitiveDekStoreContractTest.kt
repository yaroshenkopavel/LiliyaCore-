package pro.liliya.core.encryption

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.IndexedPersistentRecordMutationBackend
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendEntryLoadResult
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendMetadataLoadResult
import pro.liliya.core.persistence.PersistentBackendMutationResult
import pro.liliya.core.persistence.PersistentBackendPageLoadResult
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentStoreId

class PersistentCognitiveDekStoreContractTest {
    private class CorruptExactIndexedBackend : IndexedPersistentRecordMutationBackend {
        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
            PersistentBackendLoadResult.Failed("legacy load must not be used")

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            PersistentBackendCommitResult.Failed("legacy commit must not be used")

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            PersistentBackendMetadataLoadResult.Missing

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult =
            PersistentBackendEntryLoadResult.Corrupt

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult =
            PersistentBackendPageLoadResult.Missing

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unexpected install")

        override fun transitionEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            sourceId: PersistentEntityId,
            sourceGeneration: PersistentGeneration,
            replacement: PersistentBackendEntry
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unexpected transition")

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Failed("unexpected remove")
    }


    @Test
    fun register_persists_wrapped_only_and_reopen_resolves_exact_material() {
        val backend = InMemoryPersistentRecordBackend()
        val materialBytes = ByteArray(32) { (it * 11 + 7).toByte() }
        val material = CognitiveDekMaterial(materialBytes)
        val protector = FakeProtector()
        val descriptor = protector.descriptor()

        val first = open(backend, protector, FixedMaterialSource(material))
        val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            first.register(CognitiveDekId("memory-main"), descriptor)
        )
        assertEquals(1L, registered.ownership.reference.generation.value)

        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(
            backend.load(PersistentCognitiveDekStore.STORE_ID)
        )
        val durable = loaded.state.entries.values.single().record.payload.copyBytes()
        assertFalse(containsSubsequence(durable, materialBytes))

        val reopened = open(backend, protector, FixedMaterialSource(material))
        assertEquals(
            listOf(registered.ownership.reference),
            reopened.snapshotReferences()
        )
        val resolved = assertIs<CognitiveEncryptionResult.Success<CognitiveDekMaterial>>(
            reopened.resolve(registered.ownership.reference)
        )
        assertContentEquals(materialBytes, resolved.value.copyBytes())
    }

    @Test
    fun duplicate_live_id_is_rejected_and_generation_advances_after_retirement() {
        val backend = InMemoryPersistentRecordBackend()
        val material = CognitiveDekMaterial(ByteArray(32) { (it + 1).toByte() })
        val protector = FakeProtector()
        val descriptor = protector.descriptor()
        val store = open(backend, protector, FixedMaterialSource(material))

        val first = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            store.register(CognitiveDekId("same"), descriptor)
        )
        val duplicate = assertIs<PersistentCognitiveDekRegistrationResult.Rejected>(
            store.register(CognitiveDekId("same"), descriptor)
        )
        assertEquals(
            CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP,
            duplicate.category
        )

        val dependencies = CognitiveCiphertextDependencyRegistry()
        assertEquals(
            PersistentCognitiveDekMutationResult.Retired,
            first.ownership.retireIfUnused(dependencies)
        )

        val second = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            store.register(CognitiveDekId("same"), descriptor)
        )
        assertEquals(2L, second.ownership.reference.generation.value)

        val stale = assertIs<CognitiveEncryptionResult.Rejected>(
            store.resolve(first.ownership.reference)
        )
        assertEquals(CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP, stale.category)
    }

    @Test
    fun retirement_is_blocked_while_exact_ciphertext_dependency_exists() {
        val backend = InMemoryPersistentRecordBackend()
        val material = CognitiveDekMaterial(ByteArray(32) { (it + 9).toByte() })
        val protector = FakeProtector()
        val store = open(backend, protector, FixedMaterialSource(material))
        val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            store.register(CognitiveDekId("blocked"), protector.descriptor())
        )

        val dependencies = CognitiveCiphertextDependencyRegistry()
        val dependency = CognitiveCiphertextDependency(
            storeId = pro.liliya.core.persistence.PersistentStoreId("encrypted-memory"),
            entityId = pro.liliya.core.persistence.PersistentEntityId("record-1"),
            entityGeneration = pro.liliya.core.persistence.PersistentGeneration(1),
            dek = registered.ownership.reference
        )
        assertEquals(
            CognitiveDependencyUpdateResult.Updated,
            dependencies.registerCommitted(dependency)
        )

        val blocked = assertIs<PersistentCognitiveDekMutationResult.Rejected>(
            registered.ownership.retireIfUnused(dependencies)
        )
        assertEquals(CognitiveEncryptionFailureCategory.MIGRATION_INCOMPLETE, blocked.category)

        assertEquals(
            CognitiveDependencyUpdateResult.Updated,
            dependencies.releaseCommitted(dependency)
        )
        assertEquals(
            PersistentCognitiveDekMutationResult.Retired,
            registered.ownership.retireIfUnused(dependencies)
        )
    }

    @Test
    fun failed_durable_commit_does_not_publish_registered_dek() {
        val backend = InMemoryPersistentRecordBackend()
        backend.failNextCommit(IllegalStateException("private failure"))
        val material = CognitiveDekMaterial(ByteArray(32) { 3 })
        val protector = FakeProtector()
        val store = open(backend, protector, FixedMaterialSource(material))

        val failed = assertIs<PersistentCognitiveDekRegistrationResult.Failed>(
            store.register(CognitiveDekId("failed"), protector.descriptor())
        )
        assertEquals(CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED, failed.category)
        assertTrue(store.snapshotReferences().isEmpty())
        assertIs<PersistentBackendLoadResult.Missing>(
            backend.load(PersistentCognitiveDekStore.STORE_ID)
        )
    }

    @Test
    fun indexed_exact_corruption_is_not_treated_as_missing_or_available_for_registration() {
        val backend = CorruptExactIndexedBackend()
        val material = CognitiveDekMaterial(ByteArray(32) { (it + 6).toByte() })
        val protector = FakeProtector()
        val store = assertIs<PersistentCognitiveDekOpenResult.Opened>(
            PersistentCognitiveDekStore.open(
                foundation = foundation(),
                backend = backend,
                protector = protector,
                materialSource = FixedMaterialSource(material)
            )
        ).store

        val reference = CognitiveDekReference(
            CognitiveDekId("corrupt"),
            CognitiveDekGeneration(1L)
        )
        val resolved = assertIs<CognitiveEncryptionResult.Failed>(
            store.resolve(reference)
        )
        assertEquals(
            CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
            resolved.category
        )

        val registered = assertIs<PersistentCognitiveDekRegistrationResult.Failed>(
            store.register(CognitiveDekId("corrupt"), protector.descriptor())
        )
        assertEquals(
            CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
            registered.category
        )
    }

    @Test
    fun wrong_generation_cannot_resolve_after_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val material = CognitiveDekMaterial(ByteArray(32) { (it + 4).toByte() })
        val protector = FakeProtector()
        val first = open(backend, protector, FixedMaterialSource(material))
        val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            first.register(CognitiveDekId("exact"), protector.descriptor())
        )

        val reopened = open(backend, protector, FixedMaterialSource(material))
        val staleReference = CognitiveDekReference(
            registered.ownership.reference.id,
            CognitiveDekGeneration(registered.ownership.reference.generation.value + 1L)
        )
        val rejected = assertIs<CognitiveEncryptionResult.Rejected>(
            reopened.resolve(staleReference)
        )
        assertEquals(CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP, rejected.category)
    }

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        protector: CognitiveKeyProtector,
        source: CognitiveDekMaterialSource
    ): PersistentCognitiveDekStore =
        assertIs<PersistentCognitiveDekOpenResult.Opened>(
            PersistentCognitiveDekStore.open(
                foundation = foundation(),
                backend = backend,
                protector = protector,
                materialSource = source
            )
        ).store

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "persistent-dek-${sequence.incrementAndGet()}"
            }
        )
    }

    private class FixedMaterialSource(
        private val material: CognitiveDekMaterial
    ) : CognitiveDekMaterialSource {
        override fun next(): CognitiveEncryptionResult<CognitiveDekMaterial> =
            CognitiveEncryptionResult.Success(material)
    }

    /** Test-only reversible wrapping transform; never production cryptography. */
    private class FakeProtector : CognitiveKeyProtector {
        private val reference = CognitiveKeyProtectorReference(
            id = CognitiveKeyProtectorId("test-protector"),
            generation = CognitiveKeyProtectorGeneration(1),
            platformReference = CognitiveKeyProtectorPlatformReference("test-platform-ref")
        )

        fun descriptor() = CognitiveKeyProtectorDescriptor(
            reference = reference,
            securityLevel = CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT,
            purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE
        )

        override fun create(
            request: CognitiveKeyProtectorCreationRequest
        ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.INVALID_REQUEST)

        override fun inspect(
            reference: CognitiveKeyProtectorReference
        ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> =
            if (reference == this.reference) CognitiveEncryptionResult.Success(descriptor())
            else CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.PROTECTOR_MISSING
            )

        override fun wrap(
            expected: CognitiveKeyProtectorDescriptor,
            dek: CognitiveDekReference,
            material: CognitiveDekMaterial
        ): CognitiveEncryptionResult<WrappedCognitiveDekEnvelope> {
            if (expected != descriptor()) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
                )
            }
            val raw = material.copyBytes()
            val wrapped = ByteArray(raw.size) { index ->
                (raw[index].toInt() xor WRAP_MASK).toByte()
            }
            raw.fill(0)
            return CognitiveEncryptionResult.Success(
                WrappedCognitiveDekEnvelope(
                    version = CognitiveEnvelopeVersion(1),
                    dek = dek,
                    protector = reference,
                    wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
                    purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
                    wrappedDek = wrapped,
                    nonce = ByteArray(12) { (it + 1).toByte() },
                    authenticationTag = ByteArray(16) { (it + 17).toByte() }
                )
            )
        }

        override fun unwrap(
            expected: CognitiveKeyProtectorDescriptor,
            envelope: WrappedCognitiveDekEnvelope
        ): CognitiveEncryptionResult<CognitiveDekMaterial> {
            if (expected != descriptor() || envelope.protector != reference) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.UNWRAP_REJECTED
                )
            }
            val wrapped = envelope.copyWrappedDek()
            val raw = ByteArray(wrapped.size) { index ->
                (wrapped[index].toInt() xor WRAP_MASK).toByte()
            }
            wrapped.fill(0)
            return try {
                CognitiveEncryptionResult.Success(CognitiveDekMaterial(raw))
            } finally {
                raw.fill(0)
            }
        }

        override fun retire(
            expected: CognitiveKeyProtectorDescriptor
        ): CognitiveEncryptionResult<Unit> =
            CognitiveEncryptionResult.Success(Unit)

        private companion object {
            const val WRAP_MASK = 0x5A
        }
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
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
