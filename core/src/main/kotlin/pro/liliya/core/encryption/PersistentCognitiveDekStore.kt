package pro.liliya.core.encryption

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
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

interface CognitiveDekMaterialSource {
    fun next(): CognitiveEncryptionResult<CognitiveDekMaterial>
}

interface PersistentCognitiveDekOwnership {
    val reference: CognitiveDekReference
    fun retireIfUnused(
        dependencies: CognitiveCiphertextDependencyRegistry
    ): PersistentCognitiveDekMutationResult
}

sealed interface PersistentCognitiveDekRegistrationResult {
    data class Registered(
        val ownership: PersistentCognitiveDekOwnership
    ) : PersistentCognitiveDekRegistrationResult

    data class Rejected(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentCognitiveDekRegistrationResult

    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : PersistentCognitiveDekRegistrationResult
}

sealed interface PersistentCognitiveDekMutationResult {
    data object Retired : PersistentCognitiveDekMutationResult
    data class Rejected(
        val category: CognitiveEncryptionFailureCategory
    ) : PersistentCognitiveDekMutationResult
    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : PersistentCognitiveDekMutationResult
}

sealed interface PersistentCognitiveDekOpenResult {
    data class Opened(
        val store: PersistentCognitiveDekStore
    ) : PersistentCognitiveDekOpenResult

