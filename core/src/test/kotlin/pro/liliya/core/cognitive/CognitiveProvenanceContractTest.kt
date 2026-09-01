package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningCandidateReference
import pro.liliya.core.learning.LearningGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CognitiveProvenanceContractTest {
    private val scope = CognitiveRuntimeScopeId("scope-fixed")
    private val turn = CognitiveTurnReference(
        id = CognitiveTurnId("turn-fixed"),
        generation = CognitiveTurnGeneration(7)
    )
    private val decision = DecisionReference(
        id = DecisionId("decision-fixed"),
        generation = DecisionGeneration(3)
    )
    private val learning = LearningCandidateReference(
        candidateId = LearningCandidateId("candidate-fixed"),
        generation = LearningGeneration(5)
    )

    @Test
    fun request_turn_and_result_provenance_have_stable_domain_separated_fixed_vectors() {
        assertEquals(
            "06f2cd7a41e1a964c76b92fe49bdb72fd56b900208908c6d52bea686b9e406d2",
            CognitiveProvenance.requestFingerprint(scope, turn.id)
        )
        assertEquals(
            "0eceff7a3904702d7255df44241d4982fbc07e1e9326581d4c5f654a9d288609",
            CognitiveProvenance.turnToken(scope, turn).value
        )
        assertEquals(
            "308140bd355f5eed5743106109886e7a70ff0e21edf2a271e6a2b2fa426be2b9",
            CognitiveProvenance.resultToken(scope, turn, decision).value
        )
    }

    @Test
    fun learning_idempotency_and_mutation_provenance_have_stable_domain_separated_fixed_vectors() {
        assertEquals(
            "fe582390229943a270b247e9083e51776e1857f8a11b9666a99588f14db527d5",
            CognitiveProvenance.learningIdempotencyToken(
                scope,
                learning,
                LearningApplicationTarget.MEMORY
            ).value
        )
        assertEquals(
            "f349733eb27678c0bbd67eecb07eb58e8ba9bf3f22965536d512a0f9e890b08c",
            CognitiveProvenance.learningMutationToken(
                scope,
                learning,
                LearningApplicationTarget.MEMORY
            ).value
        )
        assertEquals(
            "acdff176494588224beac99c2e9003abcbe3750a01f4f51dafd363fde09aaeea",
            CognitiveProvenance.learningIdempotencyToken(
                scope,
                learning,
                LearningApplicationTarget.KNOWLEDGE
            ).value
        )
        assertEquals(
            "7b4c82f7337a2c3c3c82da91909889626c80437affa26010bcb8c41fc32a2938",
            CognitiveProvenance.learningMutationToken(
                scope,
                learning,
                LearningApplicationTarget.KNOWLEDGE
            ).value
        )
    }

    @Test
    fun learning_idempotency_and_mutation_tokens_are_distinct_and_redacted() {
        val idempotency = CognitiveProvenance.learningIdempotencyToken(
            scope,
            learning,
            LearningApplicationTarget.MEMORY
        )
        val provenance = CognitiveProvenance.learningMutationToken(
            scope,
            learning,
            LearningApplicationTarget.MEMORY
        )

        assertNotEquals(idempotency.value, provenance.value)
        assertEquals(64, idempotency.value.length)
        assertEquals(64, provenance.value.length)
        assertFalse(idempotency.toString().contains(scope.value))
        assertFalse(idempotency.toString().contains(learning.candidateId.value))
        assertFalse(provenance.toString().contains(scope.value))
        assertFalse(provenance.toString().contains(learning.candidateId.value))
    }

    @Test
    fun same_turn_coordinates_in_different_runtime_scope_have_different_provenance() {
        val otherScope = CognitiveRuntimeScopeId("scope-other")

        assertNotEquals(
            CognitiveProvenance.turnToken(scope, turn),
            CognitiveProvenance.turnToken(otherScope, turn)
        )
        assertNotEquals(
            CognitiveProvenance.resultToken(scope, turn, decision),
            CognitiveProvenance.resultToken(otherScope, turn, decision)
        )
    }

    @Test
    fun provenance_value_rendering_does_not_echo_raw_scope_or_turn_id() {
        val turnToken = CognitiveProvenance.turnToken(scope, turn)
        val resultToken = CognitiveProvenance.resultToken(scope, turn, decision)

        assertFalse(turnToken.toString().contains(scope.value))
        assertFalse(turnToken.toString().contains(turn.id.value))
        assertFalse(resultToken.toString().contains(scope.value))
        assertFalse(resultToken.toString().contains(turn.id.value))
        assertEquals(64, turnToken.value.length)
        assertEquals(64, resultToken.value.length)
    }
}
