package pro.liliya.core.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AssociativeRetrievalDynamicsContractTest {
    @Test
    fun coactivation_creates_deterministic_candidate_without_strengthening() {
        val ids = listOf(
            RetrievalCandidateId("c"),
            RetrievalCandidateId("a"),
            RetrievalCandidateId("b"),
            RetrievalCandidateId("a")
        )

        val first = AssociativeRetrievalDynamics.candidateFromCoactivation(ids)
        val second = AssociativeRetrievalDynamics.candidateFromCoactivation(ids.reversed())

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(first, second)
        assertEquals(listOf("a", "b", "c"), first.participants.map { it.value })

        val state = AssociativeRetrievalDynamics.initialState(first)
        assertEquals(0.0, state.weight)
        assertEquals(0L, state.validatedSuccessCount)
        assertEquals(0L, state.lastValidationSequence)
        assertEquals(null, state.lastValidationId)
        assertEquals(null, state.lastValidationVersion)
        assertEquals(null, state.lastGroundedOutcomeId)
    }

    @Test
    fun failed_validation_does_not_strengthen_association() {
        val candidate = candidate("a", "b")
        val current = AssociativeRetrievalDynamics.initialState(candidate)

        val result = AssociativeRetrievalDynamics.applyValidation(
            current,
            validation(candidate, successful = false)
        )

        val unchanged = assertIs<RetrievalAssociationUpdateResult.Unchanged>(result)
        assertEquals(current.weight, unchanged.state.weight)
        assertEquals(current.validatedSuccessCount, unchanged.state.validatedSuccessCount)
        assertEquals(1L, unchanged.state.lastValidationSequence)
    }

    @Test
    fun successful_validation_strengthens_and_saturates_at_policy_maximum() {
        val policy = AssociativeRetrievalPolicy(
            strengthenDelta = 0.4,
            maxWeight = 0.75
        )
        val candidate = candidate("a", "b", policy = policy)
        var state = AssociativeRetrievalDynamics.initialState(candidate)

        repeat(3) { index ->
            val result = AssociativeRetrievalDynamics.applyValidation(
                state,
                validation(
                    candidate,
                    successful = true,
                    suffix = index.toString(),
                    sequence = (index + 1).toLong()
                ),
                policy
            )
            state = assertIs<RetrievalAssociationUpdateResult.Updated>(result).state
        }

        assertEquals(0.75, state.weight)
        assertEquals(3L, state.validatedSuccessCount)
        assertEquals(3L, state.lastValidationSequence)
        assertEquals("validation-2", state.lastValidationId)
        assertEquals(1, state.lastValidationVersion)
        assertEquals("outcome-2", state.lastGroundedOutcomeId)
    }

    @Test
    fun duplicate_or_out_of_order_validation_never_strengthens_twice() {
        val candidate = candidate("a", "b")
        val initial = AssociativeRetrievalDynamics.initialState(candidate)

        val first = assertIs<RetrievalAssociationUpdateResult.Updated>(
            AssociativeRetrievalDynamics.applyValidation(
                initial,
                validation(candidate, successful = true, sequence = 7L)
            )
        ).state

        val duplicate = AssociativeRetrievalDynamics.applyValidation(
            first,
            validation(candidate, successful = true, sequence = 7L)
        )
        val older = AssociativeRetrievalDynamics.applyValidation(
            first,
            validation(candidate, successful = true, sequence = 6L)
        )

        assertEquals(
            first,
            assertIs<RetrievalAssociationUpdateResult.Unchanged>(duplicate).state
        )
        assertEquals(
            first,
            assertIs<RetrievalAssociationUpdateResult.Unchanged>(older).state
        )
    }

    @Test
    fun failed_validation_consumes_sequence_without_strengthening() {
        val candidate = candidate("a", "b")
        val initial = AssociativeRetrievalDynamics.initialState(candidate)

        val failed = assertIs<RetrievalAssociationUpdateResult.Unchanged>(
            AssociativeRetrievalDynamics.applyValidation(
                initial,
                validation(candidate, successful = false, sequence = 3L)
            )
        ).state

        assertEquals(0.0, failed.weight)
        assertEquals(0L, failed.validatedSuccessCount)
        assertEquals(3L, failed.lastValidationSequence)
        assertEquals("validation-0", failed.lastValidationId)
        assertEquals(1, failed.lastValidationVersion)
        assertEquals("outcome-0", failed.lastGroundedOutcomeId)

        val replayAsSuccess = AssociativeRetrievalDynamics.applyValidation(
            failed,
            validation(candidate, successful = true, sequence = 3L)
        )
        assertEquals(
            failed,
            assertIs<RetrievalAssociationUpdateResult.Unchanged>(replayAsSuccess).state
        )
    }

    @Test
    fun bounded_rebuild_is_deterministic_and_preserves_validation_trace() {
        val candidate = candidate("a", "b")
        val validations = listOf(
            validation(candidate, successful = true, suffix = "1", sequence = 1L),
            validation(candidate, successful = false, suffix = "2", sequence = 2L),
            validation(candidate, successful = true, suffix = "3", sequence = 3L)
        )

        val first = AssociativeRetrievalDynamics.rebuild(candidate, validations)
        val second = AssociativeRetrievalDynamics.rebuild(candidate, validations)

        val firstState = assertIs<RetrievalAssociationRebuildResult.Rebuilt>(first).state
        val secondState = assertIs<RetrievalAssociationRebuildResult.Rebuilt>(second).state
        assertEquals(firstState, secondState)
        assertEquals(0.10, firstState.weight)
        assertEquals(2L, firstState.validatedSuccessCount)
        assertEquals(3L, firstState.lastValidationSequence)
        assertEquals("validation-3", firstState.lastValidationId)
        assertEquals("outcome-3", firstState.lastGroundedOutcomeId)
    }

    @Test
    fun rebuild_rejects_out_of_order_or_unbounded_validation_history() {
        val candidate = candidate("a", "b")
        val outOfOrder = listOf(
            validation(candidate, successful = true, sequence = 2L),
            validation(candidate, successful = true, sequence = 1L)
        )
        val tooMany = (1..(AssociativeRetrievalDynamics.MAX_VALIDATIONS_PER_REBUILD + 1))
            .map { sequence ->
                validation(
                    candidate,
                    successful = true,
                    suffix = sequence.toString(),
                    sequence = sequence.toLong()
                )
            }

        assertIs<RetrievalAssociationRebuildResult.Rejected>(
            AssociativeRetrievalDynamics.rebuild(candidate, outOfOrder)
        )
        assertIs<RetrievalAssociationRebuildResult.Rejected>(
            AssociativeRetrievalDynamics.rebuild(candidate, tooMany)
        )
    }

    @Test
    fun identity_mismatch_is_rejected_fail_closed() {
        val candidate = candidate("a", "b")
        val current = AssociativeRetrievalDynamics.initialState(candidate)
        val other = candidate("a", "c")

        val result = AssociativeRetrievalDynamics.applyValidation(
            current,
            validation(other, successful = true)
        )

        assertIs<RetrievalAssociationUpdateResult.Rejected>(result)
    }

    @Test
    fun decay_is_bounded_and_never_goes_negative() {
        val policy = AssociativeRetrievalPolicy(decayDelta = 0.2)
        val candidate = candidate("a", "b", policy = policy)
        val current = AssociativeRetrievalDynamics.initialState(candidate).copy(weight = 0.1)

        val decayed = AssociativeRetrievalDynamics.decay(current, policy)

        assertEquals(0.0, decayed.weight)
        assertEquals(current.validatedSuccessCount, decayed.validatedSuccessCount)
    }

    @Test
    fun rerank_never_introduces_missing_candidate_and_preserves_revalidation() {
        val fused = listOf(
            fused("a", 0.010),
            fused("b", 0.009),
            fused("c", 0.008)
        )
        val present = candidate("a", "b")
        val absent = candidate("a", "missing")
        val associations = listOf(
            AssociativeRetrievalDynamics.initialState(present).copy(weight = 1.0),
            AssociativeRetrievalDynamics.initialState(absent).copy(weight = 1.0)
        )

        val reranked = AssociativeRetrievalDynamics.rerank(fused, associations)

        assertEquals(setOf("a", "b", "c"), reranked.map { it.candidate.id.value }.toSet())
        assertTrue(reranked.all { it.requiresCanonicalRevalidation })
        assertTrue(reranked.all { it.candidate.requiresCanonicalRevalidation })
        assertTrue(
            reranked.flatMap { it.contributions }
                .none { it.associationId == absent.id }
        )
    }

    @Test
    fun rerank_bonus_is_bounded_per_candidate() {
        val policy = AssociativeRetrievalPolicy(maxScoreBonus = 0.002)
        val fused = listOf(fused("a", 0.010), fused("b", 0.009), fused("c", 0.008))
        val associations = listOf(
            candidate("a", "b", policy = policy),
            candidate("a", "c", policy = policy)
        ).map {
            AssociativeRetrievalDynamics.initialState(it).copy(weight = 1.0)
        }

        val reranked = AssociativeRetrievalDynamics.rerank(fused, associations, policy)
        val a = reranked.single { it.candidate.id.value == "a" }

        assertEquals(0.012, a.adjustedScore)
        assertEquals(2, a.contributions.size)
    }

    @Test
    fun association_identity_changes_with_participants_or_policy_version_input() {
        val first = candidate("a", "b")
        val second = candidate("a", "c")

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun participant_budget_rejects_unbounded_coactivation() {
        val ids = (0..AssociativeRetrievalPolicy.MAX_PARTICIPANTS)
            .map { RetrievalCandidateId("candidate-$it") }

        val candidate = AssociativeRetrievalDynamics.candidateFromCoactivation(ids)

        assertEquals(null, candidate)
    }

    private fun candidate(
        vararg ids: String,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): RetrievalAssociationCandidate =
        requireNotNull(
            AssociativeRetrievalDynamics.candidateFromCoactivation(
                ids.map(::RetrievalCandidateId),
                policy
            )
        )

    private fun validation(
        candidate: RetrievalAssociationCandidate,
        successful: Boolean,
        suffix: String = "0",
        sequence: Long = 1L
    ) = RetrievalAssociationValidation(
        associationId = candidate.id,
        participants = candidate.participants,
        validationId = "validation-$suffix",
        validationVersion = 1,
        validationSequence = sequence,
        groundedOutcomeId = "outcome-$suffix",
        successful = successful
    )

    private fun fused(id: String, score: Double) = HybridFusedCandidate(
        id = RetrievalCandidateId(id),
        fusedScore = score,
        contributions = listOf(
            HybridRankContribution(
                channelId = RetrievalChannelId("lexical"),
                rank = 1,
                reciprocalContribution = score
            )
        )
    )
}
