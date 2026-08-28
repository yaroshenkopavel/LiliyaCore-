package pro.liliya.core.authority

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthorityReadinessContractTest {
    @Test
    fun legacy_explicit_grants_are_global_only() {
        val principal = AuthorityPrincipal("planner")
        val capability = CapabilityId("memory.read")
        val policy = ExplicitGrantAuthorityPolicy(mapOf(principal to setOf(capability)))

        assertEquals(
            AuthorityDecision.Granted,
            policy.decide(AuthorityRequest(principal, capability, "read global memory"))
        )
        assertIs<AuthorityDecision.Denied>(
            policy.decide(
                AuthorityRequest(
                    principal = principal,
                    capability = capability,
                    reason = "read conversation memory",
                    scope = AuthorityScope("conversation:42")
                )
            )
        )
    }

    @Test
    fun direct_grant_conversion_preserves_direct_provenance() {
        val grant = DirectAuthorityGrant(
            principal = AuthorityPrincipal("planner"),
            capability = CapabilityId("memory.read"),
            scope = AuthorityScope("conversation:42")
        )

        assertEquals(AuthorityGrantOrigin.DIRECT, grant.asScopedGrant().origin)
    }
}
