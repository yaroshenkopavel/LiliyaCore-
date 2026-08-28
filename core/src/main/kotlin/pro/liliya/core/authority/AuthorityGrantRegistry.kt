package pro.liliya.core.authority

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

interface AuthorityGrantRegistration {
    val grant: DirectAuthorityGrant
    fun revoke(context: LogContext): Boolean
}

sealed interface AuthorityGrantRegistrationResult {
    data class Registered(
        val registration: AuthorityGrantRegistration
    ) : AuthorityGrantRegistrationResult

    data class Rejected(
        val reason: String
    ) : AuthorityGrantRegistrationResult
}

class AuthorityGrantRegistry(
    private val observability: CoreObservability,
    private val now: () -> Instant = Instant::now
) {
    private data class GrantKey(
        val principal: AuthorityPrincipal,
        val capability: CapabilityId,
        val scope: AuthorityScope
    )

    private data class Entry(
        val token: Long,
        val grant: DirectAuthorityGrant
    )

    private val nextToken = AtomicLong(0)
    private val grants = ConcurrentHashMap<GrantKey, Entry>()

    fun register(
        grant: DirectAuthorityGrant,
        context: LogContext
    ): AuthorityGrantRegistrationResult {
        val current = now()
        if (grant.expiresAt != null && !current.isBefore(grant.expiresAt)) {
            val reason = "direct authority grant must expire after the current time"
            record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTHORITY_GRANT_REGISTRATION_REJECTED",
                message = reason,
                grant = grant,
                context = context
            )
            return AuthorityGrantRegistrationResult.Rejected(reason)
        }

        val key = grant.key()
        val entry = Entry(
            token = nextToken.incrementAndGet(),
            grant = grant
        )
        val previous = grants.putIfAbsent(key, entry)
        if (previous != null) {
            val reason = "direct authority grant already registered"
            record(
                severity = DiagnosticSeverity.WARNING,
                code = "AUTHORITY_GRANT_REGISTRATION_REJECTED",
                message = reason,
                grant = grant,
                context = context
            )
            return AuthorityGrantRegistrationResult.Rejected(reason)
        }

        record(
            severity = DiagnosticSeverity.INFO,
            code = "AUTHORITY_GRANT_REGISTERED",
            message = "direct authority grant registered",
            grant = grant,
            context = context
        )

        return AuthorityGrantRegistrationResult.Registered(
            registration = object : AuthorityGrantRegistration {
                override val grant: DirectAuthorityGrant = grant

                override fun revoke(context: LogContext): Boolean {
                    val removed = grants.remove(key, entry)
                    record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "AUTHORITY_GRANT_REVOKED"
                        } else {
                            "AUTHORITY_GRANT_REVOCATION_REJECTED"
                        },
                        message = if (removed) {
                            "direct authority grant revoked"
                        } else {
                            "direct authority grant registration is no longer current"
                        },
                        grant = grant,
                        context = context
                    )
                    return removed
                }
            }
        )
    }

    fun find(
        principal: AuthorityPrincipal,
        capability: CapabilityId,
        scope: AuthorityScope
    ): DirectAuthorityGrant? = grants[GrantKey(principal, capability, scope)]?.grant

    fun snapshot(): List<DirectAuthorityGrant> =
        grants.values
            .map { entry -> entry.grant }
            .sortedWith(
                compareBy<DirectAuthorityGrant>(
                    { it.principal.value },
                    { it.capability.value },
                    { it.scope.value },
                    { it.expiresAt?.toString() ?: "" }
                )
            )

    private fun DirectAuthorityGrant.key(): GrantKey = GrantKey(
        principal = principal,
        capability = capability,
        scope = scope
    )

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        grant: DirectAuthorityGrant,
        context: LogContext
    ) {
        observability.record(
            severity = severity,
            code = code,
            message = message,
            context = context,
            metadata = mapOf(
                "principal" to grant.principal.value,
                "capabilityId" to grant.capability.value,
                "scope" to grant.scope.value,
                "expiresAt" to (grant.expiresAt?.toString() ?: "unbounded"),
                "origin" to AuthorityGrantOrigin.DIRECT.name
            )
        )
    }
}
