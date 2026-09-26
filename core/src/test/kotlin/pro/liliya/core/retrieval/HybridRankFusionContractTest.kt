package pro.liliya.core.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HybridRankFusionContractTest {
    private val policy = HybridRankFusionPolicy(maxOutputCandidates = 8)

    @Test
    fun channel_order_does_not_change_fused_result() {
        val lexical = ranked("lexical", "a", "b", "c")
        val vector = ranked("vector", "b", "a", "d")

        val first = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(lexical, vector),
                policy
            )
        )
        val second = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(vector, lexical),
                policy
            )
        )

        assertEquals(first.candidates, second.candidates)
        assertEquals(first.audit, second.audit)
        assertEquals(listOf("a", "b", "c", "d"), first.candidates.map { it.id.value })
    }

    @Test
    fun same_candidate_from_multiple_channels_accumulates_rank_contributions() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("exact", "claim-1", "claim-2"),
                    ranked("episodic", "claim-2", "claim-1")
                ),
                policy
            )
        )

        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { it.contributions.size == 2 })
        assertTrue(result.candidates.all { it.requiresCanonicalRevalidation })
        assertTrue(result.audit.advisoryOnly)
    }

    @Test
    fun exact_score_ties_use_stable_utf8_candidate_identity() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("one", "beta", "alpha"),
                    ranked("two", "alpha", "beta")
                ),
                policy
            )
        )
        assertEquals(listOf("alpha", "beta"), result.candidates.map { it.id.value })
    }

    @Test
    fun optional_channel_failure_is_audited_and_does_not_block_available_channel() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("exact", "a"),
                    RetrievalChannelResult.Failed(
                        RetrievalChannelId("lexical"),
                        RetrievalChannelRequirement.OPTIONAL,
                        "index unavailable"
                    )
                ),
                policy
            )
        )

        assertEquals(listOf("a"), result.candidates.map { it.id.value })
        val failed = result.audit.channels.single { it.channelId.value == "lexical" }
        assertEquals(false, failed.used)
        assertTrue(failed.status.startsWith("FAILED:"))
    }

    @Test
    fun required_channel_failure_requires_fallback_without_partial_fusion() {
        val result = assertIs<HybridRankFusionResult.FallbackRequired>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("exact", "a"),
                    RetrievalChannelResult.Unavailable(
                        RetrievalChannelId("canonical"),
                        RetrievalChannelRequirement.REQUIRED,
                        "source stale"
                    )
                ),
                policy
            )
        )

        assertEquals(0, result.audit.outputCount)
        assertEquals(1, result.audit.distinctInputCandidates)
        assertTrue(result.reason.contains("canonical"))
    }

    @Test
    fun output_budget_truncates_only_current_candidate_set() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(ranked("one", "a", "b", "c")),
                HybridRankFusionPolicy(maxOutputCandidates = 2)
            )
        )

        assertEquals(listOf("a", "b"), result.candidates.map { it.id.value })
        assertEquals(3, result.audit.distinctInputCandidates)
        assertEquals(2, result.audit.outputCount)
    }

    @Test
    fun duplicate_channel_ids_are_rejected() {
        assertIs<HybridRankFusionResult.Rejected>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("same", "a"),
                    ranked("same", "b")
                ),
                policy
            )
        )
    }

    @Test
    fun per_channel_budget_is_enforced_at_input_boundary() {
        val candidates = (0..HybridRankFusionPolicy.MAX_PER_CHANNEL_CANDIDATES)
            .map { RankedRetrievalCandidate(RetrievalCandidateId("id-" + it)) }
        try {
            RetrievalChannelResult.Ranked(
                RetrievalChannelId("too-many"),
                RetrievalChannelRequirement.OPTIONAL,
                candidates
            )
            throw AssertionError("candidate budget must be enforced")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun max_channel_budget_is_enforced_before_fusion() {
        val channels = (0..HybridRankFusionPolicy.MAX_CHANNELS).map { ordinal ->
            ranked("channel-" + ordinal, "id-" + ordinal)
        }
        assertIs<HybridRankFusionResult.Rejected>(
            DeterministicReciprocalRankFusion.fuse(channels, policy)
        )
    }

    @Test
    fun reciprocal_rank_formula_uses_explicit_policy_k() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(ranked("exact", "a", "b")),
                HybridRankFusionPolicy(
                    reciprocalRankConstant = 60,
                    maxOutputCandidates = 2
                )
            )
        )
        assertEquals(1.0 / 61.0, result.candidates[0].fusedScore)
        assertEquals(1.0 / 62.0, result.candidates[1].fusedScore)
        assertEquals(60, result.audit.reciprocalRankConstant)
        assertEquals(HybridRankFusionPolicy.CURRENT_VERSION, result.audit.policyVersion)
    }

    @Test
    fun required_failure_result_is_channel_order_invariant() {
        val requiredFailure = RetrievalChannelResult.Failed(
            RetrievalChannelId("canonical"),
            RetrievalChannelRequirement.REQUIRED,
            "source corrupt"
        )
        val available = ranked("semantic", "a", "b")

        val first = assertIs<HybridRankFusionResult.FallbackRequired>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(requiredFailure, available),
                policy
            )
        )
        val second = assertIs<HybridRankFusionResult.FallbackRequired>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(available, requiredFailure),
                policy
            )
        )

        assertEquals(first.reason, second.reason)
        assertEquals(first.audit, second.audit)
    }

    @Test
    fun raw_channel_scores_cannot_influence_rrf_because_api_accepts_rank_only() {
        val result = assertIs<HybridRankFusionResult.Fused>(
            DeterministicReciprocalRankFusion.fuse(
                listOf(
                    ranked("semantic", "a", "b"),
                    ranked("lexical", "b", "a")
                ),
                policy
            )
        )
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { it.fusedScore > 0.0 })
    }

    private fun ranked(
        channel: String,
        vararg ids: String,
        requirement: RetrievalChannelRequirement =
            RetrievalChannelRequirement.OPTIONAL
    ) = RetrievalChannelResult.Ranked(
        channelId = RetrievalChannelId(channel),
        requirement = requirement,
        candidates = ids.map {
            RankedRetrievalCandidate(RetrievalCandidateId(it))
        }
    )
}
