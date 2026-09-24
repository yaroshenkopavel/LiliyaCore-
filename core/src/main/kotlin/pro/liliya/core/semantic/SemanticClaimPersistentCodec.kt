package pro.liliya.core.semantic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface SemanticClaimPersistentDecodeResult {
    data class Decoded(val record: SemanticClaimRecord) : SemanticClaimPersistentDecodeResult
    data object Corrupt : SemanticClaimPersistentDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimPersistentDecodeResult
}

internal object SemanticClaimPersistentCodec {
    val schemaId = PersistentSchemaId("semantic-claim-record")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x53434C31
    private const val MAX_EPISODE_PROVENANCE = 256
    private const val MAX_RAW_PROVENANCE = 256

    fun encode(record: SemanticClaimRecord): PersistentRecord {
        require(record.id == SemanticClaimIds.forIdentity(record.identity)) {
            "semantic claim id must match deterministic identity"
        }
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(record.id.value)
                data.writeLong(record.version.value)
                data.writeString(record.identity.subject.namespace)
                data.writeString(record.identity.subject.id)
                data.writeString(record.identity.predicate)
                data.writeClaimObject(record.objectValue)
                data.writeInstant(record.temporal.observedAt)
                data.writeOptionalInstant(record.temporal.validFrom)
                data.writeOptionalInstant(record.temporal.validUntil)
                data.writeOptionalInstant(record.temporal.supersededAt)

                data.writeInt(record.provenance.episodes.size)
                record.provenance.episodes.forEach { data.writeString(it.value) }

                data.writeInt(record.provenance.rawEvidence.size)
                record.provenance.rawEvidence.forEach {
                    data.writeString(it.namespace.value)
                    data.writeString(it.id.value)
                }

                data.writeString(record.provenance.extraction.extractorId)
                data.writeString(record.provenance.extraction.extractorVersion)
                data.writeInstant(record.provenance.extraction.extractedAt)

                data.writeBoolean(record.provenance.evidenceStrength != null)
                record.provenance.evidenceStrength?.let { data.writeString(it.name) }
            }
            output.toByteArray()
        }

        return PersistentRecord(
            id = PersistentEntityId(recordKey(record)),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.provenance.extraction.extractedAt
        )
    }

    fun decode(record: PersistentRecord): SemanticClaimPersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return SemanticClaimPersistentDecodeResult.Incompatible("semantic claim schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimPersistentDecodeResult.Incompatible("semantic claim schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return SemanticClaimPersistentDecodeResult.Corrupt

            val id = SemanticClaimId(data.readString(input))
            val version = SemanticClaimVersion(data.readLong())
            val identity = SemanticClaimIdentity(
                subject = SemanticEntityReference(
                    namespace = data.readString(input),
                    id = data.readString(input)
                ),
                predicate = data.readString(input)
            )
            if (id != SemanticClaimIds.forIdentity(identity)) {
                return SemanticClaimPersistentDecodeResult.Corrupt
            }

            val objectValue = data.readClaimObject(input)
            val temporal = SemanticClaimTemporalState(
                observedAt = data.readInstant(),
                validFrom = data.readOptionalInstant(),
                validUntil = data.readOptionalInstant(),
                supersededAt = data.readOptionalInstant()
            )

            val episodeCount = data.readInt()
            if (episodeCount !in 0..MAX_EPISODE_PROVENANCE) {
                return SemanticClaimPersistentDecodeResult.Corrupt
            }
            val episodes = List(episodeCount) { EpisodeId(data.readString(input)) }

            val rawCount = data.readInt()
            if (rawCount !in 0..MAX_RAW_PROVENANCE) {
                return SemanticClaimPersistentDecodeResult.Corrupt
            }
            val raw = List(rawCount) {
                RawEvidenceReference(
                    namespace = RawEvidenceNamespace(data.readString(input)),
                    id = RawEvidenceId(data.readString(input))
                )
            }

            val extraction = SemanticClaimExtractionProvenance(
                extractorId = data.readString(input),
                extractorVersion = data.readString(input),
                extractedAt = data.readInstant()
            )
            val strength = if (data.readBoolean()) {
                SemanticEvidenceStrength.valueOf(data.readString(input))
            } else {
                null
            }

            if (input.available() != 0) return SemanticClaimPersistentDecodeResult.Corrupt

            val decoded = SemanticClaimRecord(
                id = id,
                version = version,
                identity = identity,
                objectValue = objectValue,
                temporal = temporal,
                provenance = SemanticClaimProvenance(
                    episodes = episodes,
                    rawEvidence = raw,
                    extraction = extraction,
                    evidenceStrength = strength
                )
            )

            if (record.id.value != recordKey(decoded) ||
                record.createdAt != extraction.extractedAt
            ) {
                return SemanticClaimPersistentDecodeResult.Corrupt
            }

            SemanticClaimPersistentDecodeResult.Decoded(decoded)
        } catch (_: EOFException) {
            SemanticClaimPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            SemanticClaimPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            SemanticClaimPersistentDecodeResult.Corrupt
        }
    }

    private fun recordKey(record: SemanticClaimRecord): String =
        "${record.id.value}:v${record.version.value}"

    private fun DataOutputStream.writeClaimObject(value: SemanticClaimObject) {
        when (value) {
            is SemanticClaimObject.Entity -> {
                writeByte(1)
                writeString(value.reference.namespace)
                writeString(value.reference.id)
            }
            is SemanticClaimObject.Text -> {
                writeByte(2)
                writeString(value.value)
            }
            is SemanticClaimObject.Number -> {
                writeByte(3)
                writeString(value.canonical)
            }
            is SemanticClaimObject.BooleanValue -> {
                writeByte(4)
                writeBoolean(value.value)
            }
        }
    }

    private fun DataInputStream.readClaimObject(input: ByteArrayInputStream): SemanticClaimObject =
        when (readUnsignedByte()) {
            1 -> SemanticClaimObject.Entity(
                SemanticEntityReference(readString(input), readString(input))
            )
            2 -> SemanticClaimObject.Text(readString(input))
            3 -> SemanticClaimObject.Number(readString(input))
            4 -> SemanticClaimObject.BooleanValue(readBoolean())
            else -> throw IllegalArgumentException("unknown semantic claim object type")
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())

    private fun DataOutputStream.writeOptionalInstant(value: Instant?) {
        writeBoolean(value != null)
        value?.let { writeInstant(it) }
    }

    private fun DataInputStream.readOptionalInstant(): Instant? =
        if (readBoolean()) readInstant() else null
}