    data object Corrupt : PersistentCognitiveDekOpenResult
    data class Incompatible(val reason: String) : PersistentCognitiveDekOpenResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentCognitiveDekOpenResult
}

/**
 * Durable wrapped-DEK registry. Plaintext CognitiveDekMaterial is never persisted.
 *
 * The exact DEK generation is intentionally the underlying PersistentRecord generation. This
 * preserves one monotonic durable generation source across process reopen and prevents ABA reuse.
 */
class PersistentCognitiveDekStore private constructor(
    private val persistentStore: PersistentRecordStore,
    private val protector: CognitiveKeyProtector,
    private val materialSource: CognitiveDekMaterialSource
) : CognitiveDekMaterialResolver {

    @Synchronized
    fun register(
        id: CognitiveDekId,
        protectorDescriptor: CognitiveKeyProtectorDescriptor
    ): PersistentCognitiveDekRegistrationResult = synchronized(persistentStore) {
        if (protectorDescriptor.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE) {
            return@synchronized PersistentCognitiveDekRegistrationResult.Rejected(
                CognitiveEncryptionFailureCategory.INVALID_REQUEST
            )
        }

        val entityId = entityIdFor(id)
        if (persistentStore.contains(entityId)) {
            return@synchronized PersistentCognitiveDekRegistrationResult.Rejected(
                CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP
            )
        }

        val nextGenerationValue = persistentStore.generationHighWatermark() + 1L
        if (nextGenerationValue <= 0L) {
            return@synchronized PersistentCognitiveDekRegistrationResult.Failed(
                CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED
            )
        }
        val reference = CognitiveDekReference(
            id = id,
            generation = CognitiveDekGeneration(nextGenerationValue)
        )

        val material = when (val generated = materialSource.next()) {
            is CognitiveEncryptionResult.Success -> generated.value
            is CognitiveEncryptionResult.Rejected ->
                return@synchronized PersistentCognitiveDekRegistrationResult.Rejected(generated.category)
            is CognitiveEncryptionResult.Failed ->
                return@synchronized PersistentCognitiveDekRegistrationResult.Failed(
                    generated.category,
                    generated.throwable
                )
        }

        val envelope = when (val wrapped = protector.wrap(protectorDescriptor, reference, material)) {
            is CognitiveEncryptionResult.Success -> wrapped.value
            is CognitiveEncryptionResult.Rejected ->
                return@synchronized PersistentCognitiveDekRegistrationResult.Rejected(wrapped.category)
            is CognitiveEncryptionResult.Failed ->
                return@synchronized PersistentCognitiveDekRegistrationResult.Failed(
                    wrapped.category,
                    wrapped.throwable
                )
        }
        if (envelope.dek != reference ||
            envelope.protector != protectorDescriptor.reference ||
            envelope.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE
        ) {
            return@synchronized PersistentCognitiveDekRegistrationResult.Rejected(
                CognitiveEncryptionFailureCategory.WRAP_REJECTED
            )
        }

        val payload = try {
            PersistentPayload(WrappedCognitiveDekEnvelopeCodec.encode(envelope))
        } catch (e: IllegalArgumentException) {
            return@synchronized PersistentCognitiveDekRegistrationResult.Failed(
                CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                e
            )
        }
        val record = PersistentRecord(
            id = entityId,
            schemaId = SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = payload,
            createdAt = Instant.EPOCH
        )

        when (val installed = persistentStore.install(record)) {
            is PersistentInstallResult.Installed -> {
                if (installed.ownership.generation.value != reference.generation.value) {
                    installed.ownership.remove()
                    PersistentCognitiveDekRegistrationResult.Failed(
                        CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    )
                } else {
                    PersistentCognitiveDekRegistrationResult.Registered(
                        ownership(reference, installed.ownership)
                    )
                }
            }

            is PersistentInstallResult.Rejected ->
                PersistentCognitiveDekRegistrationResult.Rejected(
                    CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                )

            is PersistentInstallResult.Failed ->
                PersistentCognitiveDekRegistrationResult.Failed(
                    CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                    installed.throwable
                )
        }
    }

    fun inspect(reference: CognitiveDekReference): WrappedCognitiveDekEnvelope? {
        val snapshot = persistentStore.inspect(entityIdFor(reference.id)) ?: return null
        if (snapshot.generation.value != reference.generation.value) return null
        val envelope = decodeRecord(snapshot.record) ?: return null
        return envelope.takeIf { it.dek == reference }
    }

    override fun resolve(
        reference: CognitiveDekReference
    ): CognitiveEncryptionResult<CognitiveDekMaterial> {
        val snapshot = persistentStore.inspect(entityIdFor(reference.id))
            ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.DEK_MISSING)
        if (snapshot.generation.value != reference.generation.value) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP
            )
        }
        val envelope = decodeRecord(snapshot.record)
            ?: return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
            )
        if (envelope.dek != reference) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP
            )
        }

        val descriptor = when (val inspected = protector.inspect(envelope.protector)) {
            is CognitiveEncryptionResult.Success -> inspected.value
            is CognitiveEncryptionResult.Rejected -> return inspected
            is CognitiveEncryptionResult.Failed -> return inspected
        }
        if (descriptor.reference != envelope.protector ||
            descriptor.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE
        ) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
            )
        }
        return protector.unwrap(descriptor, envelope)
    }

    fun snapshotReferences(): List<CognitiveDekReference> =
        persistentStore.snapshotEntries()
            .mapNotNull { snapshot ->
                decodeRecord(snapshot.record)?.dek?.takeIf {
                    it.generation.value == snapshot.generation.value
                }
            }
            .sortedWith(
                compareBy<CognitiveDekReference> { it.generation.value }
                    .thenBy { it.id.value }
            )

    private fun ownership(
        reference: CognitiveDekReference,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentCognitiveDekOwnership = object : PersistentCognitiveDekOwnership {
        override val reference: CognitiveDekReference = reference

        override fun retireIfUnused(
            dependencies: CognitiveCiphertextDependencyRegistry
        ): PersistentCognitiveDekMutationResult = synchronized(persistentStore) {
            if (!dependencies.canRetire(reference)) {
                return@synchronized PersistentCognitiveDekMutationResult.Rejected(
                    CognitiveEncryptionFailureCategory.MIGRATION_INCOMPLETE
                )
            }
            when (val removed = persistentOwnership.remove()) {
                PersistentMutationResult.Committed -> PersistentCognitiveDekMutationResult.Retired
                is PersistentMutationResult.Rejected ->
                    PersistentCognitiveDekMutationResult.Rejected(
                        CognitiveEncryptionFailureCategory.STALE_DEK_OWNERSHIP
                    )
                is PersistentMutationResult.Failed ->
                    PersistentCognitiveDekMutationResult.Failed(
                        CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                        removed.throwable
                    )
            }
        }
    }

    private fun decodeRecord(record: PersistentRecord): WrappedCognitiveDekEnvelope? {
        if (record.schemaId != SCHEMA_ID || record.schemaVersion != SCHEMA_VERSION) return null
        val decoded = WrappedCognitiveDekEnvelopeCodec.decode(record.payload.copyBytes()) ?: return null
        if (record.id != entityIdFor(decoded.dek.id)) return null
        return decoded
    }

    companion object {
        val STORE_ID = PersistentStoreId("cognitive-wrapped-dek-v1")
        private val SCHEMA_ID = PersistentSchemaId("cognitive-wrapped-dek-envelope")
        private val SCHEMA_VERSION = PersistentSchemaVersion(1)

        fun open(
            foundation: FoundationComposition,
            backend: PersistentRecordBackend,
            protector: CognitiveKeyProtector,
            materialSource: CognitiveDekMaterialSource
        ): PersistentCognitiveDekOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, STORE_ID, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> {
                val candidate = PersistentCognitiveDekStore(
                    persistentStore = opened.store,
                    protector = protector,
                    materialSource = materialSource
                )
                when (candidate.validateRestoredState()) {
                    RestoreValidation.VALID -> PersistentCognitiveDekOpenResult.Opened(candidate)
                    RestoreValidation.CORRUPT -> PersistentCognitiveDekOpenResult.Corrupt
                    RestoreValidation.INCOMPATIBLE ->
                        PersistentCognitiveDekOpenResult.Incompatible(
                            "unsupported cognitive wrapped DEK schema"
                        )
                }
            }

            PersistentStoreOpenResult.Corrupt -> PersistentCognitiveDekOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentCognitiveDekOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentCognitiveDekOpenResult.Failed(opened.reason, opened.throwable)
        }

        private fun entityIdFor(id: CognitiveDekId): PersistentEntityId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(id.value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return PersistentEntityId("dek-$digest")
        }
    }

    private fun validateRestoredState(): RestoreValidation {
        for (snapshot in persistentStore.snapshotEntries()) {
            if (snapshot.record.schemaId != SCHEMA_ID) return RestoreValidation.INCOMPATIBLE
            if (snapshot.record.schemaVersion != SCHEMA_VERSION) return RestoreValidation.INCOMPATIBLE
            val envelope = decodeRecord(snapshot.record) ?: return RestoreValidation.CORRUPT
            if (envelope.dek.generation.value != snapshot.generation.value) {
                return RestoreValidation.CORRUPT
            }
        }
        return RestoreValidation.VALID
    }

    private enum class RestoreValidation {
        VALID,
        CORRUPT,
        INCOMPATIBLE
    }
}

