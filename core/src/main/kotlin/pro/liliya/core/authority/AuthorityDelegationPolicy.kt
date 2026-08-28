package pro.liliya.core.authority

import java.time.Instant

class AuthorityDelegationPolicy(
    sourceGrants: Collection<ScopedAuthorityGrant>,
    private val now: () -> Instant = Instant::now
) {
    private val sourceGrants = sourceGrants.toList()

    fun decide(request: AuthorityDelegationRequest): AuthorityDelegationDecision {
        val candidates = sourceGrants.filter { grant ->
            grant.origin == AuthorityGrantOrigin.DIRECT &&
                grant.principal == request.delegator &&
                grant.capability == request.capability &&
                grant.scope == request.scope
        }

        if (candidates.isEmpty()) {
            return AuthorityDelegationDecision.Denied(
                "delegator ${request.delegator} does not own a direct capability ${request.capability} grant in scope ${request.scope}"
            )
        }

        val current = now()
        val active = candidates.filter { grant ->
            grant.expiresAt == null || current.isBefore(grant.expiresAt)
        }
        if (active.isEmpty()) {
            return AuthorityDelegationDecision.Denied(
                "delegator ${request.delegator} source grant is expired"
            )
        }

        val compatible = active.firstOrNull { source ->
            when {
                source.expiresAt == null -> true
                request.expiresAt == null -> false
                else -> !request.expiresAt.isAfter(source.expiresAt)
            }
        } ?: return AuthorityDelegationDecision.Denied(
            "delegated grant would outlive source grant"
        )

        return AuthorityDelegationDecision.Granted(
            DelegatedAuthorityGrant(
                delegator = request.delegator,
                principal = request.delegate,
                capability = compatible.capability,
                scope = compatible.scope,
                expiresAt = request.expiresAt
            )
        )
    }
}
