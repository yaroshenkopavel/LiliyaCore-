package pro.liliya.core.reflection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface BoundedReflectionDecodeResult {
    data class Decoded(val record: BoundedReflectionRecord) : BoundedReflectionDecodeResult
    data object Corrupt : BoundedReflectionDecodeResult
    data class Incompatible(val reason: String) : BoundedReflectionDecodeResult
}

internal object BoundedReflectionPersistentCodec {
    val schemaId = PersistentSchemaId("bounded-reflection-record")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x42524631
    private const val MAX_STRING_BYTES = 8 * 1024

    fun encode(record: BoundedReflectionRecord): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                val request = record.request
                val result = record.result
                data.writeInt(MAGIC)
                data.writeString(request.id.value)
                data.writeLong(request.version.value)
                data.writeInt(request.evidence.size)
                request.evidence.forEach {
                    data.writeString(it.namespace.value)
                    data.writeString(it.id.value)
                }
                data.writeInt(request.outcomes.size)
                request.outcomes.forEach {
                    data.writeString(it.id.value)
                    data.writeLong(it.version.value)
                }
                data.writeInt(request.budget.maxFindings)
                data.writeInt(request.budget.maxTotalOutputUtf8Bytes)
                data.writeString(request.policyId.value)
                data.writeInt(request.policyVersion.value)
                data.writeInstant(request.requestedAt)

                data.writeString(result.id.value)
                data.writeLong(result.version.value)
                data.writeInt(result.findings.size)
                result.findings.forEach { finding ->
                    data.writeString(finding.kind.name)
                    data.writeString(finding.text.value)
                    data.writeInt(finding.evidence.size)
                    finding.evidence.forEach {
                        data.writeString(it.namespace.value)
                        data.writeString(it.id.value)
                    }
                    data.writeInt(finding.outcomes.size)
                    finding.outcomes.forEach {
                        data.writeString(it.id.value)
                        data.writeLong(it.version.value)
                    }
                }
                data.writeString(result.policyId.value)
                data.writeInt(result.policyVersion.value)
                data.writeInstant(result.completedAt)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(record.result.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.result.completedAt
        )
    }

    fun decode(record: PersistentRecord): BoundedReflectionDecodeResult {
        if (record.schemaId != schemaId) {
            return BoundedReflectionDecodeResult.Incompatible("bounded reflection schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return BoundedReflectionDecodeResult.Incompatible("bounded reflection schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return BoundedReflectionDecodeResult.Corrupt

            val requestId = ReflectionRequestId(data.readString(input))
            val requestVersion = ReflectionVersion(data.readLong())
            val evidenceCount = data.readInt()
            if (evidenceCount !in 0..ReflectionRequest.MAX_EVIDENCE_REFERENCES) {
                return BoundedReflectionDecodeResult.Corrupt
            }
            val evidence = List(evidenceCount) {
                RawEvidenceReference(
                    RawEvidenceNamespace(data.readString(input)),
                    RawEvidenceId(data.readString(input))
                )
            }
            val outcomeCount = data.readInt()
            if (outcomeCount !in 0..ReflectionRequest.MAX_OUTCOME_REFERENCES) {
                return BoundedReflectionDecodeResult.Corrupt
            }
            val outcomes = List(outcomeCount) {
                ReflectionOutcomeReference(
                    OutcomeEvaluationId(data.readString(input)),
                    OutcomeEvaluationVersion(data.readLong())
                )
            }
            val budget = ReflectionWorkBudget(data.readInt(), data.readInt())
            val policyId = ReflectionPolicyId(data.readString(input))
            val policyVersion = ReflectionPolicyVersion(data.readInt())
            val requestedAt = data.readInstant()
            val request = ReflectionRequest(
                requestId,
                requestVersion,
                evidence,
                outcomes,
                budget,
                policyId,
                policyVersion,
                requestedAt
            )

            val resultId = ReflectionResultId(data.readString(input))
            val resultVersion = ReflectionVersion(data.readLong())
            val findingCount = data.readInt()
            if (findingCount !in 1..ReflectionWorkBudget.MAX_FINDINGS) {
                return BoundedReflectionDecodeResult.Corrupt
            }
            val findings = List(findingCount) {
                val kind = ReflectionFindingKind.valueOf(data.readString(input))
                val text = ReflectionFindingText(data.readString(input))
                val feCount = data.readInt()
                if (feCount !in 0..ReflectionRequest.MAX_EVIDENCE_REFERENCES) {
                    return BoundedReflectionDecodeResult.Corrupt
                }
                val fe = List(feCount) {
                    RawEvidenceReference(
                        RawEvidenceNamespace(data.readString(input)),
                        RawEvidenceId(data.readString(input))
                    )
                }
                val foCount = data.readInt()
                if (foCount !in 0..ReflectionRequest.MAX_OUTCOME_REFERENCES) {
                    return BoundedReflectionDecodeResult.Corrupt
                }
                val fo = List(foCount) {
                    ReflectionOutcomeReference(
                        OutcomeEvaluationId(data.readString(input)),
                        OutcomeEvaluationVersion(data.readLong())
                    )
                }
                ReflectionFinding(kind, text, fe, fo)
            }
            val resultPolicyId = ReflectionPolicyId(data.readString(input))
            val resultPolicyVersion = ReflectionPolicyVersion(data.readInt())
            val completedAt = data.readInstant()
            if (input.available() != 0) return BoundedReflectionDecodeResult.Corrupt
            val result = BoundedReflectionResult(
                resultId,
                resultVersion,
                request.id,
                findings,
                resultPolicyId,
                resultPolicyVersion,
                completedAt
            )
            val decoded = BoundedReflectionRecord(request, result)
            if (record.id.value != result.id.value || record.createdAt != result.completedAt) {
                BoundedReflectionDecodeResult.Corrupt
            } else {
                BoundedReflectionDecodeResult.Decoded(decoded)
            }
        } catch (_: EOFException) {
            BoundedReflectionDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            BoundedReflectionDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            BoundedReflectionDecodeResult.Corrupt
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "bounded reflection string exceeds codec bound" }
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
