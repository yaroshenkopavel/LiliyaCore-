package pro.liliya.core.authority

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityRegistrationResult
import pro.liliya.core.capability.CapabilityRegistry
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

interface CapabilityOwnership {
    val descriptor: CapabilityDescriptor
    fun unregister(): Boolean
}

sealed interface CapabilityOwnershipResult {
    data class Registered(val ownership: CapabilityOwnership) : CapabilityOwnershipResult
    data class Rejected(val reason: String) : CapabilityOwnershipResult
}

interface DirectAuthorityGrantOwnership {
    val grant: DirectAuthorityGrant
    fun revoke(): Boolean
}

sealed interface DirectAuthorityGrantOwnershipResult {
    data class Registered(val ownership: DirectAuthorityGrantOwnership) : DirectAuthorityGrantOwnershipResult
    data class Rejected(val reason: String) : DirectAuthorityGrantOwnershipResult
}

interface DelegatedAuthorityGrantOwnership {
    val grant: DelegatedAuthorityGrant
    fun revoke(): Boolean
}

sealed interface CapabilityAuthorityDelegationResult {
    data class Granted(
        val grant: DelegatedAuthorityGrant,
        val ownership: DelegatedAuthorityGrantOwnership
    ) : CapabilityAuthorityDelegationResult

    data class Denied(val reason: String) : CapabilityAuthorityDelegationResult
}

