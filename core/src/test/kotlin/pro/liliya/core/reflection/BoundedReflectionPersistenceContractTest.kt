package pro.liliya.core.reflection

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaVersion

class BoundedReflectionPersistenceContractTest {
    private val t0 = Instant.parse("2026-09-28T12:30:00Z")

    @Test
    fun codec_round_trips_request_result_and_provenance() {
        val original = record()
        val encoded = BoundedReflectionPersistentCodec.encode(original)
        val decoded = assertIs<BoundedReflectionDecodeResult.Decoded>(
            BoundedReflectionPersistentCodec.decode(encoded)
        ).record

        assertEquals(original, decoded)
        assertEquals(original.request.evidence, decoded.request.evidence)
        assertEquals(original.request.outcomes, decoded.request.outcomes)
        assertEquals(original.result.findings, decoded.result.findings)
    }

    @Test
    fun codec_fails_closed_on_schema_version_identity_payload_or_timestamp_mismatch() {
        val original = record()
        val encoded = BoundedReflectionPersistentCodec.encode(original)

        assertIs<BoundedReflectionDecodeResult.Incompatible>(
            BoundedReflectionPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(99),
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )

        assertIs<BoundedReflectionDecodeResult.Corrupt>(
            BoundedReflectionPersistentCodec.decode(
                PersistentRecord(
                    PersistentEntityId("wrong-id"),
                    encoded.schemaId,
                    encoded.schemaVersion,
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )

        val damaged = encoded.payload.copyBytes().also {
            it[0] = (it[0].toInt() xor 0x55).toByte()
        }
        assertIs<BoundedReflectionDecodeResult.Corrupt>(
            BoundedReflectionPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    PersistentPayload(damaged),
                    encoded.createdAt
                )
            )
        )

        assertIs<BoundedReflectionDecodeResult.Corrupt>(
            BoundedReflectionPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    encoded.payload,
                    encoded.createdAt.plusSeconds(1)
                )
            )
        )
    }

    @Test
    fun record_rejects_result_from_different_request_or_policy() {
        val original = record()
        val otherRequest = ReflectionRequest.create(
            ReflectionVersion(1),
            evidence = listOf(evidence("observation", "other")),
            budget = original.request.budget,
            policyId = original.request.policyId,
            policyVersion = original.request.policyVersion,
            requestedAt = original.request.requestedAt
        )

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            BoundedReflectionRecord(otherRequest, original.result)
        }
    }

    @Test
    fun durable_record_revalidates_request_budget_and_provenance_fail_closed() {
        val original = record()
        val foreign = evidence("observation", "foreign")
        val foreignResult = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = original.request.id,
            findings = listOf(
                ReflectionFinding(
                    ReflectionFindingKind.EXPLANATION_CANDIDATE,
                    ReflectionFindingText("foreign provenance"),
                    evidence = listOf(foreign)
                )
            ),
            policyId = original.request.policyId,
            policyVersion = original.request.policyVersion,
            completedAt = original.result.completedAt
        )
        assertFailsWith<IllegalArgumentException> {
            BoundedReflectionRecord(original.request, foreignResult)
        }

        val tinyRequest = ReflectionRequest.create(
            version = ReflectionVersion(1),
            evidence = original.request.evidence,
            outcomes = original.request.outcomes,
            budget = ReflectionWorkBudget(1, 4),
            policyId = original.request.policyId,
            policyVersion = original.request.policyVersion,
            requestedAt = original.request.requestedAt
        )
        val oversizedResult = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = tinyRequest.id,
            findings = listOf(
                ReflectionFinding(
                    ReflectionFindingKind.EXPLANATION_CANDIDATE,
                    ReflectionFindingText("more than four bytes"),
                    evidence = tinyRequest.evidence
                )
            ),
            policyId = tinyRequest.policyId,
            policyVersion = tinyRequest.policyVersion,
            completedAt = original.result.completedAt
        )
        assertFailsWith<IllegalArgumentException> {
            BoundedReflectionRecord(tinyRequest, oversizedResult)
        }
    }

    private fun record(): BoundedReflectionRecord {
        val raw = evidence("observation", "obs-1")
        val outcome = ReflectionOutcomeReference(
            OutcomeEvaluationId("outcome-evaluation-" + "a".repeat(64)),
            OutcomeEvaluationVersion(1)
        )
        val request = ReflectionRequest.create(
            version = ReflectionVersion(1),
            evidence = listOf(raw),
            outcomes = listOf(outcome),
            budget = ReflectionWorkBudget(4, 4096),
            policyId = ReflectionPolicyId("bounded-reflection-v1"),
            policyVersion = ReflectionPolicyVersion(1),
            requestedAt = t0
        )
        val result = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = request.id,
            findings = listOf(
                ReflectionFinding(
                    ReflectionFindingKind.EXPLANATION_CANDIDATE,
                    ReflectionFindingText("bounded explanation candidate"),
                    evidence = listOf(raw),
                    outcomes = listOf(outcome)
                )
            ),
            policyId = request.policyId,
            policyVersion = request.policyVersion,
            completedAt = t0.plusSeconds(1)
        )
        return BoundedReflectionRecord(request, result)
    }

    private fun evidence(namespace: String, id: String) =
        RawEvidenceReference(RawEvidenceNamespace(namespace), RawEvidenceId(id))
}
