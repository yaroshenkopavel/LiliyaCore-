package pro.liliya.core.autonomy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface AutonomyGoalExecutionPersistentDecodeResult {
    data class Decoded(
        val plan: AutonomyGoalExecutionPlan,
        val state: AutonomyGoalExecutionState
    ) : AutonomyGoalExecutionPersistentDecodeResult

    data object Corrupt : AutonomyGoalExecutionPersistentDecodeResult
    data class Incompatible(val reason: String) : AutonomyGoalExecutionPersistentDecodeResult
}

/** Deterministic checksummed codec containing structural governance identity only. */
internal object AutonomyGoalExecutionPersistentCodec {
    val schemaId = PersistentSchemaId("autonomy-goal-execution-transaction")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val MAGIC = 0x41474531 // AGE1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_STRING_BYTES = 16_384

    fun encode(
        plan: AutonomyGoalExecutionPlan,
        state: AutonomyGoalExecutionState
    ): PersistentRecord {
        val body = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(plan.id.value)
                data.writeByte(state.ordinal)
                data.writeLong(plan.createdAt.epochSecond)
                data.writeInt(plan.createdAt.nano)
                data.writeString(plan.identity.goalId.value)
                data.writeString(plan.identity.proposalId.value)
                data.writeString(plan.identity.scope.value)
                data.writeString(plan.identity.actionClass.value)
                data.writeString(plan.principal.value)
                data.writeString(plan.capability.value)
                data.writeString(plan.actionId.value)
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

    fun decode(record: PersistentRecord): AutonomyGoalExecutionPersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return AutonomyGoalExecutionPersistentDecodeResult.Incompatible(
                "autonomy execution transaction schema id mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return AutonomyGoalExecutionPersistentDecodeResult.Incompatible(
                "autonomy execution transaction schema version mismatch"
            )
        }

        val encoded = record.payload.copyBytes()
        if (encoded.size <= CHECKSUM_BYTES) return AutonomyGoalExecutionPersistentDecodeResult.Corrupt
        val body = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        val checksum = encoded.copyOfRange(encoded.size - CHECKSUM_BYTES, encoded.size)
        val actual = MessageDigest.getInstance("SHA-256").digest(body)
        if (!MessageDigest.isEqual(checksum, actual)) {
            return AutonomyGoalExecutionPersistentDecodeResult.Corrupt
        }

        return try {
            val input = ByteArrayInputStream(body)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return AutonomyGoalExecutionPersistentDecodeResult.Corrupt
            val id = AutonomyGoalExecutionTransactionId(data.readString(input))
            val state = enumValue<AutonomyGoalExecutionState>(data.readUnsignedByte())
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            val identity = AutonomyGoalAuthorityIdentity(
                goalId = AutonomyGoalId(data.readString(input)),
                proposalId = AutonomyGoalProposalId(data.readString(input)),
                scope = AutonomyGoalScope(data.readString(input)),
                actionClass = AutonomyActionClass(data.readString(input))
            )
            val plan = AutonomyGoalExecutionPlan(
                id = id,
                identity = identity,
                principal = AuthorityPrincipal(data.readString(input)),
                capability = CapabilityId(data.readString(input)),
                actionId = ExecutionActionId(data.readString(input)),
                createdAt = createdAt
            )
            if (input.available() != 0) return AutonomyGoalExecutionPersistentDecodeResult.Corrupt
            if (record.id.value != entityId(id) || record.createdAt != createdAt) {
                return AutonomyGoalExecutionPersistentDecodeResult.Corrupt
            }
            AutonomyGoalExecutionPersistentDecodeResult.Decoded(plan, state)
        } catch (_: EOFException) {
            AutonomyGoalExecutionPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            AutonomyGoalExecutionPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            AutonomyGoalExecutionPersistentDecodeResult.Corrupt
        }
    }

    internal fun entityId(id: AutonomyGoalExecutionTransactionId): String =
        "autonomy-goal-execution:${id.value}"

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("invalid enum ordinal")

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "autonomy execution identity field is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
