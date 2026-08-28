package pro.liliya.core.authority

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthorityDelegationProvenanceContractTest {
    @Test
    fun delegated_grant_keeps_delegated_provenance() {
        val now = Instant.parse("2026-08-28T16:00:00Z")
        val planner = AuthorityPrincipal("planner")
        val executor = AuthorityPrincipal("executor")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")

        val first = AuthorityDelegationPolicy(
            sourceGrants = listOf(
                DirectAuthorityGrant(
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
    }

    @Test
    fun direct_grant_is_the_only_delegation_source_type() {
        val now = Instant.parse("2026-08-28T16:00:00Z")
        val planner = AuthorityPrincipal("planner")
        val executor = AuthorityPrincipal("executor")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")
        val direct = DirectAuthorityGrant(planner, capability, scope, now.plusSeconds(60))

        assertEquals(AuthorityGrantOrigin.DIRECT, direct.asScopedGrant().origin)
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