class CapabilityAuthorityComposition(
    private val foundation: FoundationComposition,
    private val now: () -> Instant = Instant::now
) {
    private data class DelegatedEntry(
        val token: Long,
        val grant: DelegatedAuthorityGrant
    )

    private val capabilityRegistry = CapabilityRegistry(foundation.observability)
    private val directGrantRegistry = AuthorityGrantRegistry(
        observability = foundation.observability,
        now = now
    )
    private val nextDelegationToken = AtomicLong(0)
    private val delegatedGrants = ConcurrentHashMap<Long, DelegatedEntry>()

    fun registerCapability(descriptor: CapabilityDescriptor): CapabilityOwnershipResult {
        val context = foundation.rootContext(
            operation = "register-capability",
            component = "CapabilityAuthority"
        )
        return when (val result = capabilityRegistry.register(descriptor, context)) {
            is CapabilityRegistrationResult.Registered -> CapabilityOwnershipResult.Registered(
                ownership = object : CapabilityOwnership {
                    override val descriptor: CapabilityDescriptor = descriptor

                    override fun unregister(): Boolean = result.registration.unregister(
                        foundation.rootContext(
                            operation = "unregister-capability",
                            component = "CapabilityAuthority"
                        )
                    )
                }
            )

            is CapabilityRegistrationResult.Rejected -> CapabilityOwnershipResult.Rejected(result.reason)
        }
    }

    fun registerDirectGrant(grant: DirectAuthorityGrant): DirectAuthorityGrantOwnershipResult {
        if (!capabilityRegistry.contains(grant.capability)) {
            val reason = "capability ${grant.capability} is not registered"
            foundation.observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTHORITY_GRANT_REGISTRATION_REJECTED",
                message = reason,
                context = foundation.rootContext(
                    operation = "register-direct-grant",
                    component = "CapabilityAuthority"
                ),
                metadata = mapOf(
                    "principal" to grant.principal.value,
                    "capabilityId" to grant.capability.value,
                    "scope" to grant.scope.value,
                    "denialReason" to reason
                )
            )
            return DirectAuthorityGrantOwnershipResult.Rejected(reason)
        }

        val result = directGrantRegistry.register(
            grant = grant,
            context = foundation.rootContext(
                operation = "register-direct-grant",
                component = "CapabilityAuthority"
            )
        )
        return when (result) {
            is AuthorityGrantRegistrationResult.Registered -> DirectAuthorityGrantOwnershipResult.Registered(
                ownership = object : DirectAuthorityGrantOwnership {
                    override val grant: DirectAuthorityGrant = grant

                    override fun revoke(): Boolean = result.registration.revoke(
                        foundation.rootContext(
                            operation = "revoke-direct-grant",
                            component = "CapabilityAuthority"
                        )
                    )
                }
            )

            is AuthorityGrantRegistrationResult.Rejected -> DirectAuthorityGrantOwnershipResult.Rejected(result.reason)
        }
    }

    fun authorize(request: AuthorityRequest): AuthorityDecision {
        val registered = capabilityRegistry.contains(request.capability)
        val policy = if (!registered) {
            AuthorityPolicy {
                AuthorityDecision.Denied("capability ${request.capability} is not registered")
            }
        } else {
            ScopedGrantAuthorityPolicy(
                grants = activeAuthorizationGrants(),
                now = now
            )
        }
        return AuthorityManager(
            policy = policy,
            observability = foundation.observability
        ).authorize(
            request = request,
            context = foundation.rootContext(
                operation = "authorize",
                component = "CapabilityAuthority"
            )
        )
    }

    fun delegate(request: AuthorityDelegationRequest): CapabilityAuthorityDelegationResult {
        if (!capabilityRegistry.contains(request.capability)) {
            val reason = "capability ${request.capability} is not registered"
            recordDelegationDenied(request, reason)
            return CapabilityAuthorityDelegationResult.Denied(reason)
        }

        val decision = AuthorityDelegationManager(
            policy = AuthorityDelegationPolicy(
                sourceGrants = directGrantRegistry.snapshot(),
                now = now
            ),
            observability = foundation.observability
        ).delegate(
            request = request,
            context = foundation.rootContext(
                operation = "delegate",
                component = "CapabilityAuthority"
            )
        )

        return when (decision) {
            is AuthorityDelegationDecision.Denied ->
                CapabilityAuthorityDelegationResult.Denied(decision.reason)

            is AuthorityDelegationDecision.Granted -> {
                val entry = DelegatedEntry(
                    token = nextDelegationToken.incrementAndGet(),
                    grant = decision.grant
                )
                delegatedGrants[entry.token] = entry
                CapabilityAuthorityDelegationResult.Granted(
                    grant = entry.grant,
                    ownership = object : DelegatedAuthorityGrantOwnership {
                        override val grant: DelegatedAuthorityGrant = entry.grant

                        override fun revoke(): Boolean {
                            val removed = delegatedGrants.remove(entry.token, entry)
                            foundation.observability.record(
                                severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                                code = if (removed) {
                                    "AUTHORITY_DELEGATION_REVOKED"
                                } else {
                                    "AUTHORITY_DELEGATION_REVOCATION_REJECTED"
                                },
                                message = if (removed) {
                                    "delegated authority grant revoked"
                                } else {
                                    "delegated authority grant registration is no longer current"
                                },
                                context = foundation.rootContext(
                                    operation = "revoke-delegation",
                                    component = "CapabilityAuthority"
                                ),
                                metadata = delegationMetadata(entry.grant)
                            )
                            return removed
                        }
                    }
                )
            }
        }
    }

    fun findCapability(id: CapabilityId): CapabilityDescriptor? = capabilityRegistry.find(id)

    fun capabilitySnapshot(): Map<CapabilityId, CapabilityDescriptor> = capabilityRegistry.snapshot()

    fun directGrantSnapshot(): List<DirectAuthorityGrant> = directGrantRegistry.snapshot()

    private fun activeAuthorizationGrants(): List<ScopedAuthorityGrant> {
        val direct = directGrantRegistry.snapshot()
        val directScoped = direct.map { grant -> grant.asScopedGrant() }
        val current = now()
        val delegatedScoped = delegatedGrants.values
            .map { entry -> entry.grant }
            .filter { grant -> grant.expiresAt == null || current.isBefore(grant.expiresAt) }
            .filter { grant ->
                direct.any { source ->
                    source.principal == grant.delegator &&
                        source.capability == grant.capability &&
                        source.scope == grant.scope
                }
            }
            .map { grant -> grant.asScopedGrant() }
        return directScoped + delegatedScoped
    }

    private fun recordDelegationDenied(
        request: AuthorityDelegationRequest,
        reason: String
    ) {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AUTHORITY_DELEGATION_DENIED",
            message = reason,
            context = foundation.rootContext(
                operation = "delegate",
                component = "CapabilityAuthority"
            ),
            metadata = mapOf(
                "delegator" to request.delegator.value,
                "delegate" to request.delegate.value,
                "capabilityId" to request.capability.value,
                "scope" to request.scope.value,
                "reason" to request.reason,
                "denialReason" to reason
            )
        )
    }

    private fun delegationMetadata(grant: DelegatedAuthorityGrant): Map<String, String> = mapOf(
        "delegator" to grant.delegator.value,
        "delegate" to grant.principal.value,
        "capabilityId" to grant.capability.value,
        "scope" to grant.scope.value,
        "expiresAt" to (grant.expiresAt?.toString() ?: "unbounded")
    )
}
