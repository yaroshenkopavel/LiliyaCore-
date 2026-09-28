package pro.liliya.core.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssociativeRetrievalStateContractTest {
    @Test
    fun pair_identity_is_unordered_deterministic_and_distinct_only() {
        val a = RetrievalCandidateId("semantic-claim:a:v1")
        val b = RetrievalCandidateId("semantic-claim:b:v1")

        val first = AssociativeRetrievalPair.of(a, b)
        val second = AssociativeRetrievalPair.of(b, a)

        assertEquals(first, second)
        assertEquals(first.id, second.id)
        assertTrue(compareUtf8(first.left.value, first.right.value) < 0)
        assertFailsWith<IllegalArgumentException> {
            AssociativeRetrievalPair.of(a, a)
        }
    }

    @Test
    fun coactivation_creates_candidate_without_increasing_validated_strength() {
        val pair = pair()
        val first = transition(null, AssociativeRetrievalSignal.CoActivated(pair))
        val firstState = assertIs<AssociativeRetrievalTransitionResult.Updated>(first).state
        val second = transition(
            firstState,
            AssociativeRetrievalSignal.CoActivated(pair)
        )
        val secondState = assertIs<AssociativeRetrievalTransitionResult.Updated>(second).state

        assertEquals(1, firstState.candidateObservationCount)
        assertEquals(2, secondState.candidateObservationCount)
        assertEquals(0, firstState.validatedStrengthUnits)
        assertEquals(0, secondState.validatedStrengthUnits)
        assertTrue(firstState.advisoryOnly)
    }

    @Test
    fun validated_success_requires_observed_candidate_and_strengthens_once() {
        val pair = pair()
        val evidence = AssociativeValidationEvidenceId("validation-1")
        val sequence = AssociativeValidationSequence(1)

        val rejected = transition(
            null,
            AssociativeRetrievalSignal.ValidatedSuccess(pair, sequence, evidence)
        )
        assertIs<AssociativeRetrievalTransitionResult.Rejected>(rejected)

        val observed = observed(pair)
        val strengthened = transition(
            observed,
            AssociativeRetrievalSignal.ValidatedSuccess(pair, sequence, evidence)
        )
        val updated = assertIs<AssociativeRetrievalTransitionResult.Updated>(strengthened)

        assertEquals(50, updated.state.validatedStrengthUnits)
        assertEquals(sequence, updated.state.lastValidationSequence)
        assertEquals(evidence, updated.state.lastValidationEvidenceId)
        assertEquals(0, updated.audit.priorStrengthUnits)
        assertEquals(50, updated.audit.newStrengthUnits)
        assertTrue(updated.audit.advisoryOnly)
    }
    @Test
    fun duplicate_or_stale_validation_sequence_never_strengthens_again() {
        val pair = pair()
        val observed = observed(pair)
        val accepted = assertIs<AssociativeRetrievalTransitionResult.Updated>(
            transition(
                observed,
                AssociativeRetrievalSignal.ValidatedSuccess(
                    pair,
                    AssociativeValidationSequence(10),
                    AssociativeValidationEvidenceId("validation-10")
                )
            )
        ).state

        val duplicate = transition(
            accepted,
            AssociativeRetrievalSignal.ValidatedSuccess(
                pair,
                AssociativeValidationSequence(10),
                AssociativeValidationEvidenceId("validation-10-replay")
            )
        )
        val stale = transition(
            accepted,
            AssociativeRetrievalSignal.ValidatedSuccess(
                pair,
                AssociativeValidationSequence(9),
                AssociativeValidationEvidenceId("validation-9")
            )
        )

        assertIs<AssociativeRetrievalTransitionResult.NoChange>(duplicate)
        assertIs<AssociativeRetrievalTransitionResult.NoChange>(stale)
        assertEquals(50, duplicate.state.validatedStrengthUnits)
        assertEquals(50, stale.state.validatedStrengthUnits)
    }

    @Test
    fun validated_strength_is_saturating_and_integer_bounded() {
        val policy = AssociativeRetrievalPolicy(
            maxStrengthUnits = 100,
            strengthenStepUnits = 60,
            decayStepUnits = 10
        )
        val pair = pair()
        var state = observed(pair, policy)

        state = strengthened(state, pair, 1, "validation-1", policy)
        assertEquals(60, state.validatedStrengthUnits)

        state = strengthened(state, pair, 2, "validation-2", policy)
        assertEquals(100, state.validatedStrengthUnits)

        state = strengthened(state, pair, 3, "validation-3", policy)
        assertEquals(100, state.validatedStrengthUnits)
        assertEquals(AssociativeValidationSequence(3), state.lastValidationSequence)
    }

    @Test
    fun decay_is_bounded_bottoms_at_zero_and_does_not_clear_validation_provenance() {
        val policy = AssociativeRetrievalPolicy(
            maxStrengthUnits = 100,
            strengthenStepUnits = 100,
            decayStepUnits = 30,
            maxDecayStepsPerCall = 4
        )
        val pair = pair()
        var state = observed(pair, policy)
        state = strengthened(state, pair, 1, "validation-1", policy)

        val decayed = transition(
            state,
            AssociativeRetrievalSignal.Decay(pair, steps = 2),
            policy
        )
        state = assertIs<AssociativeRetrievalTransitionResult.Updated>(decayed).state
        assertEquals(40, state.validatedStrengthUnits)
        assertEquals(AssociativeValidationSequence(1), state.lastValidationSequence)

        val bottom = transition(
            state,
            AssociativeRetrievalSignal.Decay(pair, steps = 4),
            policy
        )
        state = assertIs<AssociativeRetrievalTransitionResult.Updated>(bottom).state
        assertEquals(0, state.validatedStrengthUnits)
        assertEquals(AssociativeValidationEvidenceId("validation-1"), state.lastValidationEvidenceId)

        val overBudget = transition(
            state,
            AssociativeRetrievalSignal.Decay(pair, steps = 5),
            policy
        )
        assertIs<AssociativeRetrievalTransitionResult.Rejected>(overBudget)
    }

    @Test
    fun reset_clears_only_derived_associative_state() {
        val pair = pair()
        var state = observed(pair)
        state = strengthened(state, pair, 1, "validation-1")

        val reset = transition(state, AssociativeRetrievalSignal.Reset(pair))
        val updated = assertIs<AssociativeRetrievalTransitionResult.Updated>(reset)

        assertEquals(pair, updated.state.pair)
        assertEquals(0, updated.state.candidateObservationCount)
        assertEquals(0, updated.state.validatedStrengthUnits)
        assertEquals(null, updated.state.lastValidationSequence)
        assertEquals(null, updated.state.lastValidationEvidenceId)
        assertTrue(updated.state.advisoryOnly)
    }

    @Test
    fun mismatched_pair_is_rejected_without_cross_association_mutation() {
        val first = pair("a", "b")
        val second = pair("a", "c")
        val state = observed(first)

        val result = transition(
            state,
            AssociativeRetrievalSignal.CoActivated(second)
        )
        val rejected = assertIs<AssociativeRetrievalTransitionResult.Rejected>(result)
        assertEquals(second.id, rejected.associationId)
    }

    @Test
    fun identical_inputs_produce_identical_transition_results() {
        val pair = pair()
        val signal = AssociativeRetrievalSignal.CoActivated(pair)

        assertEquals(
            transition(null, signal),
            transition(null, signal)
        )

        val observed = observed(pair)
        val validated = AssociativeRetrievalSignal.ValidatedSuccess(
            pair,
            AssociativeValidationSequence(7),
            AssociativeValidationEvidenceId("validation-7")
        )
        assertEquals(
            transition(observed, validated),
            transition(observed, validated)
        )
    }

    private fun observed(
        pair: AssociativeRetrievalPair,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): AssociativeRetrievalState =
        assertIs<AssociativeRetrievalTransitionResult.Updated>(
            transition(null, AssociativeRetrievalSignal.CoActivated(pair), policy)
        ).state

    private fun strengthened(
        state: AssociativeRetrievalState,
        pair: AssociativeRetrievalPair,
        sequence: Long,
        evidenceId: String,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): AssociativeRetrievalState =
        assertIs<AssociativeRetrievalTransitionResult.Updated>(
            transition(
                state,
                AssociativeRetrievalSignal.ValidatedSuccess(
                    pair,
                    AssociativeValidationSequence(sequence),
                    AssociativeValidationEvidenceId(evidenceId)
                ),
                policy
            )
        ).state

    private fun transition(
        state: AssociativeRetrievalState?,
        signal: AssociativeRetrievalSignal,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): AssociativeRetrievalTransitionResult =
        DeterministicAssociativeRetrievalStateMachine.apply(state, signal, policy)

    private fun pair(
        left: String = "a",
        right: String = "b"
    ): AssociativeRetrievalPair =
        AssociativeRetrievalPair.of(
            RetrievalCandidateId("semantic-claim:$left:v1"),
            RetrievalCandidateId("semantic-claim:$right:v1")
        )
}
