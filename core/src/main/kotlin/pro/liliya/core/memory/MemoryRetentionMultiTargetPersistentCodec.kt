package pro.liliya.core.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface MemoryRetentionMultiTargetPersistentDecodeResult {
    data class Decoded(
        val plan: MemoryRetentionMultiTargetPlan,
        val nextIndex: Int,
        val state: MemoryRetentionMultiTargetState
    ) : MemoryRetentionMultiTargetPersistentDecodeResult

    data object Corrupt : MemoryRetentionMultiTargetPersistentDecodeResult
    data class Incompatible(val reason: String) : MemoryRetentionMultiTargetPersistentDecodeResult
}

internal object MemoryRetentionMultiTargetPersistentCodec {
    val schemaId = PersistentSchemaId("memory-retention-multi-target")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x4D524D31 // MRM1
    private const val CHECKSUM_BYTES = 32

    fun encode(
        plan: MemoryRetentionMultiTargetPlan,
        nextIndex: Int,
        state: MemoryRetentionMultiTargetState
    ): PersistentRecord {
        val snapshot = MemoryRetentionMultiTargetSnapshot(
            plan = plan,
            generation = MemoryRetentionMultiTargetGeneration(1),
            nextIndex = nextIndex,
            state = state
        )
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(plan.id.value)
                data.writeInt(snapshot.nextIndex)
                data.writeByte(snapshot.state.ordinal)
                data.writeLong(plan.createdAt.epochSecond)
                data.writeInt(plan.createdAt.nano)
                data.writeInt(plan.targets.size)
                for (target in plan.targets) {
                    data.writeString(target.recordId.value)
                    data.writeLong(target.generation.value)
                    data.writeByte(target.retentionClass.ordinal)
                    data.writeByte(target.disposition.ordinal)
                }
            }
            output.toByteArray()
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(body)
        return PersistentRecord(
            id = PersistentEntityId(entityId(plan.id)),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(body + checksum),
            createdAt = plan.createdAt
        )
    }

    fun decode(record: PersistentRecord): MemoryRetentionMultiTargetPersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return MemoryRetentionMultiTargetPersistentDecodeResult.Incompatible(
                "memory retention multi-target schema id mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return MemoryRetentionMultiTargetPersistentDecodeResult.Incompatible(
                "memory retention multi-target schema version mismatch"
            )
        }

        val encoded = record.payload.copyBytes()
        if (encoded.size <= CHECKSUM_BYTES) return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
        val body = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        val checksum = encoded.copyOfRange(encoded.size - CHECKSUM_BYTES, encoded.size)
        val actual = MessageDigest.getInstance("SHA-256").digest(body)
        if (!MessageDigest.isEqual(checksum, actual)) {
            return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
        }

        return try {
            val input = ByteArrayInputStream(body)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
            val id = MemoryRetentionMultiTargetId(data.readString(input))
            val nextIndex = data.readInt()
            val state = enumValue<MemoryRetentionMultiTargetState>(data.readUnsignedByte())
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            val targetCount = data.readInt()
            if (targetCount < 1 || targetCount > 100_000) {
                return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
            }
            val targets = ArrayList<MemoryRetentionTransactionTarget>(targetCount)
            repeat(targetCount) {
                targets += MemoryRetentionTransactionTarget(
                    recordId = MemoryRecordId(data.readString(input)),
                    generation = MemoryGeneration(data.readLong()),
                    retentionClass = enumValue(data.readUnsignedByte()),
                    disposition = enumValue(data.readUnsignedByte())
                )
            }
            if (input.available() != 0) return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt

            val plan = MemoryRetentionMultiTargetPlan(id, targets, createdAt)
            MemoryRetentionMultiTargetSnapshot(
                plan = plan,
                generation = MemoryRetentionMultiTargetGeneration(1),
                nextIndex = nextIndex,
                state = state
            )
            if (record.id.value != entityId(id) || record.createdAt != createdAt) {
                return MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
            }
            MemoryRetentionMultiTargetPersistentDecodeResult.Decoded(plan, nextIndex, state)
        } catch (_: EOFException) {
            MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("invalid enum ordinal")

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

    internal fun entityId(id: MemoryRetentionMultiTargetId): String =
        "memory-retention-multi-target:${id.value}"
}
