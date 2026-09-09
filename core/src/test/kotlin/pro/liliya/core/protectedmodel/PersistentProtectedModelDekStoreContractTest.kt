package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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

class PersistentProtectedModelDekStoreContractTest {

    @Test
    fun register_exact_persists_wrapped_only_and_reopen_resolves_provisioned_reference() {
        val backend = InMemoryPersistentRecordBackend()
        val raw = ByteArray(32) { (it * 13 + 5).toByte() }
        val material = ProtectedModelDekMaterial(raw)
        val protector = FakeProtector()
        val model = model("model-main", 4)
        val dek = ModelDekReference(
            ModelDekId("model-dek-main"),
            ModelDekGeneration(7)
        )

        val first = open(backend, protector)
        val registered = assertIs<PersistentProtectedModelDekRegistrationResult.Registered>(
            first.registerExact(
                model = model,
                reference = dek,
                protectorDescriptor = protector.descriptor(),
                material = material
            )
        )
        assertEquals(PersistentProtectedModelDekBinding(model, dek), registered.binding)

        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(
            backend.load(PersistentProtectedModelDekStore.STORE_ID)
        )
        val durable = loaded.state.entries.values.single().record.payload.copyBytes()
        assertFalse(containsSubsequence(durable, raw))

        val reopened = open(backend, protector)
        val resolved = assertIs<PersistentProtectedModelDekResolutionResult.Resolved>(
            reopened.resolveExact(model, dek)
        )
        assertContentEquals(raw, resolved.key.encoded)

        val adapterKey = reopened.resolveForProtectedModelOpen(model, dek)
        assertContentEquals(raw, requireNotNull(adapterKey).encoded)
    }

    @Test
    fun exact_dek_cannot_be_reused_for_another_model_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val raw = ByteArray(32) { (it + 9).toByte() }
        val protector = FakeProtector()
        val firstModel = model("same-package", 1)
        val secondModel = model("same-package", 2)
        val dek = ModelDekReference(ModelDekId("bound-dek"), ModelDekGeneration(3))
        val store = open(backend, protector)

        assertIs<PersistentProtectedModelDekRegistrationResult.Registered>(
            store.registerExact(
                firstModel,
                dek,
                protector.descriptor(),
                ProtectedModelDekMaterial(raw)
            )
        )

