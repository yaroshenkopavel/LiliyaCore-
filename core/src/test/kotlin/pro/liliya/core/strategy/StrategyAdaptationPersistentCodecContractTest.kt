package pro.liliya.core.strategy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.reflection.ReflectionFindingKind
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion

class StrategyAdaptationPersistentCodecContractTest {
    private val t0 = Instant.parse("2026-09-28T14:30:00Z")

    @Test
    fun codec_round_trips_full_adopted_lifecycle() {
        val original = record()
        val encoded = StrategyAdaptationPersistentCodec.encode(original)
        val decoded = assertIs<StrategyAdaptationDecodeResult.Decoded>(
            StrategyAdaptationPersistentCodec.decode(encoded)
        ).record

        assertEquals(original, decoded)
        assertEquals(original.candidate.source, decoded.candidate.source)
        assertEquals(original.validation.constraintResults, decoded.validation.constraintResults)
        assertEquals(original.applicationIntent, decoded.applicationIntent)
    }

    @Test
    fun codec_fails_closed_on_schema_version_identity_payload_or_timestamp_mismatch() {
        val original = record()
        val encoded = StrategyAdaptationPersistentCodec.encode(original)

        assertIs<StrategyAdaptationDecodeResult.Incompatible>(
            StrategyAdaptationPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(99),
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )

        assertIs<StrategyAdaptationDecodeResult.Corrupt>(
            StrategyAdaptationPersistentCodec.decode(
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
        assertIs<StrategyAdaptationDecodeResult.Corrupt>(
            StrategyAdaptationPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    PersistentPayload(damaged),
                    encoded.createdAt
                )
            )
        )

        assertIs<StrategyAdaptationDecodeResult.Corrupt>(
            StrategyAdaptationPersistentCodec.decode(
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
    fun rejected_lifecycle_round_trips_without_application_intent() {
        val candidate = candidate()
        val ref = StrategyReference(candidate.id, candidate.version)
        val validation = validation(candidate, StrategyValidationDisposition.INVALID)
        val adoption = StrategyAdoptionRecord.create(
            candidate = ref,
            validation = StrategyValidationReference(validation.id, ref),
            disposition = StrategyAdoptionDisposition.REJECT,
            rationale = "compatibility mismatch",
            decidedAt = t0.plusSeconds(3)
        )
        val original = StrategyAdaptationRecord(candidate, validation, adoption, null)

        val decoded = assertIs<StrategyAdaptationDecodeResult.Decoded>(
            StrategyAdaptationPersistentCodec.decode(
                StrategyAdaptationPersistentCodec.encode(original)
            )
        ).record

        assertEquals(original, decoded)
        assertEquals(null, decoded.applicationIntent)
    }

    private fun record(): StrategyAdaptationRecord {
        val candidate = candidate()
        val ref = StrategyReference(candidate.id, candidate.version)
        val validation = validation(candidate, StrategyValidationDisposition.VALID)
        val adoption = StrategyAdoptionRecord.create(
            candidate = ref,
            validation = StrategyValidationReference(validation.id, ref),
            disposition = StrategyAdoptionDisposition.ADOPT,
            rationale = "validated bounded strategy",
            decidedAt = t0.plusSeconds(3)
        )
        val intent = StrategyApplicationIntent.create(
            candidate = ref,
            adoption = StrategyAdoptionReference(adoption.id, ref),
            target = candidate.target,
            scope = candidate.scope,
            createdAt = t0.plusSeconds(4)
        )
        return StrategyAdaptationRecord(candidate, validation, adoption, intent)
    }

    private fun candidate() = StrategyCandidate.create(
        version = StrategyVersion(1),
        source = StrategyReflectionSource(
            ReflectionResultId("reflection-result-" + "a".repeat(64)),
            ReflectionVersion(1),
            0,
            ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
        ),
        target = StrategyTarget.RETRIEVAL,
        scope = StrategyScope("semantic.retrieval.ranking"),
        proposal = StrategyText("prefer validated reciprocal retrieval"),
        compatibility = listOf(
            StrategyCompatibilityConstraint("abi", "arm64-v8a"),
            StrategyCompatibilityConstraint("runtime", "offline")
        ),
        rollbackTo = null,
        createdAt = t0.plusSeconds(1),
        expiresAt = t0.plusSeconds(300)
    )

    private fun validation(
        candidate: StrategyCandidate,
        disposition: StrategyValidationDisposition
    ): StrategyValidationRecord {
        val resultDisposition =
            if (disposition == StrategyValidationDisposition.VALID) {
                StrategyConstraintDisposition.SATISFIED
            } else {
                StrategyConstraintDisposition.UNSATISFIED
            }
        return StrategyValidationRecord.create(
            candidate = StrategyReference(candidate.id, candidate.version),
            disposition = disposition,
            constraintResults = candidate.compatibility.map {
                StrategyConstraintResult(it, resultDisposition, "deterministic result")
            },
            policyId = StrategyPolicyId("strategy-validation-v1"),
            policyVersion = StrategyPolicyVersion(1),
            validatedAt = t0.plusSeconds(2)
        )
    }
}
