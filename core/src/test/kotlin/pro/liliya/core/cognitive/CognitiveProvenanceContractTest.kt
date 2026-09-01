package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
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
