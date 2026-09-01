package pro.liliya.core.cognitive

import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveTurnRegistryContractTest {
    private val limits = CognitiveRuntimeLimits(
        maxTurnIdChars = 16,
        maxInputChars = 32,
        maxContextItems = 2,
        maxContextItemChars = 16,
        maxRetrievalResults = 2,
        maxInferenceOutputChars = 32
    )

    @Test
    fun one_live_turn_is_owned_and_fresh_turn_gets_new_generation_after_completion() {
        val registry = CognitiveTurnRegistry(limits)
        val first = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("turn"), CognitiveInput("hello"))
        ).turn

        assertEquals(1L, first.reference.generation.value)
        assertIs<CognitiveTurnRegistrationResult.Rejected>(
            registry.register(CognitiveTurnId("other"), CognitiveInput("blocked"))
        )

        publishAcceptedCognition(registry, first.reference)
        assertIs<CognitiveTurnTransitionResult.Transitioned>(
            registry.completeIfCurrent(first.reference)
        )
        assertIs<CognitiveTurnTransitionResult.Stale>(
            registry.completeIfCurrent(first.reference)
        )
        assertFalse(first.isCurrent())
        assertNull(registry.currentReference())

        val second = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("turn"), CognitiveInput("again"))
        ).turn
        assertEquals(2L, second.reference.generation.value)
        assertTrue(second.isCurrent())
        assertIs<CognitiveTurnTransitionResult.Stale>(
            registry.failIfCurrent(first.reference)
        )
        assertEquals(second.reference, registry.currentReference())
    }

    @Test
    fun over_bound_turn_id_is_rejected_before_generation_is_consumed() {
        val registry = CognitiveTurnRegistry(limits)
        val rejected = assertIs<CognitiveTurnRegistrationResult.Rejected>(
            registry.register(CognitiveTurnId("x".repeat(17)), CognitiveInput("hello"))
        )
        assertEquals(CognitiveTurnRegistrationFailure.TURN_ID_LIMIT_REJECTED, rejected.reason)
        assertNull(registry.currentReference())

        val accepted = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("bounded"), CognitiveInput("hello"))
        ).turn
        assertEquals(1L, accepted.reference.generation.value)
    }

    @Test
    fun stale_reference_cannot_publish_into_replacement_turn() {
        val registry = CognitiveTurnRegistry(limits)
        val first = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("same"), CognitiveInput("first"))
        ).turn
        val failure = assertIs<CognitiveTurnTransitionResult.Failed>(
            registry.failIfCurrent(first.reference)
        )
        assertEquals(CognitiveTurnFailure.TURN_FAILED, failure.reason)
        assertEquals(CognitiveTurnLifecycle.FAILED, first.lifecycle())
        assertIs<CognitiveTurnTransitionResult.Stale>(
            registry.failIfCurrent(first.reference)
        )

        val replacement = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("same"), CognitiveInput("replacement"))
        ).turn

        assertEquals(2L, replacement.reference.generation.value)
        assertIs<CognitiveTurnPublicationResult.Stale>(
            registry.publishContextIfCurrent(
                first.reference,
                CognitiveContextSnapshot(first.reference, emptyList())
            )
        )
        assertEquals(replacement.reference, registry.currentReference())
        assertEquals(CognitiveTurnLifecycle.CREATED, replacement.lifecycle())
    }

    @Test
    fun generation_overflow_fails_closed_without_live_turn() {
        val registry = CognitiveTurnRegistry(limits, initialGeneration = Long.MAX_VALUE)
        val result = assertIs<CognitiveTurnRegistrationResult.Rejected>(
            registry.register(CognitiveTurnId("overflow"), CognitiveInput("hello"))
        )
        assertEquals(CognitiveTurnRegistrationFailure.GENERATION_OVERFLOW, result.reason)
        assertNull(registry.currentReference())
    }

    @Test
    fun input_context_and_inference_limits_fail_closed() {
        val registry = CognitiveTurnRegistry(limits)
        val oversizedInput = assertIs<CognitiveTurnRegistrationResult.Rejected>(
            registry.register(CognitiveTurnId("input"), CognitiveInput("x".repeat(33)))
        )
        assertEquals(CognitiveTurnRegistrationFailure.INPUT_LIMIT_REJECTED, oversizedInput.reason)

        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("bounded"), CognitiveInput("hello"))
        ).turn

        val oversizedContext = CognitiveContextSnapshot(
            turn.reference,
            listOf(
                CognitiveContextItem(
                    source = CognitiveContextSourceReference.Memory(
                        pro.liliya.core.memory.MemoryRecordId("m1"),
                        pro.liliya.core.memory.MemoryGeneration(1)
                    ),
                    content = "x".repeat(17)
                )
            )
        )
        assertIs<CognitiveTurnPublicationResult.Rejected>(
            registry.publishContextIfCurrent(turn.reference, oversizedContext)
        )
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())

        publishContextAndBeginGeneration(registry, turn.reference)
        assertIs<CognitiveTurnPublicationResult.Rejected>(
            registry.publishAcceptedCognitionIfCurrent(
                reference = turn.reference,
                inference = CognitiveInferenceResult.Succeeded(turn.reference, "x".repeat(33)),
                receipt = receipt(turn.reference)
            )
        )
        assertEquals(CognitiveTurnLifecycle.GENERATING, turn.lifecycle())
        assertNull(registry.inferenceIfCurrent(turn.reference))
        assertNull(registry.acceptedCognitionIfCurrent(turn.reference))
    }

    @Test
    fun accepted_cognition_receipt_is_committed_atomically_with_inference_and_ready_state() {
        val registry = CognitiveTurnRegistry(limits)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("receipt"), CognitiveInput("hello"))
        ).turn
        publishContextAndBeginGeneration(registry, turn.reference)
        val accepted = receipt(turn.reference)
        val inference = CognitiveInferenceResult.Succeeded(turn.reference, "answer")

        assertIs<CognitiveTurnPublicationResult.Published>(
            registry.publishAcceptedCognitionIfCurrent(turn.reference, inference, accepted)
        )

        assertEquals(CognitiveTurnLifecycle.COGNITION_READY, turn.lifecycle())
        assertEquals(inference, registry.inferenceIfCurrent(turn.reference))
        assertEquals(accepted, registry.acceptedCognitionIfCurrent(turn.reference))
    }

    @Test
    fun foreign_inference_or_receipt_cannot_advance_current_turn() {
        val registry = CognitiveTurnRegistry(limits)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("exact"), CognitiveInput("hello"))
        ).turn
        publishContextAndBeginGeneration(registry, turn.reference)
        val foreign = CognitiveTurnReference(CognitiveTurnId("foreign"), CognitiveTurnGeneration(999))

        assertIs<CognitiveTurnPublicationResult.Rejected>(
            registry.publishAcceptedCognitionIfCurrent(
                reference = turn.reference,
                inference = CognitiveInferenceResult.Succeeded(foreign, "foreign-answer"),
                receipt = receipt(turn.reference)
            )
        )
        assertEquals(CognitiveTurnLifecycle.GENERATING, turn.lifecycle())
        assertNull(registry.inferenceIfCurrent(turn.reference))
        assertNull(registry.acceptedCognitionIfCurrent(turn.reference))

        assertIs<CognitiveTurnPublicationResult.Rejected>(
            registry.publishAcceptedCognitionIfCurrent(
                reference = turn.reference,
                inference = CognitiveInferenceResult.Succeeded(turn.reference, "answer"),
                receipt = receipt(foreign)
            )
        )
        assertEquals(CognitiveTurnLifecycle.GENERATING, turn.lifecycle())
        assertNull(registry.inferenceIfCurrent(turn.reference))
        assertNull(registry.acceptedCognitionIfCurrent(turn.reference))
    }

    @Test
    fun context_snapshot_detaches_mutable_input_list() {
        val registry = CognitiveTurnRegistry(limits)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("context-copy"), CognitiveInput("hello"))
        ).turn
        val item = CognitiveContextItem(
            source = CognitiveContextSourceReference.Memory(
                pro.liliya.core.memory.MemoryRecordId("m-copy"),
                pro.liliya.core.memory.MemoryGeneration(1)
            ),
            content = "remember"
        )
        val mutableItems = mutableListOf(item)
        val snapshot = CognitiveContextSnapshot(turn.reference, mutableItems)

        mutableItems.clear()

        assertEquals(listOf(item), snapshot.items)
        assertIs<CognitiveTurnPublicationResult.Published>(
            registry.publishContextIfCurrent(turn.reference, snapshot)
        )
        assertEquals(listOf(item), registry.contextIfCurrent(turn.reference)?.items)
    }

    @Test
    fun reentrant_registration_during_publication_is_contained_and_does_not_advance_turn() {
        val registry = CognitiveTurnRegistry(limits)
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("reentrant"), CognitiveInput("hello"))
        ).turn

        val result = registry.publishContextIfCurrent(
            turn.reference,
            CognitiveContextSnapshot(turn.reference, emptyList())
        ) {
            registry.register(CognitiveTurnId("nested"), CognitiveInput("nested"))
        }

        assertIs<CognitiveTurnPublicationResult.Failed>(result)
        assertTrue(turn.isCurrent())
        assertEquals(CognitiveTurnLifecycle.CREATED, turn.lifecycle())
        assertNull(registry.contextIfCurrent(turn.reference))
    }

    private fun publishAcceptedCognition(
        registry: CognitiveTurnRegistry,
        reference: CognitiveTurnReference
    ) {
        publishContextAndBeginGeneration(registry, reference)
        assertIs<CognitiveTurnPublicationResult.Published>(
            registry.publishAcceptedCognitionIfCurrent(
                reference = reference,
                inference = CognitiveInferenceResult.Succeeded(reference, "answer"),
                receipt = receipt(reference)
            )
        )
    }

    private fun publishContextAndBeginGeneration(
        registry: CognitiveTurnRegistry,
        reference: CognitiveTurnReference
    ) {
        assertIs<CognitiveTurnPublicationResult.Published>(
            registry.publishContextIfCurrent(reference, CognitiveContextSnapshot(reference, emptyList()))
        )
        assertIs<CognitiveTurnTransitionResult.Transitioned>(
            registry.beginGeneratingIfCurrent(reference)
        )
    }

    private fun receipt(reference: CognitiveTurnReference): AcceptedCognitionReceipt =
        AcceptedCognitionReceipt(
            turn = reference,
            planning = PlanningReference(PlanningProposalId("planning"), PlanningGeneration(1)),
            reasoning = ReasoningReference(ReasoningArtifactId("reasoning"), ReasoningGeneration(1)),
            decision = DecisionReference(DecisionId("decision"), DecisionGeneration(1))
        )
}
