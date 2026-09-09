package pro.liliya.core.protectedmodel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

data class PersistentProtectedModelDekBinding(
    val model: ProtectedModelReference,
    val dek: ModelDekReference
)

enum class PersistentProtectedModelDekFailure {
    DEK_MISSING,
    STALE_DEK_OWNERSHIP,
    STALE_PROTECTOR_OWNERSHIP,
    MODEL_MISMATCH,
    MALFORMED_ENVELOPE,
    PROTECTOR_REJECTED,
    PROTECTOR_FAILED,
    PERSISTENCE_CONFLICT,
    PERSISTENCE_FAILED
}

sealed interface PersistentProtectedModelDekRegistrationResult {
    data class Registered(
        val binding: PersistentProtectedModelDekBinding
    ) : PersistentProtectedModelDekRegistrationResult

    data class Rejected(
        val reason: PersistentProtectedModelDekFailure
    ) : PersistentProtectedModelDekRegistrationResult

    data class Failed(
        val reason: PersistentProtectedModelDekFailure,
        val throwable: Throwable? = null
    ) : PersistentProtectedModelDekRegistrationResult
}

sealed interface PersistentProtectedModelDekResolutionResult {
    data class Resolved(
        val key: SecretKey
    ) : PersistentProtectedModelDekResolutionResult

    data class Rejected(
        val reason: PersistentProtectedModelDekFailure
    ) : PersistentProtectedModelDekResolutionResult

    data class Failed(
        val reason: PersistentProtectedModelDekFailure,
        val throwable: Throwable? = null
    ) : PersistentProtectedModelDekResolutionResult
}

sealed interface PersistentProtectedModelDekOpenResult {
    data class Opened(
        val store: PersistentProtectedModelDekStore
    ) : PersistentProtectedModelDekOpenResult

    data object Corrupt : PersistentProtectedModelDekOpenResult
    data class Incompatible(val reason: String) : PersistentProtectedModelDekOpenResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentProtectedModelDekOpenResult
}

/**
 * Durable wrapped protected-model DEK registry.
 *
 * The exact [ModelDekReference] comes from trusted package/key provisioning and is never generated
 * by this store. Plaintext [ProtectedModelDekMaterial] is accepted only for one explicit registration
 * call, wrapped immediately through the dedicated [ProtectedModelKeyProtector], and never persisted.
 *
 * Records are immutable in v0.1. An exact model-DEK reference cannot be retired and re-used through
 * this store, so reopening a process cannot create an ABA alias for an older protected package.
 */
