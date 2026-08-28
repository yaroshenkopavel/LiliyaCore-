package pro.liliya.core.authority

import java.time.Instant

data class DirectAuthorityGrant(
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
        origin = AuthorityGrantOrigin.DIRECT
    )
}
