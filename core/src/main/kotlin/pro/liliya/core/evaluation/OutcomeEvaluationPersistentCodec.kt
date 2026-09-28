package pro.liliya.core.evaluation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId

internal sealed interface OutcomeEvaluationDecodeResult {
    data class Decoded(val record: OutcomeEvaluationRecord) : OutcomeEvaluationDecodeResult
    data object Corrupt : OutcomeEvaluationDecodeResult
    data class Incompatible(val reason: String) : OutcomeEvaluationDecodeResult
}

internal object OutcomeEvaluationPersistentCodec {
    val schemaId = PersistentSchemaId("outcome-evaluation-record")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x4f455631
    private const val MAX_STRING_BYTES = 16 * 1024
    fun encode(record: OutcomeEvaluationRecord): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(record.id.value)
                data.writeLong(record.version.value)
                data.writeString(record.plan.id.value)
                data.writeLong(record.plan.generation.value)
                data.writeString(record.decision.id.value)
                data.writeLong(record.decision.generation.value)
                data.writeBoolean(record.authorizedActionId != null)
                record.authorizedActionId?.let { data.writeString(it.value) }
                data.writeString(record.expected.value)
                data.writeString(record.observed.value)
                data.writeInt(record.evidence.size)
                record.evidence.forEach {
                    data.writeString(it.namespace.value)
                    data.writeString(it.id.value)
                }
                data.writeInt(record.validationReferences.size)
                record.validationReferences.forEach { data.writeString(it.value) }
                data.writeString(record.status.name)
                data.writeString(record.evaluatorPolicyId.value)
                data.writeInt(record.evaluatorPolicyVersion.value)
                data.writeInstant(record.evaluatedAt)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(record.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.evaluatedAt
        )
    }
    fun decode(record: PersistentRecord): OutcomeEvaluationDecodeResult {
        if (record.schemaId != schemaId) {
            return OutcomeEvaluationDecodeResult.Incompatible("outcome evaluation schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return OutcomeEvaluationDecodeResult.Incompatible("outcome evaluation schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return OutcomeEvaluationDecodeResult.Corrupt

            val id = OutcomeEvaluationId(data.readString(input))
            val version = OutcomeEvaluationVersion(data.readLong())
            val plan = OutcomePlanReference(
                PlanningProposalId(data.readString(input)),
                PlanningGeneration(data.readLong())
            )
            val decision = OutcomeDecisionReference(
                DecisionId(data.readString(input)),
                DecisionGeneration(data.readLong())
            )
            val action = if (data.readBoolean()) ExecutionActionId(data.readString(input)) else null
            val expected = OutcomeText(data.readString(input))
            val observed = OutcomeText(data.readString(input))

            val evidenceCount = data.readInt()
            if (evidenceCount !in 1..OutcomeEvaluationRecord.MAX_EVIDENCE_REFERENCES) {
                return OutcomeEvaluationDecodeResult.Corrupt
            }
            val evidence = List(evidenceCount) {
                RawEvidenceReference(
                    RawEvidenceNamespace(data.readString(input)),
                    RawEvidenceId(data.readString(input))
                )
            }
            val validationCount = data.readInt()
            if (validationCount !in 0..OutcomeEvaluationRecord.MAX_VALIDATION_REFERENCES) {
                return OutcomeEvaluationDecodeResult.Corrupt
            }
            val validations = List(validationCount) {
                OutcomeValidationReference(data.readString(input))
            }
            val status = OutcomeEvaluationStatus.valueOf(data.readString(input))
            val policyId = EvaluatorPolicyId(data.readString(input))
            val policyVersion = EvaluatorPolicyVersion(data.readInt())
            val evaluatedAt = data.readInstant()
            if (input.available() != 0) return OutcomeEvaluationDecodeResult.Corrupt

            val decoded = OutcomeEvaluationRecord(
                id = id,
                version = version,
                plan = plan,
                decision = decision,
                authorizedActionId = action,
                expected = expected,
                observed = observed,
                evidence = evidence,
                validationReferences = validations,
                status = status,
                evaluatorPolicyId = policyId,
                evaluatorPolicyVersion = policyVersion,
                evaluatedAt = evaluatedAt
            )
            if (record.id.value != decoded.id.value || record.createdAt != decoded.evaluatedAt) {
                OutcomeEvaluationDecodeResult.Corrupt
            } else {
                OutcomeEvaluationDecodeResult.Decoded(decoded)
            }
        } catch (_: EOFException) {
            OutcomeEvaluationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            OutcomeEvaluationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            OutcomeEvaluationDecodeResult.Corrupt
        }
    }
    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "outcome evaluation string exceeds codec bound" }
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

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())
}
