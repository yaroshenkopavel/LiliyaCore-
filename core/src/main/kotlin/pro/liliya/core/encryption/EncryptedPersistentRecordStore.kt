package pro.liliya.core.encryption

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordSnapshot
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

/** Exact DEK material resolution seam. Key protection/unwrap is supplied by a later reviewed layer. */
interface CognitiveDekMaterialResolver {
    fun resolve(reference: CognitiveDekReference): CognitiveEncryptionResult<CognitiveDekMaterial>
}

data class CognitivePersistentRecordDraft(
    val id: PersistentEntityId,
    val schemaId: PersistentSchemaId,
    val schemaVersion: PersistentSchemaVersion,
    val plaintext: CognitivePlaintext,
    val createdAt: Instant,
    val dek: CognitiveDekReference
)

/**
 * Record-level encryption adapter around the frozen PersistentRecordStore boundary.
 * Generation prediction and install execute under the exact PersistentRecordStore monitor, so
 * authenticated generation binding cannot race another install in the same store instance.
 */
class EncryptedPersistentRecordStore(
    private val store: PersistentRecordStore,
    private val profile: CognitiveEncryptionProfile,
    private val envelopeVersion: CognitiveEnvelopeVersion,
    private val nonceSource: CognitiveNonceSource,
    private val aead: CognitiveAeadProvider,
    private val dekResolver: CognitiveDekMaterialResolver
) {
    init {
        require(envelopeVersion.value == 1) { "unsupported cognitive persistent envelope version" }
    }

    fun install(draft: CognitivePersistentRecordDraft): CognitiveEncryptionResult<PersistentRecordOwnership> =
        synchronized(store) {
            val nextGenerationValue = store.generationHighWatermark() + 1L
            if (nextGenerationValue <= 0L) {
                return@synchronized CognitiveEncryptionResult.Failed(
                    CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED
                )
            }
            val generation = PersistentGeneration(nextGenerationValue)
            val binding = CognitivePayloadBinding(
                storeId = store.storeId,
                entityId = draft.id,
                entityGeneration = generation,
                schemaId = draft.schemaId,
                schemaVersion = draft.schemaVersion,
                dek = draft.dek
            )

            val dek = when (val resolved = dekResolver.resolve(draft.dek)) {
                is CognitiveEncryptionResult.Success -> resolved.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized resolved
                is CognitiveEncryptionResult.Failed -> return@synchronized resolved
            }
            val nonce = when (val generated = nonceSource.next(profile)) {
                is CognitiveEncryptionResult.Success -> generated.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized generated
                is CognitiveEncryptionResult.Failed -> return@synchronized generated
            }
            val aad = CognitiveAssociatedDataEncoder.encode(envelopeVersion, profile, binding)
            val sealed = when (
                val result = aead.seal(profile, dek, nonce, aad, draft.plaintext)
            ) {
                is CognitiveEncryptionResult.Success -> result.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized result
                is CognitiveEncryptionResult.Failed -> return@synchronized result
            }

            val envelope = EncryptedCognitivePayloadEnvelope(
                version = envelopeVersion,
                profile = profile,
                binding = binding,
                nonce = nonce.copyBytes(),
                ciphertext = sealed.copyCiphertext(),
                authenticationTag = sealed.copyAuthenticationTag()
            )
            val record = PersistentRecord(
                id = draft.id,
                schemaId = draft.schemaId,
                schemaVersion = draft.schemaVersion,
                payload = PersistentPayload(CognitivePersistentEnvelopeCodec.encode(envelope)),
                createdAt = draft.createdAt
            )

            when (val installed = store.install(record)) {
                is PersistentInstallResult.Installed -> {
                    if (installed.ownership.generation != generation) {
                        CognitiveEncryptionResult.Failed(
                            CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                        )
                    } else {
                        CognitiveEncryptionResult.Success(installed.ownership)
                    }
                }
                is PersistentInstallResult.Rejected -> CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                )
                is PersistentInstallResult.Failed -> CognitiveEncryptionResult.Failed(
                    CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                    installed.throwable
                )
            }
        }

    internal fun transitionExact(
        sourceId: PersistentEntityId,
        sourceGeneration: PersistentGeneration,
        replacement: CognitivePersistentRecordDraft
    ): CognitiveEncryptionResult<PersistentRecordOwnership> =
        synchronized(store) {
            val binding = CognitivePayloadBinding(
                storeId = store.storeId,
                entityId = replacement.id,
                entityGeneration = sourceGeneration,
                schemaId = replacement.schemaId,
                schemaVersion = replacement.schemaVersion,
                dek = replacement.dek
            )

            val dek = when (val resolved = dekResolver.resolve(replacement.dek)) {
                is CognitiveEncryptionResult.Success -> resolved.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized resolved
                is CognitiveEncryptionResult.Failed -> return@synchronized resolved
            }
            val nonce = when (val generated = nonceSource.next(profile)) {
                is CognitiveEncryptionResult.Success -> generated.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized generated
                is CognitiveEncryptionResult.Failed -> return@synchronized generated
            }
            val aad = CognitiveAssociatedDataEncoder.encode(envelopeVersion, profile, binding)
            val sealed = when (
                val result = aead.seal(profile, dek, nonce, aad, replacement.plaintext)
            ) {
                is CognitiveEncryptionResult.Success -> result.value
                is CognitiveEncryptionResult.Rejected -> return@synchronized result
                is CognitiveEncryptionResult.Failed -> return@synchronized result
            }

            val envelope = EncryptedCognitivePayloadEnvelope(
                version = envelopeVersion,
                profile = profile,
                binding = binding,
                nonce = nonce.copyBytes(),
                ciphertext = sealed.copyCiphertext(),
                authenticationTag = sealed.copyAuthenticationTag()
            )
            val record = PersistentRecord(
                id = replacement.id,
                schemaId = replacement.schemaId,
                schemaVersion = replacement.schemaVersion,
                payload = PersistentPayload(CognitivePersistentEnvelopeCodec.encode(envelope)),
                createdAt = replacement.createdAt
            )

            when (
                val transitioned = store.transitionExact(
                    sourceId = sourceId,
                    sourceGeneration = sourceGeneration,
                    replacement = record
                )
            ) {
                is PersistentRecordTransitionResult.Committed ->
                    CognitiveEncryptionResult.Success(transitioned.ownership)
                is PersistentRecordTransitionResult.Rejected ->
                    CognitiveEncryptionResult.Rejected(
                        CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                    )
                is PersistentRecordTransitionResult.Failed ->
                    CognitiveEncryptionResult.Failed(
                        CognitiveEncryptionFailureCategory.PERSISTENCE_FAILED,
                        transitioned.throwable
                    )
            }
        }

    internal fun decryptedSnapshotEntries():
        CognitiveEncryptionResult<List<PersistentRecordSnapshot>> {
        val decrypted = ArrayList<PersistentRecordSnapshot>()
        for (snapshot in store.snapshotEntries()) {
            val plaintext = when (val opened = open(snapshot.record.id)) {
                is CognitiveEncryptionResult.Success -> opened.value
                is CognitiveEncryptionResult.Rejected -> return opened
                is CognitiveEncryptionResult.Failed -> return opened
            }
            decrypted += PersistentRecordSnapshot(
                record = PersistentRecord(
                    id = snapshot.record.id,
                    schemaId = snapshot.record.schemaId,
                    schemaVersion = snapshot.record.schemaVersion,
                    payload = PersistentPayload(plaintext.copyBytes()),
                    createdAt = snapshot.record.createdAt
                ),
                generation = snapshot.generation
            )
        }
        return CognitiveEncryptionResult.Success(decrypted)
    }

    internal fun snapshotEntries(): List<pro.liliya.core.persistence.PersistentRecordSnapshot> =
        store.snapshotEntries()

    internal fun generationHighWatermark(): Long = store.generationHighWatermark()

    fun open(id: PersistentEntityId): CognitiveEncryptionResult<CognitivePlaintext> {
        val snapshot = store.inspect(id)
            ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.INVALID_REQUEST)
        val envelope = when (
            val decoded = CognitivePersistentEnvelopeCodec.decode(snapshot.record.payload.copyBytes())
        ) {
            is CognitiveEncryptionResult.Success -> decoded.value
            is CognitiveEncryptionResult.Rejected -> return decoded
            is CognitiveEncryptionResult.Failed -> return decoded
        }

        val expectedBinding = CognitivePayloadBinding(
            storeId = store.storeId,
            entityId = snapshot.record.id,
            entityGeneration = snapshot.generation,
            schemaId = snapshot.record.schemaId,
            schemaVersion = snapshot.record.schemaVersion,
            dek = envelope.binding.dek
        )
        if (envelope.version != envelopeVersion ||
            envelope.profile != profile ||
            envelope.binding != expectedBinding
        ) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
            )
        }

        val dek = when (val resolved = dekResolver.resolve(envelope.binding.dek)) {
            is CognitiveEncryptionResult.Success -> resolved.value
            is CognitiveEncryptionResult.Rejected -> return resolved
            is CognitiveEncryptionResult.Failed -> return resolved
        }
        val nonce = try {
            CognitiveNonce(profile, envelope.copyNonce())
        } catch (_: IllegalArgumentException) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.NONCE_VALIDATION_FAILED
            )
        }
        val sealed = try {
            CognitiveAeadSealedData(
                envelope.copyCiphertext(),
                envelope.copyAuthenticationTag()
            )
        } catch (_: IllegalArgumentException) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
            )
        }
        val aad = CognitiveAssociatedDataEncoder.encode(envelope.version, envelope.profile, envelope.binding)
        return aead.open(envelope.profile, dek, nonce, aad, sealed)
    }
}

