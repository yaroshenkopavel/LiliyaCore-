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

class CapabilityAuthorityFoundationContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val observability: CoreObservability
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, observability)
    }

    @Test
    fun capability_principal_scope_and_reason_require_explicit_identity() {
        assertFailsWith<IllegalArgumentException> { CapabilityId(" ") }
        assertFailsWith<IllegalArgumentException> { AuthorityPrincipal("") }
        assertFailsWith<IllegalArgumentException> { AuthorityScope(" ") }
        assertFailsWith<IllegalArgumentException> {
            AuthorityRequest(AuthorityPrincipal("core"), CapabilityId("memory.read"), " ")
        }
    }

    @Test
    fun explicit_grant_policy_is_default_deny() {
        val principal = AuthorityPrincipal("planner")
        val requested = CapabilityId("device.launch")
        val policy = ExplicitGrantAuthorityPolicy()

        val decision = policy.decide(
            AuthorityRequest(principal, requested, "launch application")
        )

        assertIs<AuthorityDecision.Denied>(decision)
    }

    @Test
    fun explicit_grant_allows_only_the_declared_principal_capability_pair() {
        val planner = AuthorityPrincipal("planner")
        val memoryRead = CapabilityId("memory.read")
        val policy = ExplicitGrantAuthorityPolicy(
            mapOf(planner to setOf(memoryRead))
        )

        assertEquals(
            AuthorityDecision.Granted,
            policy.decide(AuthorityRequest(planner, memoryRead, "retrieve relevant memory"))
        )
        assertIs<AuthorityDecision.Denied>(
            policy.decide(
                AuthorityRequest(planner, CapabilityId("memory.write"), "persist memory")
            )
        )
    }

    @Test
    fun scoped_grant_requires_exact_scope() {
        val principal = AuthorityPrincipal("planner")
        val capability = CapabilityId("memory.read")
        val conversation = AuthorityScope("conversation:42")
        val policy = ScopedGrantAuthorityPolicy(
            grants = listOf(ScopedAuthorityGrant(principal, capability, conversation)),
            now = { Instant.parse("2026-08-28T16:00:00Z") }
        )

        assertEquals(
            AuthorityDecision.Granted,
            policy.decide(AuthorityRequest(principal, capability, "read conversation memory", conversation))
        )
        assertIs<AuthorityDecision.Denied>(
            policy.decide(
                AuthorityRequest(
                    principal,
                    capability,
                    "read another conversation memory",
                    AuthorityScope("conversation:43")
                )
            )
        )
    }

    @Test
    fun scoped_grant_is_valid_only_before_expiry() {
        val principal = AuthorityPrincipal("executor")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")
        val expiry = Instant.parse("2026-08-28T17:00:00Z")
        val grant = ScopedAuthorityGrant(principal, capability, scope, expiresAt = expiry)

        assertEquals(
            AuthorityDecision.Granted,
            ScopedGrantAuthorityPolicy(listOf(grant)) { Instant.parse("2026-08-28T16:59:59Z") }
                .decide(AuthorityRequest(principal, capability, "launch maps", scope))
        )
        assertIs<AuthorityDecision.Denied>(
            ScopedGrantAuthorityPolicy(listOf(grant)) { expiry }
                .decide(AuthorityRequest(principal, capability, "launch maps", scope))
        )
        assertIs<AuthorityDecision.Denied>(
            ScopedGrantAuthorityPolicy(listOf(grant)) { Instant.parse("2026-08-28T17:00:01Z") }
                .decide(AuthorityRequest(principal, capability, "launch maps", scope))
        )
    }

    @Test
    fun authority_decision_is_observable_in_logs_and_diagnostics_with_one_correlation() {
        val f = fixture()
        val principal = AuthorityPrincipal("executor")
        val capability = CapabilityId("device.launch")
        val scope = AuthorityScope("app:maps")
        val manager = AuthorityManager(
            policy = ScopedGrantAuthorityPolicy(
                listOf(ScopedAuthorityGrant(principal, capability, scope))
            ),
            observability = f.observability
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Authority",
            operation = "authorize",
            generator = CorrelationIdGenerator { "authority-contract" }
        )

        assertEquals(
            AuthorityDecision.Granted,
            manager.authorize(
                AuthorityRequest(principal, capability, "launch user-selected application", scope),
                context
            )
        )
        assertEquals(listOf("AUTHORITY_GRANTED"), f.logs.snapshot().map { it.marker })
        assertEquals(listOf("AUTHORITY_GRANTED"), f.diagnostics.snapshot().map { it.code })
        assertEquals("app:maps", f.logs.snapshot().single().context.metadata["scope"])
        assertEquals("app:maps", f.diagnostics.snapshot().single().context.metadata["scope"])
        assertEquals(
            setOf("authority-contract"),
            (f.logs.snapshot().map { it.context.correlationId } +
                f.diagnostics.snapshot().map { it.context.correlationId }).toSet()
        )
    }
}
