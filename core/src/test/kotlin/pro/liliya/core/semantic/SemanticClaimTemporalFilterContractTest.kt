package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.retrieval.HybridFusedCandidate
import pro.liliya.core.retrieval.HybridRankContribution
import pro.liliya.core.retrieval.RetrievalCandidateId
import pro.liliya.core.retrieval.RetrievalChannelId

class SemanticClaimTemporalFilterContractTest {
    private val world = Instant.parse("2026-09-27T12:00:00Z")
    private val knowledge = Instant.parse("2026-09-27T13:00:00Z")

    @Test
    fun stable_identity_round_trips_exact_claim_reference() {
        val reference = SemanticClaimVersionReference(
            SemanticClaimId("claim-abc"),
            SemanticClaimVersion(7)
        )
        val encoded = SemanticClaimRetrievalCandidateIdentity.encode(reference)
        assertEquals("semantic-claim:claim-abc:v7", encoded.value)
        assertEquals(reference, SemanticClaimRetrievalCandidateIdentity.decode(encoded))
    }

    @Test
    fun eligible_candidates_preserve_fused_rank_order_without_recency_reranking() {
        val older = claim(
            id = "older",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z"),
            validFrom = Instant.parse("2026-09-01T00:00:00Z")
        )
        val newer = claim(
            id = "newer",
            version = 1,
            observedAt = Instant.parse("2026-09-26T00:00:00Z"),
            validFrom = Instant.parse("2026-09-25T00:00:00Z")
        )
        val reader = FakeReader(listOf(older, newer))
        val input = listOf(fused(older, 0.9), fused(newer, 0.8))

        val result = assertIs<SemanticClaimTemporalFilterResult.Eligible>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(input, world, knowledge)
        )

