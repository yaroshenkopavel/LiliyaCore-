package pro.liliya.core.episodic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface EpisodePersistentDecodeResult {
    data class Decoded(val record: EpisodeRecord) : EpisodePersistentDecodeResult
    data object Corrupt : EpisodePersistentDecodeResult
    data class Incompatible(val reason: String) : EpisodePersistentDecodeResult
}

internal object EpisodicMemoryPersistentCodec {
    val schemaId = PersistentSchemaId("episodic-memory-record")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x45505331
    private const val MAX_EVIDENCE_REFERENCES = 256

    fun encode(record: EpisodeRecord): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(record.id.value)
                data.writeInt(record.evidence.size)
                record.evidence.forEach { reference ->
                    data.writeString(reference.namespace.value)
                    data.writeString(reference.id.value)
                }
                data.writeString(record.description)
                data.writeInstant(record.observedAt)
                data.writeBoolean(record.eventAt != null)
                record.eventAt?.let { data.writeInstant(it) }
                data.writeInstant(record.derivedAt)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(record.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.derivedAt
        )
    }

    fun decode(record: PersistentRecord): EpisodePersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return EpisodePersistentDecodeResult.Incompatible("episodic schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return EpisodePersistentDecodeResult.Incompatible("episodic schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return EpisodePersistentDecodeResult.Corrupt
            val id = EpisodeId(data.readString(input))
            val evidenceCount = data.readInt()
            if (evidenceCount !in 1..MAX_EVIDENCE_REFERENCES) {
                return EpisodePersistentDecodeResult.Corrupt
            }
            val evidence = List(evidenceCount) {
                RawEvidenceReference(
                    RawEvidenceNamespace(data.readString(input)),
                    RawEvidenceId(data.readString(input))
                )
            }
            val description = data.readString(input)
            val observedAt = data.readInstant()
            val eventAt = if (data.readBoolean()) data.readInstant() else null
            val derivedAt = data.readInstant()
            if (input.available() != 0) return EpisodePersistentDecodeResult.Corrupt
            if (record.id.value != id.value || record.createdAt != derivedAt) {
                return EpisodePersistentDecodeResult.Corrupt
            }
            EpisodePersistentDecodeResult.Decoded(
                EpisodeRecord(id, evidence, description, observedAt, eventAt, derivedAt)
            )
        } catch (_: EOFException) {
            EpisodePersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            EpisodePersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            EpisodePersistentDecodeResult.Corrupt
        }
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
}
