package pro.liliya.core.authority

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorityGrantRegistryContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val observability: CoreObservability
    )

    private val now = Instant.parse("2026-08-28T18:00:00Z")
    private val principal = AuthorityPrincipal("planner")
    private val capability = CapabilityId("device.launch")
    private val scope = AuthorityScope("app:maps")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        return Fixture(
            logs = logs,
            diagnostics = diagnostics,
            observability = CoreObservability(
                loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
                diagnostics = DiagnosticRecorder(diagnostics)
            )
        )
    }

    @Test
    fun register_find_snapshot_and_revoke_are_observable() {
        val f = fixture()
        val registry = AuthorityGrantRegistry(f.observability, now = { now })
        val grant = DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(60))
        val context = context("grant-lifecycle")

        val registration = assertIs<AuthorityGrantRegistrationResult.Registered>(
            registry.register(grant, context)
        ).registration

        assertEquals(grant, registry.find(principal, capability, scope))
        assertEquals(listOf(grant), registry.snapshot())
        assertTrue(registration.revoke(context))
        assertNull(registry.find(principal, capability, scope))
        assertEquals(emptyList(), registry.snapshot())
        assertEquals(
            listOf("AUTHORITY_GRANT_REGISTERED", "AUTHORITY_GRANT_REVOKED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("AUTHORITY_GRANT_REGISTERED", "AUTHORITY_GRANT_REVOKED"),
            f.diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun exact_tuple_duplicate_is_rejected_but_different_scope_is_independent() {
        val f = fixture()
        val registry = AuthorityGrantRegistry(f.observability, now = { now })
        val context = context("grant-duplicate")
        val first = DirectAuthorityGrant(principal, capability, scope)
        val duplicate = DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(60))
        val otherScope = DirectAuthorityGrant(principal, capability, AuthorityScope("app:browser"))

        assertIs<AuthorityGrantRegistrationResult.Registered>(registry.register(first, context))
        assertIs<AuthorityGrantRegistrationResult.Rejected>(registry.register(duplicate, context))
        assertIs<AuthorityGrantRegistrationResult.Registered>(registry.register(otherScope, context))

        assertEquals(first, registry.find(principal, capability, scope))
        assertEquals(2, registry.snapshot().size)
    }

    @Test
    fun stale_registration_cannot_revoke_replacement_grant() {
        val f = fixture()
        val registry = AuthorityGrantRegistry(f.observability, now = { now })
        val context = context("grant-stale")
        val first = assertIs<AuthorityGrantRegistrationResult.Registered>(
            registry.register(DirectAuthorityGrant(principal, capability, scope), context)
        ).registration

        assertTrue(first.revoke(context))

        val replacement = DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(120))
        assertIs<AuthorityGrantRegistrationResult.Registered>(registry.register(replacement, context))

        assertEquals(false, first.revoke(context))
        assertEquals(replacement, registry.find(principal, capability, scope))
        assertEquals("AUTHORITY_GRANT_REVOCATION_REJECTED", f.logs.snapshot().last().marker)
    }

    @Test
    fun expired_or_exact_now_direct_grants_are_rejected_at_registration() {
        val f = fixture()
        val registry = AuthorityGrantRegistry(f.observability, now = { now })
        val context = context("grant-expiry")

        assertIs<AuthorityGrantRegistrationResult.Rejected>(
            registry.register(
                DirectAuthorityGrant(principal, capability, scope, now.minusSeconds(1)),
                context
            )
        )
        assertIs<AuthorityGrantRegistrationResult.Rejected>(
            registry.register(
                DirectAuthorityGrant(principal, capability, scope, now),
                context
            )
        )
        assertIs<AuthorityGrantRegistrationResult.Registered>(
            registry.register(
                DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(1)),
                context
            )
        )
    }

    @Test
    fun expired_grant_is_hidden_and_can_be_atomically_replaced() {
        val f = fixture()
        var current = now
        val registry = AuthorityGrantRegistry(f.observability, now = { current })
        val context = context("grant-expired-replacement")
        val firstGrant = DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(1))
        val first = assertIs<AuthorityGrantRegistrationResult.Registered>(
            registry.register(firstGrant, context)
        ).registration

        current = now.plusSeconds(1)
        assertNull(registry.find(principal, capability, scope))
        assertEquals(emptyList(), registry.snapshot())

        val replacement = DirectAuthorityGrant(principal, capability, scope, current.plusSeconds(60))
        assertIs<AuthorityGrantRegistrationResult.Registered>(
            registry.register(replacement, context)
        )

        assertEquals(replacement, registry.find(principal, capability, scope))
        assertEquals(listOf(replacement), registry.snapshot())
        assertEquals(false, first.revoke(context))
        assertEquals(replacement, registry.find(principal, capability, scope))
    }

    @Test
    fun concurrent_registration_of_same_tuple_has_exactly_one_owner() {
        val f = fixture()
        val registry = AuthorityGrantRegistry(f.observability, now = { now })
        val workers = 32
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)

        val futures = (0 until workers).map { index ->
            pool.submit<AuthorityGrantRegistrationResult> {
                ready.countDown()
                start.await()
                registry.register(
                    DirectAuthorityGrant(principal, capability, scope, now.plusSeconds(60 + index.toLong())),
                    context("grant-concurrent-$index")
                )
            }
        }

        ready.await()
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()

        assertEquals(1, results.count { it is AuthorityGrantRegistrationResult.Registered })
        assertEquals(workers - 1, results.count { it is AuthorityGrantRegistrationResult.Rejected })
        assertEquals(1, registry.snapshot().size)
    }

    private fun context(correlationId: String) = LogContextPropagation.root(
        module = "CORE",
        component = "AuthorityGrantRegistry",
        operation = "contract",
        generator = CorrelationIdGenerator { correlationId }
    )
}
