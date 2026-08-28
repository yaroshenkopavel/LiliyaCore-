package pro.liliya.core.authority

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthorityDelegationContractTest {
    private val delegator = AuthorityPrincipal("planner")
    private val delegate = AuthorityPrincipal("executor")
    private val capability = CapabilityId("device.launch")
    private val scope = AuthorityScope("app:maps")
    private val now = Instant.parse("2026-08-28T16:00:00Z")

    @Test
    fun delegation_requires_distinct_principals_and_reason() {
        assertFailsWith<IllegalArgumentException> {
            AuthorityDelegationRequest(delegator, delegator, capability, scope, "delegate")
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorityDelegationRequest(delegator, delegate, capability, scope, " ")
        }
    }

    @Test
    fun delegation_is_denied_without_exact_active_source_grant() {
        val policy = AuthorityDelegationPolicy(
            sourceGrants = listOf(
                DirectAuthorityGrant(
                    principal = delegator,
                    capability = capability,
                    scope = AuthorityScope("app:browser")
                )
            ),
            now = { now }
        )

        assertIs<AuthorityDelegationDecision.Denied>(
            policy.decide(
                AuthorityDelegationRequest(delegator, delegate, capability, scope, "launch maps")
            )
        )
    }

    @Test
    fun delegated_expiry_cannot_outlive_source_grant() {
        val sourceExpiry = now.plusSeconds(60)
        val policy = AuthorityDelegationPolicy(
            sourceGrants = listOf(
                DirectAuthorityGrant(delegator, capability, scope, sourceExpiry)
            ),
            now = { now }
        )

        assertIs<AuthorityDelegationDecision.Denied>(
            policy.decide(
                AuthorityDelegationRequest(
                    delegator = delegator,
                    delegate = delegate,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps",
                    expiresAt = sourceExpiry.plusSeconds(1)
                )
            )
        )
        assertIs<AuthorityDelegationDecision.Denied>(
            policy.decide(
                AuthorityDelegationRequest(
                    delegator = delegator,
                    delegate = delegate,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps"
                )
            )
        )
    }

    @Test
    fun delegated_expiry_must_be_strictly_in_the_future() {
        val policy = AuthorityDelegationPolicy(
            sourceGrants = listOf(DirectAuthorityGrant(delegator, capability, scope)),
            now = { now }
        )

        assertIs<AuthorityDelegationDecision.Denied>(
            policy.decide(
                AuthorityDelegationRequest(
                    delegator = delegator,
                    delegate = delegate,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps",
                    expiresAt = now
                )
            )
        )
        assertIs<AuthorityDelegationDecision.Denied>(
            policy.decide(
                AuthorityDelegationRequest(
                    delegator = delegator,
                    delegate = delegate,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps",
                    expiresAt = now.minusSeconds(1)
                )
            )
        )
        assertIs<AuthorityDelegationDecision.Granted>(
            policy.decide(
                AuthorityDelegationRequest(
                    delegator = delegator,
                    delegate = delegate,
                    capability = capability,
                    scope = scope,
                    reason = "launch maps",
                    expiresAt = now.plusSeconds(1)
                )
            )
        )
    }

    @Test
    fun bounded_delegation_can_be_used_as_scoped_grant_without_becoming_delegation_source() {
        val expiry = now.plusSeconds(30)
        val decision = AuthorityDelegationPolicy(
            sourceGrants = listOf(
                DirectAuthorityGrant(delegator, capability, scope, now.plusSeconds(60))
            ),
            now = { now }
        ).decide(
            AuthorityDelegationRequest(
                delegator = delegator,
                delegate = delegate,
                capability = capability,
                scope = scope,
                reason = "launch maps",
                expiresAt = expiry
            )
        )

        val granted = assertIs<AuthorityDelegationDecision.Granted>(decision)
        assertEquals(delegate, granted.grant.principal)
        assertEquals(expiry, granted.grant.expiresAt)
        assertEquals(AuthorityGrantOrigin.DELEGATED, granted.grant.asScopedGrant().origin)
        assertEquals(
            AuthorityDecision.Granted,
            ScopedGrantAuthorityPolicy(
                grants = listOf(granted.grant.asScopedGrant()),
                now = { now }
            ).decide(
                AuthorityRequest(delegate, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun delegation_decision_is_observable_with_one_correlation() {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        val manager = AuthorityDelegationManager(
            policy = AuthorityDelegationPolicy(
                sourceGrants = listOf(DirectAuthorityGrant(delegator, capability, scope)),
                now = { now }
            ),
            observability = observability
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Authority",
            operation = "delegate",
            generator = CorrelationIdGenerator { "authority-delegation" }
        )

        assertIs<AuthorityDelegationDecision.Granted>(
            manager.delegate(
                AuthorityDelegationRequest(delegator, delegate, capability, scope, "launch maps"),
                context
            )
        )
        assertEquals(listOf("AUTHORITY_DELEGATION_GRANTED"), logs.snapshot().map { it.marker })
        assertEquals(listOf("AUTHORITY_DELEGATION_GRANTED"), diagnostics.snapshot().map { it.code })
        assertEquals("app:maps", logs.snapshot().single().context.metadata["scope"])
        assertEquals(
            setOf("authority-delegation"),
            (logs.snapshot().map { it.context.correlationId } +
                diagnostics.snapshot().map { it.context.correlationId }).toSet()
        )
    }
}