/** Canonical v1 persistence encoding for authenticated cognitive payload envelopes. */
internal object CognitivePersistentEnvelopeCodec {
    private const val MAGIC = 0x4C434531 // LCE1
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 65_536

    fun encode(envelope: EncryptedCognitivePayloadEnvelope): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(envelope.version.value)
            writeString(out, envelope.profile.algorithm.name)
            out.writeInt(envelope.profile.keySizeBits)
            out.writeInt(envelope.profile.nonceSizeBytes)
            out.writeInt(envelope.profile.authenticationTagSizeBits)
            writeString(out, envelope.binding.storeId.value)
            writeString(out, envelope.binding.entityId.value)
            out.writeLong(envelope.binding.entityGeneration.value)
            writeString(out, envelope.binding.schemaId.value)
            out.writeInt(envelope.binding.schemaVersion.value)
            writeString(out, envelope.binding.dek.id.value)
            out.writeLong(envelope.binding.dek.generation.value)
            writeBytes(out, envelope.copyNonce())
            writeBytes(out, envelope.copyCiphertext())
            writeBytes(out, envelope.copyAuthenticationTag())
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): CognitiveEncryptionResult<EncryptedCognitivePayloadEnvelope> = try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
            )
        }
        val envelopeVersion = CognitiveEnvelopeVersion(input.readInt())
        val algorithmName = readString(input)
        if (algorithmName != CognitiveEncryptionAlgorithm.AES_256_GCM.name) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.UNSUPPORTED_PROFILE
            )
        }
        val profile = CognitiveEncryptionProfile(
            algorithm = CognitiveEncryptionAlgorithm.AES_256_GCM,
            keySizeBits = input.readInt(),
            nonceSizeBytes = input.readInt(),
            authenticationTagSizeBits = input.readInt()
        )
        val binding = CognitivePayloadBinding(
            storeId = pro.liliya.core.persistence.PersistentStoreId(readString(input)),
            entityId = PersistentEntityId(readString(input)),
            entityGeneration = PersistentGeneration(input.readLong()),
            schemaId = PersistentSchemaId(readString(input)),
            schemaVersion = PersistentSchemaVersion(input.readInt()),
            dek = CognitiveDekReference(
                CognitiveDekId(readString(input)),
                CognitiveDekGeneration(input.readLong())
            )
        )
        val nonce = readBytes(input)
        val ciphertext = readBytes(input)
        val tag = readBytes(input)
        if (input.available() != 0) {
            return CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE
            )
        }
        CognitiveEncryptionResult.Success(
            EncryptedCognitivePayloadEnvelope(
                version = envelopeVersion,
                profile = profile,
                binding = binding,
                nonce = nonce,
                ciphertext = ciphertext,
                authenticationTag = tag
            )
        )
    } catch (_: IllegalArgumentException) {
        CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE)
    } catch (_: Throwable) {
        CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES) { "invalid cognitive envelope string size" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_STRING_BYTES && length <= input.available()) {
            "invalid cognitive envelope string length"
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val length = input.readInt()
        require(length >= 0 && length <= input.available()) { "invalid cognitive envelope byte length" }
        return ByteArray(length).also(input::readFully)
    }
}
