package pro.liliya.core.authority

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
        if (request.capability in grants[request.principal].orEmpty()) {
            AuthorityDecision.Granted
        } else {
            AuthorityDecision.Denied(
                "capability ${request.capability} is not granted to ${request.principal}"
            )
        }
}
