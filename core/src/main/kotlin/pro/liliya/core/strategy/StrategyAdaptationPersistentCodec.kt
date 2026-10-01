package pro.liliya.core.strategy

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
import pro.liliya.core.reflection.ReflectionFindingKind
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion

internal sealed interface StrategyAdaptationDecodeResult {
    data class Decoded(val record: StrategyAdaptationRecord) : StrategyAdaptationDecodeResult
    data object Corrupt : StrategyAdaptationDecodeResult
    data class Incompatible(val reason: String) : StrategyAdaptationDecodeResult
}

internal object StrategyAdaptationPersistentCodec {
    val schemaId = PersistentSchemaId("strategy-adaptation-record")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x53545631
    private const val MAX_STRING_BYTES = 16 * 1024

    fun encode(record: StrategyAdaptationRecord): PersistentRecord {
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                writeCandidate(data, record.candidate)
                writeValidation(data, record.validation)
                writeAdoption(data, record.adoption)
                data.writeBoolean(record.applicationIntent != null)
                record.applicationIntent?.let { writeApplicationIntent(data, it) }
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(record.candidate.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(payload),
            createdAt = record.applicationIntent?.createdAt ?: record.adoption.decidedAt
        )
    }

    fun decode(record: PersistentRecord): StrategyAdaptationDecodeResult {
        if (record.schemaId != schemaId) {
            return StrategyAdaptationDecodeResult.Incompatible("strategy adaptation schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return StrategyAdaptationDecodeResult.Incompatible(
                "strategy adaptation schema version mismatch"
            )
        }

        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return StrategyAdaptationDecodeResult.Corrupt

            val candidate = readCandidate(data, input)
            val validation = readValidation(data, input)
            val adoption = readAdoption(data, input)
            val applicationIntent =
                if (data.readBoolean()) readApplicationIntent(data, input) else null

            if (input.available() != 0) return StrategyAdaptationDecodeResult.Corrupt

            val decoded = StrategyAdaptationRecord(
                candidate = candidate,
                validation = validation,
                adoption = adoption,
                applicationIntent = applicationIntent
            )
            val expectedCreatedAt = applicationIntent?.createdAt ?: adoption.decidedAt
            if (record.id.value != candidate.id.value || record.createdAt != expectedCreatedAt) {
                StrategyAdaptationDecodeResult.Corrupt
            } else {
                StrategyAdaptationDecodeResult.Decoded(decoded)
            }
        } catch (_: EOFException) {
            StrategyAdaptationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            StrategyAdaptationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            StrategyAdaptationDecodeResult.Corrupt
        }
    }

    private fun writeCandidate(data: DataOutputStream, candidate: StrategyCandidate) {
        data.writeString(candidate.id.value)
        data.writeLong(candidate.version.value)
        data.writeString(candidate.source.resultId.value)
        data.writeLong(candidate.source.resultVersion.value)
        data.writeInt(candidate.source.findingIndex)
        data.writeString(candidate.source.findingKind.name)
        data.writeString(candidate.target.name)
        data.writeString(candidate.scope.value)
        data.writeString(candidate.proposal.value)
        data.writeInt(candidate.compatibility.size)
        candidate.compatibility.forEach {
            data.writeString(it.key)
            data.writeString(it.expected)
        }
        data.writeBoolean(candidate.rollbackTo != null)
        candidate.rollbackTo?.let {
            data.writeString(it.id.value)
            data.writeLong(it.version.value)
        }
        data.writeInstant(candidate.createdAt)
        data.writeInstant(candidate.expiresAt)
    }

    private fun readCandidate(
        data: DataInputStream,
        input: ByteArrayInputStream
    ): StrategyCandidate {
        val id = StrategyCandidateId(data.readString(input))
        val version = StrategyVersion(data.readLong())
        val source = StrategyReflectionSource(
            ReflectionResultId(data.readString(input)),
            ReflectionVersion(data.readLong()),
            data.readInt(),
            ReflectionFindingKind.valueOf(data.readString(input))
        )
        val target = StrategyTarget.valueOf(data.readString(input))
        val scope = StrategyScope(data.readString(input))
        val proposal = StrategyText(data.readString(input))
        val compatibilityCount = data.readInt()
        if (compatibilityCount !in 1..StrategyCandidate.MAX_COMPATIBILITY_CONSTRAINTS) {
            throw EOFException()
        }
        val compatibility = List(compatibilityCount) {
            StrategyCompatibilityConstraint(data.readString(input), data.readString(input))
        }
        val rollbackTo = if (data.readBoolean()) {
            StrategyReference(
                StrategyCandidateId(data.readString(input)),
                StrategyVersion(data.readLong())
            )
        } else null
        val createdAt = data.readInstant()
        val expiresAt = data.readInstant()
        return StrategyCandidate(
            id, version, source, target, scope, proposal, compatibility, rollbackTo,
            createdAt, expiresAt
        )
    }

    private fun writeValidation(data: DataOutputStream, validation: StrategyValidationRecord) {
        data.writeString(validation.id.value)
        data.writeString(validation.candidate.id.value)
        data.writeLong(validation.candidate.version.value)
        data.writeString(validation.disposition.name)
        data.writeInt(validation.constraintResults.size)
        validation.constraintResults.forEach {
            data.writeString(it.constraint.key)
            data.writeString(it.constraint.expected)
            data.writeString(it.disposition.name)
            data.writeString(it.reason)
        }
        data.writeString(validation.policyId.value)
        data.writeInt(validation.policyVersion.value)
        data.writeInstant(validation.validatedAt)
    }

    private fun readValidation(
        data: DataInputStream,
        input: ByteArrayInputStream
    ): StrategyValidationRecord {
        val id = StrategyValidationId(data.readString(input))
        val candidate = StrategyReference(
            StrategyCandidateId(data.readString(input)),
            StrategyVersion(data.readLong())
        )
        val disposition = StrategyValidationDisposition.valueOf(data.readString(input))
        val resultCount = data.readInt()
        if (resultCount !in 1..StrategyCandidate.MAX_COMPATIBILITY_CONSTRAINTS) {
            throw EOFException()
        }
        val results = List(resultCount) {
            StrategyConstraintResult(
                StrategyCompatibilityConstraint(
                    data.readString(input),
                    data.readString(input)
                ),
                StrategyConstraintDisposition.valueOf(data.readString(input)),
                data.readString(input)
            )
        }
        val policyId = StrategyPolicyId(data.readString(input))
        val policyVersion = StrategyPolicyVersion(data.readInt())
        val validatedAt = data.readInstant()
        return StrategyValidationRecord(
            id, candidate, disposition, results, policyId, policyVersion, validatedAt
        )
    }

    private fun writeAdoption(data: DataOutputStream, adoption: StrategyAdoptionRecord) {
        data.writeString(adoption.id.value)
        data.writeString(adoption.candidate.id.value)
        data.writeLong(adoption.candidate.version.value)
        data.writeString(adoption.validation.id.value)
        data.writeString(adoption.validation.candidate.id.value)
        data.writeLong(adoption.validation.candidate.version.value)
        data.writeString(adoption.disposition.name)
        data.writeString(adoption.rationale)
        data.writeInstant(adoption.decidedAt)
    }

    private fun readAdoption(
        data: DataInputStream,
        input: ByteArrayInputStream
    ): StrategyAdoptionRecord {
        val id = StrategyAdoptionId(data.readString(input))
        val candidate = StrategyReference(
            StrategyCandidateId(data.readString(input)),
            StrategyVersion(data.readLong())
        )
        val validation = StrategyValidationReference(
            StrategyValidationId(data.readString(input)),
            StrategyReference(
                StrategyCandidateId(data.readString(input)),
                StrategyVersion(data.readLong())
            )
        )
        val disposition = StrategyAdoptionDisposition.valueOf(data.readString(input))
        val rationale = data.readString(input)
        val decidedAt = data.readInstant()
        return StrategyAdoptionRecord(
            id, candidate, validation, disposition, rationale, decidedAt
        )
    }

    private fun writeApplicationIntent(
        data: DataOutputStream,
        intent: StrategyApplicationIntent
    ) {
        data.writeString(intent.id.value)
        data.writeString(intent.candidate.id.value)
        data.writeLong(intent.candidate.version.value)
        data.writeString(intent.adoption.id.value)
        data.writeString(intent.adoption.candidate.id.value)
        data.writeLong(intent.adoption.candidate.version.value)
        data.writeString(intent.target.name)
        data.writeString(intent.scope.value)
        data.writeInstant(intent.createdAt)
    }

    private fun readApplicationIntent(
        data: DataInputStream,
        input: ByteArrayInputStream
    ): StrategyApplicationIntent {
        val id = StrategyApplicationIntentId(data.readString(input))
        val candidate = StrategyReference(
            StrategyCandidateId(data.readString(input)),
            StrategyVersion(data.readLong())
        )
        val adoption = StrategyAdoptionReference(
            StrategyAdoptionId(data.readString(input)),
            StrategyReference(
                StrategyCandidateId(data.readString(input)),
                StrategyVersion(data.readLong())
            )
        )
        val target = StrategyTarget.valueOf(data.readString(input))
        val scope = StrategyScope(data.readString(input))
        val createdAt = data.readInstant()
        return StrategyApplicationIntent(id, candidate, adoption, target, scope, createdAt)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw EOFException()
        }
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
