package pro.liliya.core.cognitive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveTurnRegistryContractTest {
    private val limits = CognitiveRuntimeLimits(
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
        ).ownership

        assertEquals(1L, first.reference.generation.value)
        assertIs<CognitiveTurnRegistrationResult.Rejected>(
            registry.register(CognitiveTurnId("other"), CognitiveInput("blocked"))
        )

        val context = CognitiveContextSnapshot(first.reference, emptyList())
        assertIs<CognitiveTurnPublicationResult.Published>(first.publishContextIfCurrent(context))
        assertIs<CognitiveTurnTransitionResult.Transitioned>(first.beginGenerating())
        assertIs<CognitiveTurnPublicationResult.Published>(
            first.publishInferenceIfCurrent(
                CognitiveInferenceResult.Succeeded(first.reference, "answer")
            )
        )
        assertIs<CognitiveTurnTransitionResult.Transitioned>(first.complete())
        assertFalse(first.isCurrent())
        assertNull(registry.currentReference())

        val second = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("turn"), CognitiveInput("again"))
        ).ownership
        assertEquals(2L, second.reference.generation.value)
        assertTrue(second.isCurrent())
    }

    @Test
    fun stale_owner_cannot_publish_into_replacement_turn() {
        val registry = CognitiveTurnRegistry(limits)
        val first = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("same"), CognitiveInput("first"))
        ).ownership
        assertIs<CognitiveTurnTransitionResult.Rejected>(first.fail())

        val replacement = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("same"), CognitiveInput("replacement"))
        ).ownership

        assertEquals(2L, replacement.reference.generation.value)
        assertIs<CognitiveTurnPublicationResult.Stale>(
            first.publishContextIfCurrent(CognitiveContextSnapshot(first.reference, emptyList()))
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

        val ownership = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("bounded"), CognitiveInput("hello"))
        ).ownership

        val oversizedContext = CognitiveContextSnapshot(
            ownership.reference,
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
            ownership.publishContextIfCurrent(oversizedContext)
        )
        assertEquals(CognitiveTurnLifecycle.CREATED, ownership.lifecycle())

        assertIs<CognitiveTurnPublicationResult.Published>(
            ownership.publishContextIfCurrent(CognitiveContextSnapshot(ownership.reference, emptyList()))
        )
        assertIs<CognitiveTurnTransitionResult.Transitioned>(ownership.beginGenerating())
        assertIs<CognitiveTurnPublicationResult.Rejected>(
            ownership.publishInferenceIfCurrent(
                CognitiveInferenceResult.Succeeded(ownership.reference, "x".repeat(33))
            )
        )
        assertEquals(CognitiveTurnLifecycle.GENERATING, ownership.lifecycle())
    }

    @Test
    fun reentrant_registration_during_publication_is_contained_and_does_not_advance_turn() {
        val registry = CognitiveTurnRegistry(limits)
        val ownership = assertIs<CognitiveTurnRegistrationResult.Registered>(
            registry.register(CognitiveTurnId("reentrant"), CognitiveInput("hello"))
        ).ownership

        val result = ownership.publishContextIfCurrent(
            CognitiveContextSnapshot(ownership.reference, emptyList())
        ) {
            registry.register(CognitiveTurnId("nested"), CognitiveInput("nested"))
        }

        assertIs<CognitiveTurnPublicationResult.Failed>(result)
        assertTrue(ownership.isCurrent())
        assertEquals(CognitiveTurnLifecycle.CREATED, ownership.lifecycle())
        assertNull(ownership.context())
    }
}
