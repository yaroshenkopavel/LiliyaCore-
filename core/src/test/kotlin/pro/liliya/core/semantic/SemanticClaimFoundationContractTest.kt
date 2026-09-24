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
        id = SemanticClaimIds.forIdentity(identity),
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
    fun deterministic_identity_depends_on_subject_and_predicate_not_object_value() {
        val first = SemanticClaimIds.forIdentity(identity)
        val sameIdentityDifferentObject = record(
            version = 2,
            value = SemanticClaimObject.Text("Ukrainian")
        )

        assertEquals(first, sameIdentityDifferentObject.id)
        assertEquals(identity, sameIdentityDifferentObject.identity)
    }

    @Test
    fun different_predicate_produces_different_claim_identity() {
        val one = SemanticClaimIds.forIdentity(identity)
        val two = SemanticClaimIds.forIdentity(
            identity.copy(predicate = "preferred_timezone")
        )
        assertNotEquals(one, two)
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
    fun separate_versions_have_separate_persistent_records_but_same_claim_id() {
        val one = SemanticClaimPersistentCodec.encode(record(version = 1))
        val two = SemanticClaimPersistentCodec.encode(
            record(
                version = 2,
                value = SemanticClaimObject.Text("Ukrainian")
            )
        )

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
}
