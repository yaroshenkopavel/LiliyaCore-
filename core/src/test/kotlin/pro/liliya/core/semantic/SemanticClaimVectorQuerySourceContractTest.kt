package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.retrieval.RetrievalChannelRequirement
import pro.liliya.core.retrieval.RetrievalChannelResult

class SemanticClaimVectorQuerySourceContractTest {
    private val providerIdentity = SemanticClaimVectorProviderIdentity(
        profileId = "semantic-e5-small-v1",
        profileGeneration = 3,
        indexGeneration = 7
    )

    @Test
    fun vector_query_exact_revalidates_and_preserves_provider_rank_for_rrf() {
        val first = claim("a", 1)
        val second = claim("b", 2)
        val reader = FakeReader(listOf(first, second))
        var requested = 0
        val source = SemanticClaimVectorQuerySource(
            reader,
            SemanticClaimVectorDiscoveryPort { _, maxCandidates ->
                requested = maxCandidates
                SemanticClaimVectorProviderResult.Ranked(
                    providerIdentity,
                    listOf(
                        candidate(second, 0.91),
                        candidate(first, 0.72)
                    )
                )
            }
        )

        val result = assertIs<SemanticClaimVectorQueryResult.Ranked>(
            source.query("preferred language")
        )
        assertEquals(128, requested)
        assertEquals(
            listOf(reference(second), reference(first)),
            result.candidates.map { it.reference }
        )
        assertEquals(
            listOf(
                "semantic-claim:" + second.id.value + ":v" + second.version.value,
                "semantic-claim:" + first.id.value + ":v" + first.version.value
            ),
            result.candidates.map { it.candidateId.value }
        )
        assertEquals(providerIdentity, result.audit.providerIdentity)
        assertEquals(2, result.audit.providerCandidates)
        assertEquals(2, result.audit.returnedCandidates)
        assertEquals(0, result.audit.staleCandidates)
        assertTrue(result.audit.advisoryOnly)

        val channel = assertIs<RetrievalChannelResult.Ranked>(
            result.toRetrievalChannelResult(RetrievalChannelRequirement.OPTIONAL)
        )
        assertEquals("semantic-vector-v1", channel.channelId.value)
        assertEquals(
            result.candidates.map { it.candidateId },
            channel.candidates.map { it.id }
        )
    }