        assertEquals(input, result.candidates)
        assertEquals(2, result.audit.eligibleCount)
        assertEquals(0, result.audit.knowledgeInvisibleCount)
        assertEquals(0, result.audit.worldInvalidCount)
    }

    @Test
    fun knowledge_invisible_and_world_invalid_candidates_are_filtered_separately() {
        val futureKnowledge = claim(
            id = "future-knowledge",
            version = 1,
            observedAt = Instant.parse("2026-09-28T00:00:00Z")
        )
        val superseded = claim(
            id = "superseded",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z"),
            supersededAt = Instant.parse("2026-09-27T12:30:00Z")
        )
        val expired = claim(
            id = "expired",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z"),
            validUntil = Instant.parse("2026-09-27T11:59:59Z")
        )
        val eligible = claim(
            id = "eligible",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z")
        )
        val reader = FakeReader(listOf(futureKnowledge, superseded, expired, eligible))

        val result = assertIs<SemanticClaimTemporalFilterResult.Eligible>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(
                listOf(
                    fused(futureKnowledge, 0.9),
                    fused(superseded, 0.8),
                    fused(expired, 0.7),
                    fused(eligible, 0.6)
                ),
                world,
                knowledge
            )
        )

        assertEquals(listOf(fused(eligible, 0.6)), result.candidates)
        assertEquals(4, result.audit.examinedCount)
        assertEquals(2, result.audit.knowledgeInvisibleCount)
        assertEquals(1, result.audit.worldInvalidCount)
        assertEquals(1, result.audit.eligibleCount)
    }

    @Test
    fun temporal_boundaries_are_deterministic_and_half_open() {
        val atBoundary = claim(
            id = "at-boundary",
            version = 1,
            observedAt = knowledge,
            validFrom = world
        )
        val supersededAtBoundary = claim(
            id = "superseded-boundary",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z"),
            supersededAt = knowledge
        )
        val validUntilBoundary = claim(
            id = "until-boundary",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z"),
            validUntil = world
        )
        val reader = FakeReader(listOf(atBoundary, supersededAtBoundary, validUntilBoundary))

        val result = assertIs<SemanticClaimTemporalFilterResult.Eligible>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(
                listOf(
                    fused(atBoundary, 0.9),
                    fused(supersededAtBoundary, 0.8),
                    fused(validUntilBoundary, 0.7)
                ),
                world,
                knowledge
            )
        )

        assertEquals(listOf(fused(atBoundary, 0.9)), result.candidates)
        assertEquals(SemanticClaimTemporalFilterStatus.ELIGIBLE, result.audit.status)
        assertEquals(1, result.audit.knowledgeInvisibleCount)
        assertEquals(1, result.audit.worldInvalidCount)
    }

    @Test
    fun malformed_or_non_semantic_candidate_id_is_rejected_before_canonical_read() {
        val reader = FakeReader(emptyList())
        val candidate = HybridFusedCandidate(
            id = RetrievalCandidateId("other:123"),
            fusedScore = 0.5,
            contributions = listOf(contribution())
        )

        val result = assertIs<SemanticClaimTemporalFilterResult.Rejected>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(listOf(candidate), world, knowledge)
        )

        assertTrue(result.reason.contains("valid Semantic Claim identity"))
        assertEquals(0, reader.readCalls)
    }

    @Test
    fun missing_canonical_claim_fails_safe_without_partial_success() {
        val first = claim(
            id = "first",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z")
        )
        val missing = claim(
            id = "missing",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z")
        )
        val reader = FakeReader(listOf(first))

        val result = assertIs<SemanticClaimTemporalFilterResult.FallbackRequired>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(
                listOf(fused(first, 0.9), fused(missing, 0.8)),
                world,
                knowledge
            )
        )

        assertTrue(result.reason.contains("missing"))
        assertEquals(2, reader.readCalls)
    }

    @Test
    fun source_drift_fails_safe_after_bounded_exact_reads() {
        val record = claim(
            id = "drift",
            version = 1,
            observedAt = Instant.parse("2026-09-20T00:00:00Z")
        )
        val reader = FakeReader(listOf(record)).apply {
            driftOnSecondCheckpoint = true
        }

        val result = assertIs<SemanticClaimTemporalFilterResult.FallbackRequired>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy()
            ).filter(listOf(fused(record, 0.9)), world, knowledge)
        )

        assertTrue(result.reason.contains("source changed"))
        assertEquals(1, reader.readCalls)
    }

    @Test
    fun input_budget_is_rejected_before_source_or_reads() {
        val reader = FakeReader(emptyList())
        val candidate = HybridFusedCandidate(
            id = RetrievalCandidateId("semantic-claim:claim-x:v1"),
            fusedScore = 0.5,
            contributions = listOf(contribution())
        )
        val result = assertIs<SemanticClaimTemporalFilterResult.Rejected>(
            SemanticClaimTemporalFilter(
                reader,
                SemanticClaimTemporalFilterPolicy(maxInputCandidates = 1)
            ).filter(listOf(candidate, candidate.copy(id = RetrievalCandidateId("semantic-claim:claim-y:v1"))), world, knowledge)
        )
        assertTrue(result.reason.contains("budget"))
        assertEquals(0, reader.checkpointCalls)
        assertEquals(0, reader.readCalls)
    }

    private fun fused(record: SemanticClaimRecord, score: Double) =
        HybridFusedCandidate(
            id = SemanticClaimRetrievalCandidateIdentity.encode(
                SemanticClaimVersionReference(record.id, record.version)
            ),
            fusedScore = score,
            contributions = listOf(contribution())
        )

    private fun contribution() =
        HybridRankContribution(
            channelId = RetrievalChannelId("test"),
            rank = 1,
            reciprocalContribution = 0.01
        )

    private fun claim(
        id: String,
        version: Long,
        observedAt: Instant,
        validFrom: Instant? = null,
        validUntil: Instant? = null,
        supersededAt: Instant? = null
    ): SemanticClaimRecord =
        SemanticClaimRecord(
            id = SemanticClaimId("claim-" + id),
            version = SemanticClaimVersion(version),
            identity = SemanticClaimIdentity(
                SemanticEntityReference("user", "self"),
                "predicate-" + id
            ),
            objectValue = SemanticClaimObject.Text("value-" + id),
            temporal = SemanticClaimTemporalState(
                observedAt = observedAt,
                validFrom = validFrom,
                validUntil = validUntil,
                supersededAt = supersededAt
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-" + id)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "test",
                    extractorVersion = "1",
                    extractedAt = observedAt
                )
            )
        )

    private class FakeReader(
        records: List<SemanticClaimRecord>
    ) : SemanticClaimTemporalCanonicalReader {
        private val records = records.associateBy {
            SemanticClaimVersionReference(it.id, it.version)
        }
        var readCalls = 0
        var checkpointCalls = 0
        var driftOnSecondCheckpoint = false

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return SemanticClaimSourceCheckpoint(
                revision = if (driftOnSecondCheckpoint && checkpointCalls > 1) 2 else 1,
                highWatermark = records.size.toLong(),
                entryCount = records.size.toLong()
            )
        }

        override fun readExact(
            reference: SemanticClaimVersionReference
        ): SemanticClaimReadResult {
            readCalls += 1
            val record = records[reference] ?: return SemanticClaimReadResult.Missing
            return SemanticClaimReadResult.Found(record)
        }
    }
}