class PersistentProtectedModelDekStore private constructor(
    private val persistentStore: PersistentRecordStore,
    private val protector: ProtectedModelKeyProtector
) : ProtectedModelDekResolver {

    @Synchronized
    fun registerExact(
        model: ProtectedModelReference,
        reference: ModelDekReference,
        protectorDescriptor: ProtectedModelKeyProtectorDescriptor,
        material: ProtectedModelDekMaterial
    ): PersistentProtectedModelDekRegistrationResult = synchronized(persistentStore) {
        val entityId = entityIdFor(reference)
        if (persistentStore.contains(entityId)) {
            return@synchronized PersistentProtectedModelDekRegistrationResult.Rejected(
                PersistentProtectedModelDekFailure.STALE_DEK_OWNERSHIP
            )
        }

        val envelope = when (
            val wrapped = protector.wrap(
                expected = protectorDescriptor,
                dek = reference,
                material = material
            )
        ) {
            is ProtectedModelKeyProtectorResult.Success -> wrapped.value
            is ProtectedModelKeyProtectorResult.Rejected ->
                return@synchronized PersistentProtectedModelDekRegistrationResult.Rejected(
                    PersistentProtectedModelDekFailure.PROTECTOR_REJECTED
                )
            is ProtectedModelKeyProtectorResult.Failed ->
                return@synchronized PersistentProtectedModelDekRegistrationResult.Failed(
                    PersistentProtectedModelDekFailure.PROTECTOR_FAILED,
                    wrapped.throwable
                )
        }

        if (
            envelope.dek != reference ||
            envelope.protector != protectorDescriptor.reference
        ) {
            return@synchronized PersistentProtectedModelDekRegistrationResult.Rejected(
                PersistentProtectedModelDekFailure.STALE_PROTECTOR_OWNERSHIP
            )
        }

        val payloadBytes = try {
            WrappedProtectedModelDekBindingCodec.encode(
                PersistentProtectedModelDekRecordBinding(
                    model = model,
                    envelope = envelope
                )
            )
        } catch (throwable: Throwable) {
            return@synchronized PersistentProtectedModelDekRegistrationResult.Failed(
                PersistentProtectedModelDekFailure.PERSISTENCE_FAILED,
                throwable
            )
        }

        val record = try {
            PersistentRecord(
                id = entityId,
                schemaId = SCHEMA_ID,
                schemaVersion = SCHEMA_VERSION,
                payload = PersistentPayload(payloadBytes),
                createdAt = Instant.EPOCH
            )
        } finally {
            payloadBytes.fill(0)
        }

        return@synchronized when (val installed = persistentStore.install(record)) {
            is PersistentInstallResult.Installed ->
                PersistentProtectedModelDekRegistrationResult.Registered(
                    PersistentProtectedModelDekBinding(model, reference)
                )

            is PersistentInstallResult.Rejected ->
                PersistentProtectedModelDekRegistrationResult.Rejected(
                    PersistentProtectedModelDekFailure.PERSISTENCE_CONFLICT
                )

            is PersistentInstallResult.Failed ->
                PersistentProtectedModelDekRegistrationResult.Failed(
                    PersistentProtectedModelDekFailure.PERSISTENCE_FAILED,
                    installed.throwable
                )
        }
    }

    fun inspect(
        model: ProtectedModelReference,
        reference: ModelDekReference
    ): PersistentProtectedModelDekBinding? {
        val snapshot = persistentStore.inspect(entityIdFor(reference)) ?: return null
        val binding = decodeRecord(snapshot.record) ?: return null
        return PersistentProtectedModelDekBinding(binding.model, binding.envelope.dek).takeIf {
            it.model == model && it.dek == reference
        }
    }

    fun resolveExact(
        model: ProtectedModelReference,
        reference: ModelDekReference
    ): PersistentProtectedModelDekResolutionResult {
        val snapshot = persistentStore.inspect(entityIdFor(reference))
            ?: return PersistentProtectedModelDekResolutionResult.Rejected(
                PersistentProtectedModelDekFailure.DEK_MISSING
            )

        val binding = decodeRecord(snapshot.record)
            ?: return PersistentProtectedModelDekResolutionResult.Rejected(
                PersistentProtectedModelDekFailure.MALFORMED_ENVELOPE
            )

        if (binding.envelope.dek != reference) {
            return PersistentProtectedModelDekResolutionResult.Rejected(
                PersistentProtectedModelDekFailure.STALE_DEK_OWNERSHIP
            )
        }
        if (binding.model != model) {
            return PersistentProtectedModelDekResolutionResult.Rejected(
                PersistentProtectedModelDekFailure.MODEL_MISMATCH
            )
        }

        val descriptor = when (val inspected = protector.inspect(binding.envelope.protector)) {
            is ProtectedModelKeyProtectorResult.Success -> inspected.value
            is ProtectedModelKeyProtectorResult.Rejected ->
                return PersistentProtectedModelDekResolutionResult.Rejected(
                    PersistentProtectedModelDekFailure.STALE_PROTECTOR_OWNERSHIP
                )
            is ProtectedModelKeyProtectorResult.Failed ->
                return PersistentProtectedModelDekResolutionResult.Failed(
                    PersistentProtectedModelDekFailure.PROTECTOR_FAILED,
                    inspected.throwable
                )
        }

        if (descriptor.reference != binding.envelope.protector) {
            return PersistentProtectedModelDekResolutionResult.Rejected(
                PersistentProtectedModelDekFailure.STALE_PROTECTOR_OWNERSHIP
            )
        }

        val material = when (val unwrapped = protector.unwrap(descriptor, binding.envelope)) {
            is ProtectedModelKeyProtectorResult.Success -> unwrapped.value
            is ProtectedModelKeyProtectorResult.Rejected ->
                return PersistentProtectedModelDekResolutionResult.Rejected(
                    PersistentProtectedModelDekFailure.PROTECTOR_REJECTED
                )
            is ProtectedModelKeyProtectorResult.Failed ->
                return PersistentProtectedModelDekResolutionResult.Failed(
                    PersistentProtectedModelDekFailure.PROTECTOR_FAILED,
                    unwrapped.throwable
                )
        }

        val raw = material.copyBytes()
        return try {
            PersistentProtectedModelDekResolutionResult.Resolved(
                SecretKeySpec(raw, AES_ALGORITHM)
            )
        } finally {
            raw.fill(0)
        }
    }

    override fun resolveForProtectedModelOpen(
        model: ProtectedModelReference,
        dek: ModelDekReference
    ): SecretKey? =
        when (val result = resolveExact(model, dek)) {
            is PersistentProtectedModelDekResolutionResult.Resolved -> result.key
            is PersistentProtectedModelDekResolutionResult.Rejected -> null
            is PersistentProtectedModelDekResolutionResult.Failed -> null
        }

    fun snapshotBindings(): List<PersistentProtectedModelDekBinding> =
        persistentStore.snapshotEntries()
            .mapNotNull { snapshot ->
                decodeRecord(snapshot.record)?.let { binding ->
                    PersistentProtectedModelDekBinding(
                        model = binding.model,
                        dek = binding.envelope.dek
                    )
                }
            }
            .sortedWith(
                compareBy<PersistentProtectedModelDekBinding> { it.dek.id.value }
                    .thenBy { it.dek.generation.value }
                    .thenBy { it.model.packageId.value }
                    .thenBy { it.model.generation.value }
            )

    private fun decodeRecord(record: PersistentRecord): PersistentProtectedModelDekRecordBinding? {
        if (record.schemaId != SCHEMA_ID || record.schemaVersion != SCHEMA_VERSION) return null
        val bytes = record.payload.copyBytes()
        val binding = try {
            WrappedProtectedModelDekBindingCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        } ?: return null
        if (record.id != entityIdFor(binding.envelope.dek)) return null
        return binding
    }

    private fun validateRestoredState(): RestoreValidation {
        for (snapshot in persistentStore.snapshotEntries()) {
            if (
                snapshot.record.schemaId != SCHEMA_ID ||
                snapshot.record.schemaVersion != SCHEMA_VERSION
            ) {
                return RestoreValidation.INCOMPATIBLE
            }
            decodeRecord(snapshot.record) ?: return RestoreValidation.CORRUPT
        }
        return RestoreValidation.VALID
    }

    companion object {
        val STORE_ID = PersistentStoreId("protected-model-wrapped-dek-v1")
        private val SCHEMA_ID = PersistentSchemaId("protected-model-wrapped-dek-binding")
        private val SCHEMA_VERSION = PersistentSchemaVersion(1)
        private const val AES_ALGORITHM = "AES"

        fun open(
            foundation: FoundationComposition,
            backend: PersistentRecordBackend,
            protector: ProtectedModelKeyProtector
        ): PersistentProtectedModelDekOpenResult =
            when (val opened = PersistentRecordStore.open(foundation, STORE_ID, backend)) {
                is PersistentStoreOpenResult.Opened -> {
                    val candidate = PersistentProtectedModelDekStore(
                        persistentStore = opened.store,
                        protector = protector
                    )
                    when (candidate.validateRestoredState()) {
                        RestoreValidation.VALID ->
                            PersistentProtectedModelDekOpenResult.Opened(candidate)
                        RestoreValidation.CORRUPT ->
                            PersistentProtectedModelDekOpenResult.Corrupt
                        RestoreValidation.INCOMPATIBLE ->
                            PersistentProtectedModelDekOpenResult.Incompatible(
                                "unsupported protected-model wrapped DEK schema"
                            )
                    }
                }

                PersistentStoreOpenResult.Corrupt ->
                    PersistentProtectedModelDekOpenResult.Corrupt
                is PersistentStoreOpenResult.Incompatible ->
                    PersistentProtectedModelDekOpenResult.Incompatible(opened.reason)
                is PersistentStoreOpenResult.Failed ->
                    PersistentProtectedModelDekOpenResult.Failed(
                        opened.reason,
                        opened.throwable
                    )
            }

        private fun entityIdFor(reference: ModelDekReference): PersistentEntityId {
            val source = (reference.id.value + ":" + reference.generation.value)
                .toByteArray(StandardCharsets.UTF_8)
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(source)
            } finally {
                source.fill(0)
            }
            return try {
                PersistentEntityId(
                    "model-dek-" + digest.joinToString(separator = "") { "%02x".format(it) }
                )
            } finally {
                digest.fill(0)
            }
        }
    }

    private enum class RestoreValidation {
        VALID,
        CORRUPT,
        INCOMPATIBLE
    }
}

