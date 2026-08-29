package pro.liliya.core.learning

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LearningApplicationReservationContractTest {
    private fun authorization(
        applicationId: String = "application-1",
        applicationGeneration: Long = 1L,
        target: LearningApplicationTarget = LearningApplicationTarget.MEMORY
    ): LearningApplicationAuthorizationReceipt {
        val reference = LearningApplicationIntentReference(
            LearningApplicationId(applicationId),
            LearningApplicationGeneration(applicationGeneration)
        )
        return LearningApplicationAuthorizationReceipt(
            preflight = LearningApplicationPreflightReceipt(
                application = reference,
                decision = LearningDecisionReference(
                    LearningDecisionId("decision-$applicationId-$applicationGeneration"),
                    LearningDecisionGeneration(1L)
                ),
                candidate = LearningCandidateReference(
                    LearningCandidateId("candidate-$applicationId-$applicationGeneration"),
                    LearningGeneration(1L)
                ),
                policy = LearningPolicyReference(
                    LearningPolicyId("policy-$applicationId-$applicationGeneration"),
                    LearningPolicyGeneration(1L)
                ),
                target = target
            ),
            principal = AuthorityPrincipal("learning-controller"),
            capability = CapabilityId("learning.application.apply"),
            scope = when (target) {
                LearningApplicationTarget.MEMORY -> AuthorityScope("learning.application.memory")
                LearningApplicationTarget.KNOWLEDGE -> AuthorityScope("learning.application.knowledge")
            }
        )
    }

    @Test
    fun exact_application_generation_has_only_one_active_reservation() {
        val registry = LearningApplicationReservationRegistry()
        val receipt = authorization()

        val first = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt))
        assertIs<LearningApplicationReserveResult.Rejected>(registry.reserve(receipt))

        val reference = receipt.preflight.application
        assertTrue(registry.contains(reference))
        assertEquals(first.ownership.generation, registry.inspect(reference)?.generation)
    }

    @Test
    fun exact_owner_release_allows_replacement_with_new_generation() {
        val registry = LearningApplicationReservationRegistry()
        val receipt = authorization()

        val first = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt)).ownership
        assertTrue(first.release())
        assertFalse(first.release())

        val replacement = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt)).ownership
        assertNotEquals(first.generation, replacement.generation)
        assertTrue(registry.contains(receipt.preflight.application))
    }

    @Test
    fun stale_ownership_cannot_release_replacement() {
        val registry = LearningApplicationReservationRegistry()
        val receipt = authorization()

        val stale = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt)).ownership
        assertTrue(stale.release())
        val replacement = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt)).ownership

        assertFalse(stale.release())
        assertTrue(registry.contains(receipt.preflight.application))
        assertEquals(replacement.generation, registry.inspect(receipt.preflight.application)?.generation)
    }

    @Test
    fun different_application_generations_are_independent_reservation_keys() {
        val registry = LearningApplicationReservationRegistry()
        val first = authorization(applicationGeneration = 1L)
        val second = authorization(applicationGeneration = 2L)

        assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(first))
        assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(second))

        assertTrue(registry.contains(first.preflight.application))
        assertTrue(registry.contains(second.preflight.application))
        assertEquals(2, registry.snapshotEntries().size)
    }

    @Test
    fun reservation_preserves_authorization_receipt_without_claiming_application_success() {
        val registry = LearningApplicationReservationRegistry()
        val receipt = authorization(target = LearningApplicationTarget.KNOWLEDGE)

        val reserved = assertIs<LearningApplicationReserveResult.Reserved>(registry.reserve(receipt)).ownership

        assertEquals(receipt, reserved.authorization)
        assertEquals(LearningApplicationTarget.KNOWLEDGE, reserved.authorization.preflight.target)
    }
}