        val mismatch = assertIs<PersistentProtectedModelDekResolutionResult.Rejected>(
            store.resolveExact(secondModel, dek)
        )
        assertEquals(PersistentProtectedModelDekFailure.MODEL_MISMATCH, mismatch.reason)
        assertNull(store.resolveForProtectedModelOpen(secondModel, dek))
    }

    @Test
    fun duplicate_exact_reference_is_rejected_but_new_generation_of_same_id_can_coexist() {
        val backend = InMemoryPersistentRecordBackend()
        val protector = FakeProtector()
        val model = model("model", 1)
        val first = ModelDekReference(ModelDekId("same"), ModelDekGeneration(5))
        val second = ModelDekReference(ModelDekId("same"), ModelDekGeneration(6))
        val store = open(backend, protector)

        assertIs<PersistentProtectedModelDekRegistrationResult.Registered>(
            store.registerExact(
                model,
                first,
                protector.descriptor(),
                ProtectedModelDekMaterial(ByteArray(32) { 1 })
            )
        )
        val duplicate = assertIs<PersistentProtectedModelDekRegistrationResult.Rejected>(
            store.registerExact(
                model,
                first,
                protector.descriptor(),
                ProtectedModelDekMaterial(ByteArray(32) { 2 })
            )
        )
        assertEquals(
            PersistentProtectedModelDekFailure.STALE_DEK_OWNERSHIP,
            duplicate.reason
        )

        assertIs<PersistentProtectedModelDekRegistrationResult.Registered>(
            store.registerExact(
                model.copy(generation = ProtectedModelGeneration(2)),
                second,
                protector.descriptor(),
                ProtectedModelDekMaterial(ByteArray(32) { 3 })
            )
        )

        assertEquals(
            listOf(
                PersistentProtectedModelDekBinding(model, first),
                PersistentProtectedModelDekBinding(
                    model.copy(generation = ProtectedModelGeneration(2)),
                    second
                )
            ),
            store.snapshotBindings()
        )
    }

    @Test
    fun failed_durable_commit_does_not_publish_exact_model_dek() {
        val backend = InMemoryPersistentRecordBackend()
        backend.failNextCommit(IllegalStateException("private failure"))
        val protector = FakeProtector()
        val store = open(backend, protector)
        val model = model("failed-model", 1)
        val dek = ModelDekReference(ModelDekId("failed"), ModelDekGeneration(11))

        val failed = assertIs<PersistentProtectedModelDekRegistrationResult.Failed>(
            store.registerExact(
                model,
                dek,
                protector.descriptor(),
                ProtectedModelDekMaterial(ByteArray(32) { 7 })
            )
        )
        assertEquals(PersistentProtectedModelDekFailure.PERSISTENCE_FAILED, failed.reason)
        assertTrue(store.snapshotBindings().isEmpty())
        assertIs<PersistentBackendLoadResult.Missing>(
            backend.load(PersistentProtectedModelDekStore.STORE_ID)
        )
    }

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        protector: ProtectedModelKeyProtector
    ): PersistentProtectedModelDekStore =
        assertIs<PersistentProtectedModelDekOpenResult.Opened>(
            PersistentProtectedModelDekStore.open(
                foundation = foundation(),
                backend = backend,
                protector = protector
            )
        ).store

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "persistent-model-dek-" + sequence.incrementAndGet()
            }
        )
    }

    private fun model(id: String, generation: Long): ProtectedModelReference =
        ProtectedModelReference(
            ProtectedModelPackageId(id),
            ProtectedModelGeneration(generation)
        )

    private class FakeProtector : ProtectedModelKeyProtector {
        private val reference = ProtectedModelKeyProtectorReference(
            id = ProtectedModelKeyProtectorId("test-model-protector"),
            generation = ProtectedModelKeyProtectorGeneration(1),
            platformReference = ProtectedModelKeyProtectorPlatformReference(
                "test-model-platform-reference"
            )
        )

        fun descriptor() = ProtectedModelKeyProtectorDescriptor(
            reference = reference,
            securityLevel = ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
        )

        override fun create(
            request: ProtectedModelKeyProtectorCreationRequest
        ): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> =
            ProtectedModelKeyProtectorResult.Rejected(
                ProtectedModelKeyProtectorFailure.INVALID_REQUEST
            )

        override fun inspect(
            reference: ProtectedModelKeyProtectorReference
        ): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> =
            if (reference == this.reference) {
                ProtectedModelKeyProtectorResult.Success(descriptor())
            } else {
                ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING
                )
            }

        override fun wrap(
            expected: ProtectedModelKeyProtectorDescriptor,
            dek: ModelDekReference,
            material: ProtectedModelDekMaterial
        ): ProtectedModelKeyProtectorResult<WrappedProtectedModelDek> {
            if (expected != descriptor()) {
                return ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.STALE_PROTECTOR_OWNERSHIP
                )
            }
            val raw = material.copyBytes()
            val wrapped = ByteArray(raw.size) { index ->
                (raw[index].toInt() xor WRAP_MASK).toByte()
            }
            raw.fill(0)
            return ProtectedModelKeyProtectorResult.Success(
                WrappedProtectedModelDek(
                    dek = dek,
                    protector = reference,
                    wrapped = wrapped,
                    nonce = ByteArray(12) { (it + 1).toByte() },
                    authenticationTag = ByteArray(16) { (it + 17).toByte() }
                )
            )
        }

        override fun unwrap(
            expected: ProtectedModelKeyProtectorDescriptor,
            envelope: WrappedProtectedModelDek
        ): ProtectedModelKeyProtectorResult<ProtectedModelDekMaterial> {
            if (expected != descriptor() || envelope.protector != reference) {
                return ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.UNWRAP_FAILED
                )
            }
            val wrapped = envelope.copyWrapped()
            val raw = ByteArray(wrapped.size) { index ->
                (wrapped[index].toInt() xor WRAP_MASK).toByte()
            }
            wrapped.fill(0)
            return try {
                ProtectedModelKeyProtectorResult.Success(
                    ProtectedModelDekMaterial(raw)
                )
            } finally {
                raw.fill(0)
            }
        }

        override fun retire(
            expected: ProtectedModelKeyProtectorDescriptor
        ): ProtectedModelKeyProtectorResult<Unit> =
            ProtectedModelKeyProtectorResult.Success(Unit)

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
