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

internal sealed interface EpisodeIndexDecodeResult {
    data class Decoded(val entry: EpisodeIndexEntry) : EpisodeIndexDecodeResult
    data object Corrupt : EpisodeIndexDecodeResult
    data class Incompatible(val reason: String) : EpisodeIndexDecodeResult
}

internal object EpisodicIndexPersistentCodec {
    val observedSchemaId = PersistentSchemaId("episodic-index-observed")
    val eventSchemaId = PersistentSchemaId("episodic-index-event")
    val derivedSchemaId = PersistentSchemaId("episodic-index-derived")
    val provenanceSchemaId = PersistentSchemaId("episodic-index-provenance")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x45495831 // EIX1
    private const val KIND_TEMPORAL = 1
    private const val KIND_PROVENANCE = 2

    fun encode(entry: EpisodeIndexEntry): PersistentRecord {
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                when (entry) {
                    is EpisodeTemporalIndexEntry -> {
                        data.writeInt(KIND_TEMPORAL)
                        data.writeInt(entry.axis.ordinal)
                        data.writeInstant(entry.indexedAt)
                        data.writeString(entry.episodeId.value)
                        data.writeLong(entry.episodeGeneration)
                    }
                    is EpisodeProvenanceIndexEntry -> {
                        data.writeInt(KIND_PROVENANCE)
                        data.writeString(entry.namespace.value)
                        data.writeInstant(entry.episodeDerivedAt)
                        data.writeString(entry.episodeId.value)
                        data.writeLong(entry.episodeGeneration)
                    }
                }
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(entry.id.value),
            schemaId = schemaId(entry),
            schemaVersion = schemaVersion,
            payload = PersistentPayload(payload),
            createdAt = entry.sortAt
        )
    }

    fun decode(record: PersistentRecord): EpisodeIndexDecodeResult {
        if (record.schemaVersion != schemaVersion) {
            return EpisodeIndexDecodeResult.Incompatible("episodic index schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return EpisodeIndexDecodeResult.Corrupt
            val entry = when (data.readInt()) {
                KIND_TEMPORAL -> {
                    val axis = EpisodeTemporalAxis.entries.getOrNull(data.readInt())
                        ?: return EpisodeIndexDecodeResult.Corrupt
                    val indexedAt = data.readInstant()
                    EpisodeTemporalIndexEntry(
                        axis = axis,
                        indexedAt = indexedAt,
                        episodeId = EpisodeId(data.readString(input)),
                        episodeGeneration = data.readLong()
                    )
                }
                KIND_PROVENANCE -> EpisodeProvenanceIndexEntry(
                    namespace = RawEvidenceNamespace(data.readString(input)),
                    episodeDerivedAt = data.readInstant(),
                    episodeId = EpisodeId(data.readString(input)),
                    episodeGeneration = data.readLong()
                )
                else -> return EpisodeIndexDecodeResult.Corrupt
            }
            if (input.available() != 0) return EpisodeIndexDecodeResult.Corrupt
            if (record.id.value != entry.id.value || record.createdAt != entry.sortAt) {
                return EpisodeIndexDecodeResult.Corrupt
            }
            if (record.schemaId != schemaId(entry)) {
                return EpisodeIndexDecodeResult.Incompatible("episodic index schema id mismatch")
            }
            EpisodeIndexDecodeResult.Decoded(entry)
        } catch (_: EOFException) {
            EpisodeIndexDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            EpisodeIndexDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            EpisodeIndexDecodeResult.Corrupt
        }
    }

    fun temporalSchemaId(axis: EpisodeTemporalAxis): PersistentSchemaId = when (axis) {
        EpisodeTemporalAxis.OBSERVED -> observedSchemaId
        EpisodeTemporalAxis.EVENT -> eventSchemaId
        EpisodeTemporalAxis.DERIVED -> derivedSchemaId
    }

    private fun schemaId(entry: EpisodeIndexEntry): PersistentSchemaId = when (entry) {
        is EpisodeTemporalIndexEntry -> temporalSchemaId(entry.axis)
        is EpisodeProvenanceIndexEntry -> provenanceSchemaId
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 1 || length > input.available()) throw EOFException()
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
