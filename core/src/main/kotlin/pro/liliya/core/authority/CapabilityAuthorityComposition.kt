package pro.liliya.core.authority

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityRegistration
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
    private data class CapabilityGeneration(
        val token: Long,
        val descriptor: CapabilityDescriptor,
        val registration: CapabilityRegistration
    )

    private data class DirectGrantKey(
        val principal: AuthorityPrincipal,
        val capability: CapabilityId,
        val scope: AuthorityScope
    )

    private data class DirectSourceEntry(
        val token: Long,
        val capabilityToken: Long,
        val grant: DirectAuthorityGrant,
        val registration: AuthorityGrantRegistration
    )

    private data class DelegatedEntry(
        val token: Long,
        val capabilityToken: Long,
        val sourceToken: Long,
        val grant: DelegatedAuthorityGrant
    )

    private val ownershipLock = Any()
    private val capabilityRegistry = CapabilityRegistry(foundation.observability)
    private val directGrantRegistry = AuthorityGrantRegistry(
        observability = foundation.observability,
        now = now
    )

    private val nextCapabilityToken = AtomicLong(0)
    private val nextDirectSourceToken = AtomicLong(0)
    private val nextDelegationToken = AtomicLong(0)

    private val capabilityGenerations = ConcurrentHashMap<CapabilityId, CapabilityGeneration>()
    private val directSources = ConcurrentHashMap<DirectGrantKey, DirectSourceEntry>()
    private val delegatedGrants = ConcurrentHashMap<Long, DelegatedEntry>()

    fun registerCapability(descriptor: CapabilityDescriptor): CapabilityOwnershipResult = synchronized(ownershipLock) {
        val context = foundation.rootContext(
            operation = "register-capability",
            component = "CapabilityAuthority"
        )
        when (val result = capabilityRegistry.register(descriptor, context)) {
            is CapabilityRegistrationResult.Registered -> {
                val generation = CapabilityGeneration(
                    token = nextCapabilityToken.incrementAndGet(),
                    descriptor = descriptor,
                    registration = result.registration
                )
                capabilityGenerations[descriptor.id] = generation
                CapabilityOwnershipResult.Registered(
                    ownership = object : CapabilityOwnership {
                        override val descriptor: CapabilityDescriptor = descriptor

                        override fun unregister(): Boolean = synchronized(ownershipLock) {
                            val removed = generation.registration.unregister(
                                foundation.rootContext(
                                    operation = "unregister-capability",
                                    component = "CapabilityAuthority"
                                )
                            )
                            if (removed) {
                                capabilityGenerations.remove(descriptor.id, generation)
                                revokeDirectSourcesForCapabilityGeneration(generation)
                            }
                            removed
                        }
                    }
                )
            }

            is CapabilityRegistrationResult.Rejected -> CapabilityOwnershipResult.Rejected(result.reason)
        }
    }

    fun registerDirectGrant(grant: DirectAuthorityGrant): DirectAuthorityGrantOwnershipResult =
        synchronized(ownershipLock) {
            val capabilityGeneration = capabilityGenerations[grant.capability]
            if (capabilityGeneration == null) {
                val reason = "capability ${grant.capability} is not registered"
                recordDirectGrantRejected(grant, reason)
                return@synchronized DirectAuthorityGrantOwnershipResult.Rejected(reason)
            }

            when (
                val result = directGrantRegistry.register(
                    grant = grant,
                    context = foundation.rootContext(
                        operation = "register-direct-grant",
                        component = "CapabilityAuthority"
                    )
                )
            ) {
                is AuthorityGrantRegistrationResult.Registered -> {
                    val key = grant.key()
                    val entry = DirectSourceEntry(
                        token = nextDirectSourceToken.incrementAndGet(),
                        capabilityToken = capabilityGeneration.token,
                        grant = grant,
                        registration = result.registration
                    )
                    directSources[key] = entry
                    DirectAuthorityGrantOwnershipResult.Registered(
                        ownership = object : DirectAuthorityGrantOwnership {
                            override val grant: DirectAuthorityGrant = grant

                            override fun revoke(): Boolean = synchronized(ownershipLock) {
                                val removed = entry.registration.revoke(
                                    foundation.rootContext(
                                        operation = "revoke-direct-grant",
                                        component = "CapabilityAuthority"
                                    )
                                )
                                if (removed) {
                                    directSources.remove(key, entry)
                                }
                                removed
                            }
                        }
                    )
                }

                is AuthorityGrantRegistrationResult.Rejected ->
                    DirectAuthorityGrantOwnershipResult.Rejected(result.reason)
            }
        }

    fun authorize(request: AuthorityRequest): AuthorityDecision = synchronized(ownershipLock) {
        val capabilityGeneration = capabilityGenerations[request.capability]
        val policy = if (capabilityGeneration == null) {
            AuthorityPolicy {
                AuthorityDecision.Denied("capability ${request.capability} is not registered")
            }
        } else {
            ScopedGrantAuthorityPolicy(
                grants = activeAuthorizationGrants(),
                now = now
            )
        }
        AuthorityManager(
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

    fun delegate(request: AuthorityDelegationRequest): CapabilityAuthorityDelegationResult =
        synchronized(ownershipLock) {
            val capabilityGeneration = capabilityGenerations[request.capability]
            if (capabilityGeneration == null) {
                val reason = "capability ${request.capability} is not registered"
                recordDelegationDenied(request, reason)
                return@synchronized CapabilityAuthorityDelegationResult.Denied(reason)
            }

            val sourceKey = DirectGrantKey(
                principal = request.delegator,
                capability = request.capability,
                scope = request.scope
            )
            val sourceEntry = directSources[sourceKey]
                ?.takeIf { source ->
                    source.capabilityToken == capabilityGeneration.token &&
                        source.grant.isActiveAt(now())
                }

            val decision = AuthorityDelegationManager(
                policy = AuthorityDelegationPolicy(
                    sourceGrants = sourceEntry?.let { listOf(it.grant) }.orEmpty(),
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

            when (decision) {
                is AuthorityDelegationDecision.Denied ->
                    CapabilityAuthorityDelegationResult.Denied(decision.reason)

                is AuthorityDelegationDecision.Granted -> {
                    val exactSource = requireNotNull(sourceEntry)
                    val entry = DelegatedEntry(
                        token = nextDelegationToken.incrementAndGet(),
                        capabilityToken = capabilityGeneration.token,
                        sourceToken = exactSource.token,
                        grant = decision.grant
                    )
                    delegatedGrants[entry.token] = entry
                    CapabilityAuthorityDelegationResult.Granted(
                        grant = entry.grant,
                        ownership = object : DelegatedAuthorityGrantOwnership {
                            override val grant: DelegatedAuthorityGrant = entry.grant

                            override fun revoke(): Boolean = synchronized(ownershipLock) {
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
                                removed
                            }
                        }
                    )
                }
            }
        }

    fun findCapability(id: CapabilityId): CapabilityDescriptor? = synchronized(ownershipLock) {
        capabilityGenerations[id]?.descriptor
    }

    fun capabilitySnapshot(): Map<CapabilityId, CapabilityDescriptor> = synchronized(ownershipLock) {
        capabilityGenerations.mapValues { (_, generation) -> generation.descriptor }
    }

    fun directGrantSnapshot(): List<DirectAuthorityGrant> = synchronized(ownershipLock) {
        activeDirectGrants()
    }

    private fun activeAuthorizationGrants(): List<ScopedAuthorityGrant> {
        val current = now()
        val directEntries = activeDirectSourceEntries(current)
        val directScoped = directEntries.map { source -> source.grant.asScopedGrant() }
        val delegatedScoped = delegatedGrants.values
            .filter { entry -> entry.grant.isActiveAt(current) }
            .filter { entry ->
                val capabilityGeneration = capabilityGenerations[entry.grant.capability]
                if (capabilityGeneration?.token != entry.capabilityToken) {
                    false
                } else {
                    val key = DirectGrantKey(
                        principal = entry.grant.delegator,
                        capability = entry.grant.capability,
                        scope = entry.grant.scope
                    )
                    val source = directSources[key]
                    source?.token == entry.sourceToken &&
                        source.capabilityToken == entry.capabilityToken &&
                        source.grant.isActiveAt(current)
                }
            }
            .map { entry -> entry.grant.asScopedGrant() }
        return directScoped + delegatedScoped
    }

    private fun activeDirectGrants(): List<DirectAuthorityGrant> =
        activeDirectSourceEntries(now())
            .map { source -> source.grant }
            .sortedWith(
                compareBy<DirectAuthorityGrant>(
                    { it.principal.value },
                    { it.capability.value },
                    { it.scope.value },
                    { it.expiresAt?.toString() ?: "" }
                )
            )

    private fun activeDirectSourceEntries(current: Instant): List<DirectSourceEntry> =
        directSources.values.filter { source ->
            capabilityGenerations[source.grant.capability]?.token == source.capabilityToken &&
                source.grant.isActiveAt(current)
        }

    private fun revokeDirectSourcesForCapabilityGeneration(generation: CapabilityGeneration) {
        directSources.entries
            .filter { (_, source) -> source.capabilityToken == generation.token }
            .forEach { (key, source) ->
                val removed = source.registration.revoke(
                    foundation.rootContext(
                        operation = "revoke-direct-grant-on-capability-unregister",
                        component = "CapabilityAuthority"
                    )
                )
                if (removed) {
                    directSources.remove(key, source)
                }
            }
    }

    private fun recordDirectGrantRejected(grant: DirectAuthorityGrant, reason: String) {
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

    private fun DirectAuthorityGrant.key(): DirectGrantKey = DirectGrantKey(
        principal = principal,
        capability = capability,
        scope = scope
    )

    private fun DirectAuthorityGrant.isActiveAt(current: Instant): Boolean =
        expiresAt == null || current.isBefore(expiresAt)

    private fun DelegatedAuthorityGrant.isActiveAt(current: Instant): Boolean =
        expiresAt == null || current.isBefore(expiresAt)

    private fun delegationMetadata(grant: DelegatedAuthorityGrant): Map<String, String> = mapOf(
        "delegator" to grant.delegator.value,
        "delegate" to grant.principal.value,
        "capabilityId" to grant.capability.value,
        "scope" to grant.scope.value,
        "expiresAt" to (grant.expiresAt?.toString() ?: "unbounded")
    )
}
