package pro.liliya.core.authority

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthorityDelegationProvenanceContractTest {
    @Test
    fun delegated_grant_keeps_provenance_and_cannot_be_redelegated() {
        val now = Instant.parse("2026-08-28T16:00:00Z")
        val planner = AuthorityPrincipal("planner")
        val executor = AuthorityPrincipal("executor")
        val worker = AuthorityPrincipal("worker")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")

        val first = AuthorityDelegationPolicy(
            sourceGrants = listOf(
                ScopedAuthorityGrant(
                    principal = planner,
                    capability = capability,
                    scope = scope,
                    expiresAt = now.plusSeconds(60)
                )
            ),
            now = { now }
        ).decide(
            AuthorityDelegationRequest(
                delegator = planner,
                delegate = executor,
                capability = capability,
                scope = scope,
                reason = "launch maps",
                expiresAt = now.plusSeconds(30)
            )
        )

        val delegated = assertIs<AuthorityDelegationDecision.Granted>(first).grant.asScopedGrant()
        assertEquals(AuthorityGrantOrigin.DELEGATED, delegated.origin)

        val second = AuthorityDelegationPolicy(
            sourceGrants = listOf(delegated),
            now = { now }
        ).decide(
            AuthorityDelegationRequest(
                delegator = executor,
                delegate = worker,
                capability = capability,
                scope = scope,
                reason = "forward launch authority",
                expiresAt = now.plusSeconds(20)
            )
        )

        assertIs<AuthorityDelegationDecision.Denied>(second)
    }

    @Test
    fun direct_grant_remains_a_valid_delegation_source() {
        val now = Instant.parse("2026-08-28T16:00:00Z")
        val planner = AuthorityPrincipal("planner")
        val executor = AuthorityPrincipal("executor")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")
        val direct = ScopedAuthorityGrant(planner, capability, scope, now.plusSeconds(60))

        assertEquals(AuthorityGrantOrigin.DIRECT, direct.origin)
        assertIs<AuthorityDelegationDecision.Granted>(
            AuthorityDelegationPolicy(listOf(direct), now = { now }).decide(
                AuthorityDelegationRequest(
                    delegator = planner,
                    delegate = executor,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps",
                    expiresAt = now.plusSeconds(30)
                )
            )
        )
    }
}
