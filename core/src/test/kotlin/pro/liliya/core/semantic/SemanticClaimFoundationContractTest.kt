package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord

class SemanticClaimFoundationContractTest {
    private val identity = SemanticClaimIdentity(
        subject = SemanticEntityReference("person", "user"),
        predicate = "preferred_language"
    )

    private val provenance = SemanticClaimProvenance(
        episodes = listOf(EpisodeId("episode-language")),
        rawEvidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace("conversation"),
                RawEvidenceId("session-1:chunk-2")
            )
        ),
        extraction = SemanticClaimExtractionProvenance(
            extractorId = "semantic-extractor",
            extractorVersion = "1.0.0",
            extractedAt = Instant.parse("2026-09-24T20:00:10Z")
        ),
        evidenceStrength = SemanticEvidenceStrength.MEDIUM
    )

    private fun record(
        version: Long = 1L,
        value: SemanticClaimObject = SemanticClaimObject.Text("Russian")
    ): SemanticClaimRecord = SemanticClaimRecord(
        id = SemanticClaimIds.forClaim(identity, value),
        version = SemanticClaimVersion(version),
        identity = identity,
        objectValue = value,
        temporal = SemanticClaimTemporalState(
            observedAt = Instant.parse("2026-09-24T20:00:00Z"),
            validFrom = Instant.parse("2026-09-01T00:00:00Z"),
            validUntil = null,
            supersededAt = null
        ),
        provenance = provenance
    )

    @Test
    fun claim_identity_includes_object_while_conflict_group_is_subject_predicate_only() {
        val russian = record(value = SemanticClaimObject.Text("Russian"))
        val ukrainian = record(value = SemanticClaimObject.Text("Ukrainian"))

        assertNotEquals(russian.id, ukrainian.id)
        assertEquals(
            SemanticClaimIds.forConflictGroup(russian.identity),
            SemanticClaimIds.forConflictGroup(ukrainian.identity)
        )
    }

    @Test
    fun different_predicate_produces_different_conflict_group() {
        val one = SemanticClaimIds.forConflictGroup(identity)
        val two = SemanticClaimIds.forConflictGroup(
            identity.copy(predicate = "preferred_timezone")
        )
        assertNotEquals(one, two)
    }

    @Test
    fun same_claim_object_keeps_identity_across_versions() {
        val one = record(version = 1)
        val two = record(version = 2)
        assertEquals(one.id, two.id)
        assertEquals(
            SemanticClaimIds.forConflictGroup(one.identity),
            SemanticClaimIds.forConflictGroup(two.identity)
        )
    }

    @Test
    fun codec_round_trips_bitemporal_state_and_provenance_exactly() {
        val original = record()
        val encoded = SemanticClaimPersistentCodec.encode(original)
        val decoded = assertIs<SemanticClaimPersistentDecodeResult.Decoded>(
            SemanticClaimPersistentCodec.decode(encoded)
        )
        assertEquals(original, decoded.record)
    }

    @Test
    fun separate_versions_have_separate_persistent_records_for_same_claim() {
        val one = SemanticClaimPersistentCodec.encode(record(version = 1))
        val two = SemanticClaimPersistentCodec.encode(record(version = 2))

        assertNotEquals(one.id, two.id)
        val decodedOne = assertIs<SemanticClaimPersistentDecodeResult.Decoded>(
            SemanticClaimPersistentCodec.decode(one)
        ).record
        val decodedTwo = assertIs<SemanticClaimPersistentDecodeResult.Decoded>(
            SemanticClaimPersistentCodec.decode(two)
        ).record
        assertEquals(decodedOne.id, decodedTwo.id)
        assertEquals(1L, decodedOne.version.value)
        assertEquals(2L, decodedTwo.version.value)
    }

    @Test
    fun truncated_payload_fails_closed() {
        val encoded = SemanticClaimPersistentCodec.encode(record())
        val bytes = encoded.payload.copyBytes()
        val corrupted = PersistentRecord(
            id = encoded.id,
            schemaId = encoded.schemaId,
            schemaVersion = encoded.schemaVersion,
            payload = PersistentPayload(bytes.copyOf(bytes.size - 1)),
            createdAt = encoded.createdAt
        )
        assertIs<SemanticClaimPersistentDecodeResult.Corrupt>(
            SemanticClaimPersistentCodec.decode(corrupted)
        )
    }

    @Test
    fun mismatched_deterministic_claim_id_is_rejected_before_persistence() {
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimPersistentCodec.encode(
                record().copy(id = SemanticClaimId("claim-wrong"))
            )
        }
    }

    @Test
    fun provenance_is_mandatory() {
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimProvenance(
                episodes = emptyList(),
                rawEvidence = emptyList(),
                extraction = provenance.extraction
            )
        }
    }

    @Test
    fun invalid_validity_interval_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimTemporalState(
                observedAt = Instant.parse("2026-09-24T20:00:00Z"),
                validFrom = Instant.parse("2026-09-10T00:00:00Z"),
                validUntil = Instant.parse("2026-09-01T00:00:00Z")
            )
        }
    }

    @Test
    fun supersession_cannot_precede_observation() {
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimTemporalState(
                observedAt = Instant.parse("2026-09-24T20:00:00Z"),
                supersededAt = Instant.parse("2026-09-24T19:59:59Z")
            )
        }
    }

    @Test
    fun numeric_object_requires_single_canonical_decimal_representation() {
        assertEquals("1", SemanticClaimObject.Number("1").canonical)
        assertFailsWith<IllegalArgumentException> { SemanticClaimObject.Number("1.0") }
        assertFailsWith<IllegalArgumentException> { SemanticClaimObject.Number("01") }
    }
}
