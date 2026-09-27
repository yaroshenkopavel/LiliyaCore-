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

internal sealed interface MemoryRetentionTransactionPersistentDecodeResult {
    data class Decoded(
        val plan: MemoryRetentionTransactionPlan,
        val state: MemoryRetentionTransactionState
    ) : MemoryRetentionTransactionPersistentDecodeResult

    data object Corrupt : MemoryRetentionTransactionPersistentDecodeResult
    data class Incompatible(val reason: String) : MemoryRetentionTransactionPersistentDecodeResult
}

/**
 * Deterministic identity-only codec for retention recovery state.
 *
 * No MemoryRecord content, source reference, Authority receipt or capability token is encoded.
 * The SHA-256 trailer detects torn/corrupted payloads before restoration can expose a journal entry.
 */
internal object MemoryRetentionTransactionPersistentCodec {
    val schemaId = PersistentSchemaId("memory-retention-transaction")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x4D525431 // MRT1
    private const val CHECKSUM_BYTES = 32

    fun encode(
        plan: MemoryRetentionTransactionPlan,
        state: MemoryRetentionTransactionState
    ): PersistentRecord {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(plan.id.value)
                data.writeByte(state.ordinal)
                data.writeLong(plan.createdAt.epochSecond)
                data.writeInt(plan.createdAt.nano)
                val targets = MemoryRetentionTransactionPlan.canonicalTargets(plan.targets)
                data.writeInt(targets.size)
                for (target in targets) {
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

    fun decode(record: PersistentRecord): MemoryRetentionTransactionPersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return MemoryRetentionTransactionPersistentDecodeResult.Incompatible(
                "memory retention transaction schema id mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return MemoryRetentionTransactionPersistentDecodeResult.Incompatible(
                "memory retention transaction schema version mismatch"
            )
        }

        val encoded = record.payload.copyBytes()
        if (encoded.size <= CHECKSUM_BYTES) return MemoryRetentionTransactionPersistentDecodeResult.Corrupt
        val body = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        val checksum = encoded.copyOfRange(encoded.size - CHECKSUM_BYTES, encoded.size)
        val actual = MessageDigest.getInstance("SHA-256").digest(body)
        if (!MessageDigest.isEqual(checksum, actual)) {
            return MemoryRetentionTransactionPersistentDecodeResult.Corrupt
        }

        return try {
            val input = ByteArrayInputStream(body)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return MemoryRetentionTransactionPersistentDecodeResult.Corrupt
            val id = MemoryRetentionTransactionId(data.readString(input))
            val state = enumValue<MemoryRetentionTransactionState>(data.readUnsignedByte())
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            val targetCount = data.readInt()
            if (targetCount <= 0 || targetCount > 100_000) {
                return MemoryRetentionTransactionPersistentDecodeResult.Corrupt
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
            if (input.available() != 0) return MemoryRetentionTransactionPersistentDecodeResult.Corrupt

            val plan = MemoryRetentionTransactionPlan(id, targets, createdAt)
            if (record.id.value != entityId(id) || record.createdAt != createdAt) {
                return MemoryRetentionTransactionPersistentDecodeResult.Corrupt
            }
            MemoryRetentionTransactionPersistentDecodeResult.Decoded(plan, state)
        } catch (_: EOFException) {
            MemoryRetentionTransactionPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            MemoryRetentionTransactionPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            MemoryRetentionTransactionPersistentDecodeResult.Corrupt
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

    internal fun entityId(id: MemoryRetentionTransactionId): String =
        "memory-retention-transaction:${id.value}"
}
