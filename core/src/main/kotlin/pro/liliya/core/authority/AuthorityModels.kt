package pro.liliya.core.authority

@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "capability id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class AuthorityPrincipal(val value: String) {
    init {
        require(value.isNotBlank()) { "authority principal must not be blank" }
    }

    override fun toString(): String = value
}

data class AuthorityRequest(
    val principal: AuthorityPrincipal,
    val capability: CapabilityId,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "authority reason must not be blank" }
    }
}
