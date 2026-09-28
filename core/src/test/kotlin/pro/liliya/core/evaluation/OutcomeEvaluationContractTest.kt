package pro.liliya.core.evaluation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId

class OutcomeEvaluationContractTest {
    private val evaluatedAt = Instant.parse("2026-09-28T10:30:00Z")

    @Test
    fun deterministic_identity_is_order_independent_for_reference_inputs() {
        val first = record(
            evidence = listOf(evidence("z", "2"), evidence("a", "1")),
            validations = listOf(
                OutcomeValidationReference("validator-z"),
                OutcomeValidationReference("validator-a")
            )
        )
        val replay = record(
            evidence = listOf(evidence("a", "1"), evidence("z", "2")),
            validations = listOf(
                OutcomeValidationReference("validator-a"),
                OutcomeValidationReference("validator-z")
            )
        )

        assertEquals(first, replay)
        assertEquals(first.id, replay.id)
        assertEquals(listOf("a", "z"), first.evidence.map { it.namespace.value })
        assertEquals(
            listOf("validator-a", "validator-z"),
            first.validationReferences.map { it.value }
        )
    }

    @Test
    fun identity_changes_when_evaluation_version_or_status_changes() {
        val base = record()
        val nextVersion = record(version = 2L)
        val failure = record(status = OutcomeEvaluationStatus.FAILURE)

        assertNotEquals(base.id, nextVersion.id)
        assertNotEquals(base.id, failure.id)
    }

    @Test
    fun raw_evidence_is_mandatory_and_bounded_text_is_enforced() {
        assertFailsWith<IllegalArgumentException> {
            record(evidence = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            OutcomeText("x".repeat(OutcomeText.MAX_UTF8_BYTES + 1))
        }
    }

    @Test
    fun duplicate_evidence_and_validation_references_fail_closed() {
        val duplicateEvidence = evidence("observation", "dup")
        assertFailsWith<IllegalArgumentException> {
            record(evidence = listOf(duplicateEvidence, duplicateEvidence))
        }

        val duplicateValidation = OutcomeValidationReference("validation-dup")
        assertFailsWith<IllegalArgumentException> {
            record(validations = listOf(duplicateValidation, duplicateValidation))
        }
    }

    @Test
    fun oversized_provenance_reference_is_rejected_before_codec() {
        assertFailsWith<IllegalArgumentException> {
            record(
                evidence = listOf(
                    evidence("n".repeat(OutcomeEvaluationRecord.MAX_REFERENCE_UTF8_BYTES + 1), "1")
                )
            )
        }
    }

    @Test
    fun record_contract_contains_no_authority_or_execution_permission_material() {
        val fieldNames = OutcomeEvaluationRecord::class.java.declaredFields
            .map { it.name.lowercase() }
        listOf("authority", "principal", "scope", "capability", "token", "permission").forEach {
            forbidden -> kotlin.test.assertTrue(fieldNames.none { forbidden in it })
        }
    }

    @Test
    fun codec_round_trips_action_identity_without_authority_state() {
        val original = record(
            actionId = ExecutionActionId("action-17"),
            status = OutcomeEvaluationStatus.PARTIAL
        )
        val encoded = OutcomeEvaluationPersistentCodec.encode(original)
        val decoded = assertIs<OutcomeEvaluationDecodeResult.Decoded>(
            OutcomeEvaluationPersistentCodec.decode(encoded)
        ).record

        assertEquals(original, decoded)
        assertEquals("action-17", decoded.authorizedActionId?.value)
        assertEquals(OutcomeEvaluationStatus.PARTIAL, decoded.status)
    }
    @Test
    fun codec_fails_closed_on_schema_version_identity_or_payload_corruption() {
        val original = record()
        val encoded = OutcomeEvaluationPersistentCodec.encode(original)

        assertIs<OutcomeEvaluationDecodeResult.Incompatible>(
            OutcomeEvaluationPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(99),
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )

        assertIs<OutcomeEvaluationDecodeResult.Corrupt>(
            OutcomeEvaluationPersistentCodec.decode(
                PersistentRecord(
                    pro.liliya.core.persistence.PersistentEntityId("wrong-id"),
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
        assertIs<OutcomeEvaluationDecodeResult.Corrupt>(
            OutcomeEvaluationPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    PersistentPayload(damaged),
                    encoded.createdAt
                )
            )
        )
    }

    @Test
    fun all_four_explicit_outcome_states_are_persistable() {
        OutcomeEvaluationStatus.entries.forEach { status ->
            val record = record(status = status)
            val decoded = assertIs<OutcomeEvaluationDecodeResult.Decoded>(
                OutcomeEvaluationPersistentCodec.decode(
                    OutcomeEvaluationPersistentCodec.encode(record)
                )
            )
            assertEquals(status, decoded.record.status)
        }
    }
    private fun record(
        version: Long = 1L,
        actionId: ExecutionActionId? = null,
        status: OutcomeEvaluationStatus = OutcomeEvaluationStatus.SUCCESS,
        evidence: List<RawEvidenceReference> = listOf(evidence("observation", "obs-1")),
        validations: List<OutcomeValidationReference> =
            listOf(OutcomeValidationReference("grounding:1"))
    ): OutcomeEvaluationRecord = OutcomeEvaluationRecord.create(
        version = OutcomeEvaluationVersion(version),
        plan = OutcomePlanReference(
            PlanningProposalId("plan-1"),
            PlanningGeneration(3)
        ),
        decision = OutcomeDecisionReference(
            DecisionId("decision-1"),
            DecisionGeneration(4)
        ),
        authorizedActionId = actionId,
        expected = OutcomeText("expected bounded result"),
        observed = OutcomeText("observed bounded result"),
        evidence = evidence,
        validationReferences = validations,
        status = status,
        evaluatorPolicyId = EvaluatorPolicyId("deterministic-outcome-v1"),
        evaluatorPolicyVersion = EvaluatorPolicyVersion(1),
        evaluatedAt = evaluatedAt
    )

    private fun evidence(namespace: String, id: String) =
        RawEvidenceReference(
            RawEvidenceNamespace(namespace),
            RawEvidenceId(id)
        )
}
