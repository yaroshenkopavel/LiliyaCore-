package pro.liliya.core.memory

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

internal sealed interface MemoryPersistentDecodeResult {
    class Decoded(val record: MemoryRecord) : MemoryPersistentDecodeResult {
        override fun toString(): String = "Decoded(memoryRecordId=${record.id}, content=<redacted>)"
    }

    data object Corrupt : MemoryPersistentDecodeResult
    data class Incompatible(val reason: String) : MemoryPersistentDecodeResult
}

internal object MemoryPersistentRecordCodec {
    val schemaId = PersistentSchemaId("memory-record")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x4D454D31

    fun encode(record: MemoryRecord): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(record.id.value)
                data.writeString(record.provenance.sourceId.value)
                val reference = record.provenance.sourceReference
                data.writeBoolean(reference != null)
                if (reference != null) data.writeString(reference.value)
                data.writeString(record.content)
                data.writeLong(record.createdAt.epochSecond)
                data.writeInt(record.createdAt.nano)
            }
            output.toByteArray()
        }

        return PersistentRecord(
            id = PersistentEntityId(record.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.createdAt
        )
    }

    fun decode(persistent: PersistentRecord): MemoryPersistentDecodeResult {
        if (persistent.schemaId != schemaId) {
            return MemoryPersistentDecodeResult.Incompatible("persistent memory schema id mismatch")
        }
        if (persistent.schemaVersion != schemaVersion) {
            return MemoryPersistentDecodeResult.Incompatible("persistent memory schema version mismatch")
        }

        return try {
            val input = ByteArrayInputStream(persistent.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return MemoryPersistentDecodeResult.Corrupt

            val id = MemoryRecordId(data.readString(input))
            val sourceId = MemorySourceId(data.readString(input))
            val sourceReference = if (data.readBoolean()) {
                MemorySourceReference(data.readString(input))
            } else {
                null
            }
            val content = data.readString(input)
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())

            if (input.available() != 0) return MemoryPersistentDecodeResult.Corrupt
            if (persistent.id.value != id.value) return MemoryPersistentDecodeResult.Corrupt
            if (persistent.createdAt != createdAt) return MemoryPersistentDecodeResult.Corrupt

            MemoryPersistentDecodeResult.Decoded(
                MemoryRecord(
                    id = id,
                    provenance = MemoryProvenance(
                        sourceId = sourceId,
                        sourceReference = sourceReference
                    ),
                    content = content,
                    createdAt = createdAt
                )
            )
        } catch (_: EOFException) {
            MemoryPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            MemoryPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            MemoryPersistentDecodeResult.Corrupt
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
}