internal object WrappedCognitiveDekEnvelopeCodec {
    private const val MAGIC = 0x57444531 // WDE1
    private const val FORMAT_VERSION = 1
    private const val MAX_STRING_BYTES = 1_024
    private const val MAX_WRAPPED_BYTES = 4_096

    fun encode(envelope: WrappedCognitiveDekEnvelope): ByteArray {
        require(envelope.version.value == 1) { "unsupported wrapped DEK envelope version" }
        require(envelope.wrappingAlgorithm == CognitiveDekWrappingAlgorithm.AES_256_GCM) {
            "unsupported wrapped DEK algorithm"
        }
        require(envelope.purpose == CognitiveKeyPurpose.COGNITIVE_STORAGE) {
            "unsupported wrapped DEK purpose"
        }
        val platform = envelope.protector.platformReference
            ?: throw IllegalArgumentException("wrapped DEK protector platform reference is required")
        val wrapped = envelope.copyWrappedDek()
        val nonce = envelope.copyNonce()
        val tag = envelope.copyAuthenticationTag()
        try {
            require(wrapped.isNotEmpty() && wrapped.size <= MAX_WRAPPED_BYTES) {
                "wrapped DEK bytes exceed limit"
            }
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(envelope.version.value)
                writeString(out, envelope.dek.id.value)
                out.writeLong(envelope.dek.generation.value)
                writeString(out, envelope.protector.id.value)
                out.writeLong(envelope.protector.generation.value)
                writeString(out, platform.value)
                writeString(out, envelope.wrappingAlgorithm.name)
                writeString(out, envelope.purpose.name)
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

    fun decode(bytes: ByteArray): WrappedCognitiveDekEnvelope? = try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
        val envelopeVersion = input.readInt()
        if (envelopeVersion != 1) return null
        val dekId = readString(input) ?: return null
        val dekGeneration = input.readLong()
        if (dekGeneration <= 0L) return null
        val protectorId = readString(input) ?: return null
        val protectorGeneration = input.readLong()
        if (protectorGeneration <= 0L) return null
        val platformReference = readString(input) ?: return null
        if (readString(input) != CognitiveDekWrappingAlgorithm.AES_256_GCM.name) return null
        if (readString(input) != CognitiveKeyPurpose.COGNITIVE_STORAGE.name) return null
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
            WrappedCognitiveDekEnvelope(
                version = CognitiveEnvelopeVersion(envelopeVersion),
                dek = CognitiveDekReference(
                    CognitiveDekId(dekId),
                    CognitiveDekGeneration(dekGeneration)
                ),
                protector = CognitiveKeyProtectorReference(
                    id = CognitiveKeyProtectorId(protectorId),
                    generation = CognitiveKeyProtectorGeneration(protectorGeneration),
                    platformReference = CognitiveKeyProtectorPlatformReference(platformReference)
                ),
                wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
                purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
                wrappedDek = wrapped,
                nonce = nonce,
                authenticationTag = tag
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
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES) {
            "wrapped DEK string exceeds limit"
        }
        out.writeInt(bytes.size)
        out.write(bytes)
        bytes.fill(0)
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

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(input: DataInputStream, max: Int): ByteArray? {
        val size = input.readInt()
        if (size <= 0 || size > max) return null
        return ByteArray(size).also(input::readFully)
    }
}
