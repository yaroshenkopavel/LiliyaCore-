package pro.liliya.core.authority

import java.time.Instant

data class AuthorityDelegationRequest(
    val delegator: AuthorityPrincipal,
    val delegate: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope,
    val reason: String,
    val expiresAt: Instant? = null
) {
    init {
        require(delegator != delegate) { "delegator and delegate must differ" }
        require(reason.isNotBlank()) { "delegation reason must not be blank" }
    }
}

data class DelegatedAuthorityGrant(
    val delegator: AuthorityPrincipal,
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope,
    val expiresAt: Instant? = null
) {
    fun asScopedGrant(): ScopedAuthorityGrant = ScopedAuthorityGrant(
        principal = principal,
        capability = capability,
        scope = scope,
        expiresAt = expiresAt,
        origin = AuthorityGrantOrigin.DELEGATED
    )
}

sealed interface AuthorityDelegationDecision {
    data class Granted(val grant: DelegatedAuthorityGrant) : AuthorityDelegationDecision
    data class Denied(val reason: String) : AuthorityDelegationDecision {
        init {
            require(reason.isNotBlank()) { "delegation denial reason must not be blank" }
        }
    }
}
