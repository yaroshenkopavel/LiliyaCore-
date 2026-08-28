package pro.liliya.core.capability

import pro.liliya.core.authority.CapabilityId

@JvmInline
value class CapabilityProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "capability provider id must not be blank" }
    }

    override fun toString(): String = value
}

data class CapabilityDescriptor(
    val id: CapabilityId,
    val providerId: CapabilityProviderId
)