    @Test
    fun missing_canonical_candidate_fails_safe_without_partial_ranked_success() {
        val first = claim("a", 1)
        val missing = claim("missing", 2)
        val reader = FakeReader(listOf(first))
        val source = SemanticClaimVectorQuerySource(
            reader,
            rankedProvider(
                candidate(first, 0.9),
                candidate(missing, 0.8)
            )
        )

        val result = assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            source.query("query")
        )
        assertTrue(result.reason.contains("missing"))
        assertEquals(2, reader.readCalls)
    }

    @Test
    fun canonical_id_version_mismatch_fails_safe() {
        val expected = claim("expected", 1)
        val wrong = claim("wrong", 1)
        val reader = FakeReader(emptyList())
        reader.forcedRead = SemanticClaimReadResult.Found(wrong)
        val source = SemanticClaimVectorQuerySource(
            reader,
            rankedProvider(candidate(expected, 0.9))
        )

        val result = assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            source.query("query")
        )
        assertTrue(result.reason.contains("id/version"))
    }

    @Test
    fun provider_over_budget_or_duplicates_are_rejected_as_untrusted() {
        val one = claim("one", 1)
        val reader = FakeReader(listOf(one))
        val overBudget = SemanticClaimVectorQuerySource(
            reader,
            SemanticClaimVectorDiscoveryPort { _, _ ->
                SemanticClaimVectorProviderResult.Ranked(
                    providerIdentity,
                    List(129) { ordinal ->
                        val record = claim("x-" + ordinal, ordinal + 1L)
                        candidate(record, 0.5)
                    }
                )
            }
        )
        assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            overBudget.query("query")
        )

        val duplicate = SemanticClaimVectorQuerySource(
            reader,
            rankedProvider(candidate(one, 0.9), candidate(one, 0.8))
        )
        val duplicated = assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            duplicate.query("query")
        )
        assertTrue(duplicated.reason.contains("duplicate"))
    }

    @Test
    fun source_drift_and_provider_unavailability_are_explicit() {
        val one = claim("one", 1)
        val drifting = FakeReader(listOf(one)).apply { driftOnSecondCheckpoint = true }
        val source = SemanticClaimVectorQuerySource(
            drifting,
            rankedProvider(candidate(one, 0.9))
        )
        val drift = assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            source.query("query")
        )
        assertTrue(drift.reason.contains("source changed"))

        val unavailable = SemanticClaimVectorQuerySource(
            FakeReader(listOf(one)),
            SemanticClaimVectorDiscoveryPort { _, _ ->
                SemanticClaimVectorProviderResult.Unavailable("profile unavailable")
            }
        )
        val unavailableResult = assertIs<SemanticClaimVectorQueryResult.FallbackRequired>(
            unavailable.query("query")
        )
        assertEquals("profile unavailable", unavailableResult.reason)
    }

    @Test
    fun blank_query_is_rejected_before_provider_or_canonical_work() {
        val reader = FakeReader(emptyList())
        var providerCalls = 0
        val source = SemanticClaimVectorQuerySource(
            reader,
            SemanticClaimVectorDiscoveryPort { _, _ ->
                providerCalls += 1
                SemanticClaimVectorProviderResult.Unavailable("unused")
            }
        )

        assertIs<SemanticClaimVectorQueryResult.Rejected>(source.query("   "))
        assertEquals(0, providerCalls)
        assertEquals(0, reader.checkpointCalls)
    }

    private fun rankedProvider(
        vararg candidates: SemanticClaimVectorProviderCandidate
    ): SemanticClaimVectorDiscoveryPort =
        SemanticClaimVectorDiscoveryPort { _, _ ->
            SemanticClaimVectorProviderResult.Ranked(
                providerIdentity,
                candidates.toList()
            )
        }

    private fun candidate(
        record: SemanticClaimRecord,
        similarity: Double
    ): SemanticClaimVectorProviderCandidate =
        SemanticClaimVectorProviderCandidate(reference(record), similarity)

    private fun reference(record: SemanticClaimRecord) =
        SemanticClaimVersionReference(record.id, record.version)

    private fun claim(suffix: String, version: Long): SemanticClaimRecord {
        val observed = Instant.parse("2026-09-27T00:00:00Z")
        return SemanticClaimRecord(
            id = SemanticClaimId("claim-" + suffix),
            version = SemanticClaimVersion(version),
            identity = SemanticClaimIdentity(
                SemanticEntityReference("user", "self"),
                "preference-" + suffix
            ),
            objectValue = SemanticClaimObject.Text("value-" + suffix),
            temporal = SemanticClaimTemporalState(observedAt = observed),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-" + suffix)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "test",
                    extractorVersion = "1",
                    extractedAt = observed
                )
            )
        )
    }

    private class FakeReader(
        records: List<SemanticClaimRecord>
    ) : SemanticClaimVectorCanonicalReader {
        private val records = records.associateBy {
            SemanticClaimVersionReference(it.id, it.version)
        }
        var forcedRead: SemanticClaimReadResult? = null
        var readCalls: Int = 0
        var checkpointCalls: Int = 0
        var driftOnSecondCheckpoint: Boolean = false

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return SemanticClaimSourceCheckpoint(
                revision = if (driftOnSecondCheckpoint && checkpointCalls > 1) 2 else 1,
                highWatermark = 1,
                entryCount = records.size.toLong()
            )
        }

        override fun readExact(
            reference: SemanticClaimVersionReference
        ): SemanticClaimReadResult {
            readCalls += 1
            forcedRead?.let { return it }
            val record = records[reference] ?: return SemanticClaimReadResult.Missing
            return SemanticClaimReadResult.Found(record)
        }
    }
}