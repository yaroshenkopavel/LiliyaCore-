package pro.liliya.core.authority

import java.time.Instant

sealed interface AuthorityDecision {
    data object Granted : AuthorityDecision
    data class Denied(val reason: String) : AuthorityDecision {
        init {
            require(reason.isNotBlank()) { "denial reason must not be blank" }
        }
    }
}

fun interface AuthorityPolicy {
    fun decide(request: AuthorityRequest): AuthorityDecision
}

class ExplicitGrantAuthorityPolicy(
    grants: Map<AuthorityPrincipal, Set<CapabilityId>> = emptyMap()
) : AuthorityPolicy {
    private val grants = grants.mapValues { (_, capabilities) -> capabilities.toSet() }.toMap()

    override fun decide(request: AuthorityRequest): AuthorityDecision =
        if (
            request.scope == AuthorityScope.GLOBAL &&
            request.capability in grants[request.principal].orEmpty()
        ) {
            AuthorityDecision.Granted
        } else {
            AuthorityDecision.Denied(
                "capability ${request.capability} is not granted to ${request.principal} in scope ${request.scope}"
            )
        }
}

enum class AuthorityGrantOrigin {
    DIRECT,
    DELEGATED
}

data class ScopedAuthorityGrant(
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val scope: AuthorityScope,
    val expiresAt: Instant? = null,
    val origin: AuthorityGrantOrigin = AuthorityGrantOrigin.DIRECT
)

class ScopedGrantAuthorityPolicy(
    grants: Collection<ScopedAuthorityGrant> = emptyList(),
    private val now: () -> Instant = Instant::now
) : AuthorityPolicy {
    private val grants = grants.toList()

    override fun decide(request: AuthorityRequest): AuthorityDecision {
        val matching = grants.filter { grant ->
            grant.principal == request.principal &&
                grant.capability == request.capability &&
                grant.scope == request.scope
        }

        if (matching.isEmpty()) {
            return AuthorityDecision.Denied(
                "capability ${request.capability} is not granted to ${request.principal} in scope ${request.scope}"
            )
        }

        val current = now()
        if (matching.any { grant -> grant.expiresAt == null || current.isBefore(grant.expiresAt) }) {
            return AuthorityDecision.Granted
        }

        return AuthorityDecision.Denied(
            "capability ${request.capability} grant expired for ${request.principal} in scope ${request.scope}"
        )
    }
}
