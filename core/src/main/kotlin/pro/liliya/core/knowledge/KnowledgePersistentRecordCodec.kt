package pro.liliya.core.knowledge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface KnowledgePersistentDecodeResult {
    class Decoded(val item: KnowledgeItem) : KnowledgePersistentDecodeResult {
        override fun toString(): String =
            "Decoded(knowledgeItemId=${item.id}, content=<redacted>)"
    }

    data object Corrupt : KnowledgePersistentDecodeResult
    data class Incompatible(val reason: String) : KnowledgePersistentDecodeResult
}

internal object KnowledgePersistentRecordCodec {
    val schemaId = PersistentSchemaId("knowledge-item")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x4B4E5731
    private const val ORIGIN_MEMORY = 1
    private const val ORIGIN_DECLARED = 2

    fun encode(item: KnowledgeItem): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(item.id.value)
                when (val origin = item.origin) {
                    is KnowledgeOrigin.Memory -> {
                        data.writeByte(ORIGIN_MEMORY)
                        data.writeString(origin.recordId.value)
                        data.writeLong(origin.generation.value)
                    }

                    is KnowledgeOrigin.Declared -> {
                        data.writeByte(ORIGIN_DECLARED)
                        data.writeString(origin.sourceId.value)
                        val reference = origin.sourceReference
                        data.writeBoolean(reference != null)
                        if (reference != null) data.writeString(reference.value)
                    }
                }
                data.writeString(item.content)
                data.writeLong(item.createdAt.epochSecond)
                data.writeInt(item.createdAt.nano)
            }
            output.toByteArray()
        }

        return PersistentRecord(
            id = PersistentEntityId(item.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = item.createdAt
        )
    }

    fun decode(persistent: PersistentRecord): KnowledgePersistentDecodeResult {
        if (persistent.schemaId != schemaId) {
            return KnowledgePersistentDecodeResult.Incompatible(
                "persistent knowledge schema id mismatch"
            )
        }
        if (persistent.schemaVersion != schemaVersion) {
            return KnowledgePersistentDecodeResult.Incompatible(
                "persistent knowledge schema version mismatch"
            )
        }

        return try {
            val input = ByteArrayInputStream(persistent.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return KnowledgePersistentDecodeResult.Corrupt

            val id = KnowledgeItemId(data.readString(input))
            val origin = when (data.readUnsignedByte()) {
                ORIGIN_MEMORY -> KnowledgeOrigin.Memory(
                    recordId = MemoryRecordId(data.readString(input)),
                    generation = MemoryGeneration(data.readLong())
                )

                ORIGIN_DECLARED -> {
                    val sourceId = KnowledgeSourceId(data.readString(input))
                    val sourceReference = if (data.readBoolean()) {
                        KnowledgeSourceReference(data.readString(input))
                    } else {
                        null
                    }
                    KnowledgeOrigin.Declared(sourceId, sourceReference)
                }

                else -> return KnowledgePersistentDecodeResult.Corrupt
            }
            val content = data.readString(input)
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())

            if (input.available() != 0) return KnowledgePersistentDecodeResult.Corrupt
            if (persistent.id.value != id.value) return KnowledgePersistentDecodeResult.Corrupt
            if (persistent.createdAt != createdAt) return KnowledgePersistentDecodeResult.Corrupt

            KnowledgePersistentDecodeResult.Decoded(
                KnowledgeItem(
                    id = id,
                    origin = origin,
                    content = content,
                    createdAt = createdAt
                )
            )
        } catch (_: EOFException) {
            KnowledgePersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            KnowledgePersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            KnowledgePersistentDecodeResult.Corrupt
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
