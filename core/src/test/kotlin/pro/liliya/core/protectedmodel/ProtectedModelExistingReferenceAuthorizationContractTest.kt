package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedModelExistingReferenceAuthorizationContractTest {
    @Test
    fun allowed_exact_reference_authorizes_and_publishes_under_same_epoch() {
        val reference = reference(1)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(reference) }
        var policyCalls = 0
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy {
                policyCalls += 1
                ProtectedModelPolicyDecision.Allowed
            }
        )

        val authorized = assertIs<ProtectedModelAuthorizationResult.Authorized>(
            coordinator.authorizeExistingReference(reference)
        )
        assertEquals(reference, authorized.authorization.reference)
        assertEquals(1, policyCalls)

        var published = false
        assertIs<ProtectedModelAuthorizedPublicationResult.Published>(
            coordinator.publishAuthorized(authorized.authorization) { publishedReference ->
                assertEquals(reference, publishedReference)
                published = true
            }
        )
        assertTrue(published)
    }

    @Test
    fun policy_rejection_happens_before_authorization_and_never_publishes() {
        val reference = reference(1)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(reference) }
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy {
                ProtectedModelPolicyDecision.Rejected(
                    ProtectedModelPolicyFailure.ENTITLEMENT_REJECTED
                )
            }
        )

        val rejected = assertIs<ProtectedModelAuthorizationResult.Rejected>(
            coordinator.authorizeExistingReference(reference)
        )
        assertEquals(ProtectedModelAccessFailure.POLICY_REJECTED, rejected.reason)
    }

    @Test
    fun replacement_after_authorization_makes_final_publication_stale() {
        val oldReference = reference(1)
        val newerReference = reference(2)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(oldReference) }
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed }
        )

        val authorized = assertIs<ProtectedModelAuthorizationResult.Authorized>(
            coordinator.authorizeExistingReference(oldReference)
        )
        ownership.replaceTarget(newerReference)

        var published = false
        assertIs<ProtectedModelAuthorizedPublicationResult.Stale>(
            coordinator.publishAuthorized(authorized.authorization) { published = true }
        )
        assertFalse(published)
        assertEquals(newerReference, ownership.currentReference())
    }

    @Test
    fun value_equal_reference_under_new_epoch_invalidates_old_authorization() {
        val reference = reference(1)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(reference) }
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed }
        )

        val authorized = assertIs<ProtectedModelAuthorizationResult.Authorized>(
            coordinator.authorizeExistingReference(reference)
        )
        ownership.replaceTarget(reference)

        assertIs<ProtectedModelAuthorizedPublicationResult.Stale>(
            coordinator.publishAuthorized(authorized.authorization) {
                error("old authorization must not publish into a new ownership epoch")
            }
        )
    }

    @Test
    fun target_change_during_policy_callback_is_rejected_as_stale() {
        val oldReference = reference(1)
        val newerReference = reference(2)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(oldReference) }
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy {
                ownership.replaceTarget(newerReference)
                ProtectedModelPolicyDecision.Allowed
            }
        )

        val rejected = assertIs<ProtectedModelAuthorizationResult.Rejected>(
            coordinator.authorizeExistingReference(oldReference)
        )
        assertEquals(ProtectedModelAccessFailure.STALE_OWNERSHIP, rejected.reason)
        assertEquals(newerReference, ownership.currentReference())
    }

    @Test
    fun retired_target_cannot_authorize_existing_reference() {
        val reference = reference(1)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(reference) }
        assertTrue(ownership.retire(reference))
        val coordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed }
        )

        val rejected = assertIs<ProtectedModelAuthorizationResult.Rejected>(
            coordinator.authorizeExistingReference(reference)
        )
        assertEquals(ProtectedModelAccessFailure.NO_ACTIVE_TARGET, rejected.reason)
    }

    @Test
    fun policy_and_publication_failures_render_without_secret_messages() {
        val reference = reference(1)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(reference) }
        val policyCoordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy {
                throw IllegalStateException("private-policy-secret")
            }
        )

        val policyFailure = assertIs<ProtectedModelAuthorizationResult.Failed>(
            policyCoordinator.authorizeExistingReference(reference)
        )
        assertEquals(ProtectedModelAccessFailure.PROVIDER_FAILED, policyFailure.reason)
        assertFalse(policyFailure.toString().contains("private-policy-secret"))

        val publishCoordinator = coordinator(
            ownership,
            ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed }
        )
        val authorization = assertIs<ProtectedModelAuthorizationResult.Authorized>(
            publishCoordinator.authorizeExistingReference(reference)
        ).authorization
        val publishFailure = assertIs<ProtectedModelAuthorizedPublicationResult.Failed>(
            publishCoordinator.publishAuthorized(authorization) {
                throw IllegalStateException("private-publication-secret")
            }
        )
        assertEquals(ProtectedModelAccessFailure.PUBLISH_FAILED, publishFailure.reason)
        assertFalse(publishFailure.toString().contains("private-publication-secret"))
        val renderedAuthorization = authorization.toString()
        assertFalse(
            renderedAuthorization.contains("epoch=${authorization.ticket.attemptId.value}")
        )
        assertTrue(renderedAuthorization.contains("epoch=<redacted>"))
    }

    private fun reference(generation: Long): ProtectedModelReference =
        ProtectedModelReference(
            ProtectedModelPackageId("assembly-model"),
            ProtectedModelGeneration(generation)
        )

    private fun coordinator(
        ownership: ProtectedModelRuntimeOwnership,
        policy: ProtectedModelAccessPolicy
    ): ProtectedModelAccessCoordinator = ProtectedModelAccessCoordinator(
        policy = policy,
        ownership = ownership,
        loader = ProtectedModelPayloadLoader(
            verifier = ProtectedModelPackageVerifier(
                ProtectedModelSignerResolver { _, _ -> null }
            ),
            dekResolver = ProtectedModelDekResolver { _, _ -> null },
            maxPlaintextSizeBytes = 1L
        )
    )
}
