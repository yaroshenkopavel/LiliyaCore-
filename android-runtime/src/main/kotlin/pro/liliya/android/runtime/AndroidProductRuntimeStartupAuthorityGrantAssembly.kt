package pro.liliya.android.runtime

import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnership
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnership
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor

/**
 * Explicit startup authority plan. This plan contains only caller-approved capabilities/grants.
 *
 * Authority Plan != Grant Minting Policy.
 * Authority Plan != Capability Discovery.
 */
data class AndroidProductRuntimeStartupAuthorityPlan(
    val capabilities: List<CapabilityDescriptor>,
    val directGrants: List<DirectAuthorityGrant>
)

data class AndroidProductRuntimeStartupAuthorityOwnership(
    val authority: CapabilityAuthorityComposition,
    val capabilities: List<CapabilityOwnership>,
    val directGrants: List<DirectAuthorityGrantOwnership>
)

enum class AndroidProductRuntimeStartupAuthorityAssemblyFailure {
    CAPABILITY_REJECTED,
    DIRECT_GRANT_REJECTED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeStartupAuthorityAssemblyResult {
    data class Ready(
        val ownership: AndroidProductRuntimeStartupAuthorityOwnership
    ) : AndroidProductRuntimeStartupAuthorityAssemblyResult

    data class Rejected(
        val reason: AndroidProductRuntimeStartupAuthorityAssemblyFailure
    ) : AndroidProductRuntimeStartupAuthorityAssemblyResult
}

internal fun interface AndroidProductRuntimeCapabilityRegisterPort {
    fun register(descriptor: CapabilityDescriptor): CapabilityOwnershipResult
}

internal fun interface AndroidProductRuntimeDirectGrantRegisterPort {
    fun register(grant: DirectAuthorityGrant): DirectAuthorityGrantOwnershipResult
}

/**
 * Registers only the exact startup authority plan supplied by the product owner.
 *
 * Startup Authority Assembly != Capability Grant Authority.
 * Startup Authority Assembly != Admission Authority.
 * Startup Authority Assembly != Delegation Engine.
 */
object AndroidProductRuntimeStartupAuthorityGrantAssembly {
    fun install(
        authority: CapabilityAuthorityComposition,
        plan: AndroidProductRuntimeStartupAuthorityPlan
    ): AndroidProductRuntimeStartupAuthorityAssemblyResult =
        install(
            authority = authority,
            plan = plan,
            capabilityRegister = AndroidProductRuntimeCapabilityRegisterPort(authority::registerCapability),
            directGrantRegister = AndroidProductRuntimeDirectGrantRegisterPort(authority::registerDirectGrant)
        )

    internal fun install(
        authority: CapabilityAuthorityComposition,
        plan: AndroidProductRuntimeStartupAuthorityPlan,
        capabilityRegister: AndroidProductRuntimeCapabilityRegisterPort,
        directGrantRegister: AndroidProductRuntimeDirectGrantRegisterPort
    ): AndroidProductRuntimeStartupAuthorityAssemblyResult {
        val capabilityOwnership = mutableListOf<CapabilityOwnership>()
        val grantOwnership = mutableListOf<DirectAuthorityGrantOwnership>()

        fun rollback() {
            grantOwnership.asReversed().forEach { ownership ->
                runCatching { ownership.revoke() }
            }
            capabilityOwnership.asReversed().forEach { ownership ->
                runCatching { ownership.unregister() }
            }
        }

        try {
            for (descriptor in plan.capabilities) {
                when (val result = capabilityRegister.register(descriptor)) {
                    is CapabilityOwnershipResult.Registered ->
                        capabilityOwnership += result.ownership
                    is CapabilityOwnershipResult.Rejected -> {
                        rollback()
                        return AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected(
                            AndroidProductRuntimeStartupAuthorityAssemblyFailure.CAPABILITY_REJECTED
                        )
                    }
                }
            }

            for (grant in plan.directGrants) {
                when (val result = directGrantRegister.register(grant)) {
                    is DirectAuthorityGrantOwnershipResult.Registered ->
                        grantOwnership += result.ownership
                    is DirectAuthorityGrantOwnershipResult.Rejected -> {
                        rollback()
                        return AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected(
                            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED
                        )
                    }
                }
            }
        } catch (_: Exception) {
            rollback()
            return AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected(
                AndroidProductRuntimeStartupAuthorityAssemblyFailure.INTERNAL_FAILURE
            )
        }

        return AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready(
            AndroidProductRuntimeStartupAuthorityOwnership(
                authority = authority,
                capabilities = capabilityOwnership.toList(),
                directGrants = grantOwnership.toList()
            )
        )
    }
}