internal data class PersistentProtectedModelDekRecordBinding(
    val model: ProtectedModelReference,
    val envelope: WrappedProtectedModelDek
)

internal object WrappedProtectedModelDekBindingCodec {
    private const val MAGIC = 0x504D4431 // PMD1
    private const val FORMAT_VERSION = 1
    private const val MAX_STRING_BYTES = 1_024
    private const val MAX_WRAPPED_BYTES = 4_096

    fun encode(binding: PersistentProtectedModelDekRecordBinding): ByteArray {
        val wrapped = binding.envelope.copyWrapped()
        val nonce = binding.envelope.copyNonce()
        val tag = binding.envelope.copyAuthenticationTag()
        try {
            require(wrapped.isNotEmpty() && wrapped.size <= MAX_WRAPPED_BYTES)
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                writeString(out, binding.model.packageId.value)
                out.writeLong(binding.model.generation.value)
                writeString(out, binding.envelope.dek.id.value)
                out.writeLong(binding.envelope.dek.generation.value)
                writeString(out, binding.envelope.protector.id.value)
                out.writeLong(binding.envelope.protector.generation.value)
                writeString(out, binding.envelope.protector.platformReference.value)
                writeBytes(out, wrapped)
                writeBytes(out, nonce)
                writeBytes(out, tag)
            }
            return buffer.toByteArray()
        } finally {
            wrapped.fill(0)
            nonce.fill(0)
            tag.fill(0)
        }
    }

    fun decode(bytes: ByteArray): PersistentProtectedModelDekRecordBinding? = try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null

        val modelId = readString(input) ?: return null
        val modelGeneration = input.readLong()
        if (modelGeneration <= 0L) return null

        val dekId = readString(input) ?: return null
        val dekGeneration = input.readLong()
        if (dekGeneration <= 0L) return null

        val protectorId = readString(input) ?: return null
        val protectorGeneration = input.readLong()
        if (protectorGeneration <= 0L) return null

        val platformReference = readString(input) ?: return null
        val wrapped = readBytes(input, MAX_WRAPPED_BYTES) ?: return null
        val nonce = readBytes(input, 12) ?: return null
        val tag = readBytes(input, 16) ?: return null

        if (nonce.size != 12 || tag.size != 16 || input.read() != -1) {
            wrapped.fill(0)
            nonce.fill(0)
            tag.fill(0)
            return null
        }

        try {
            val dek = ModelDekReference(
                id = ModelDekId(dekId),
                generation = ModelDekGeneration(dekGeneration)
            )
            PersistentProtectedModelDekRecordBinding(
                model = ProtectedModelReference(
                    packageId = ProtectedModelPackageId(modelId),
                    generation = ProtectedModelGeneration(modelGeneration)
                ),
                envelope = WrappedProtectedModelDek(
                    dek = dek,
                    protector = ProtectedModelKeyProtectorReference(
                        id = ProtectedModelKeyProtectorId(protectorId),
                        generation = ProtectedModelKeyProtectorGeneration(protectorGeneration),
                        platformReference =
                            ProtectedModelKeyProtectorPlatformReference(platformReference)
                    ),
                    wrapped = wrapped,
                    nonce = nonce,
                    authenticationTag = tag
                )
            )
        } finally {
            wrapped.fill(0)
            nonce.fill(0)
            tag.fill(0)
        }
    } catch (_: EOFException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES)
        try {
            out.writeInt(bytes.size)
            out.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readString(input: DataInputStream): String? {
        val size = input.readInt()
        if (size <= 0 || size > MAX_STRING_BYTES) return null
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val value = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            bytes.fill(0)
            return null
        }
        bytes.fill(0)
        return value.takeIf { it.isNotBlank() }
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray? {
        val size = input.readInt()
        if (size <= 0 || size > maxBytes) return null
        return ByteArray(size).also(input::readFully)
    }
}
