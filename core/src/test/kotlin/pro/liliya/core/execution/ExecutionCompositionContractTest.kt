package pro.liliya.core.execution

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityDelegationRequest
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityAuthorityDelegationResult
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val foundation: FoundationComposition,
        val authority: CapabilityAuthorityComposition
    )

    private val now = Instant.parse("2026-08-28T20:00:00Z")
    private val capability = CapabilityId("device.launch")
    private val provider = CapabilityProviderId("android.intent")
    private val planner = AuthorityPrincipal("planner")
    private val executorPrincipal = AuthorityPrincipal("executor")
    private val scope = AuthorityScope("app:maps")
    private val actionId = ExecutionActionId("launch.maps")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "execution-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            diagnostics = diagnostics,
            foundation = foundation,
            authority = CapabilityAuthorityComposition(
                foundation = foundation,
                now = { now }
            )
        )
    }

    private fun request(principal: AuthorityPrincipal = planner) = ExecutionRequest(
        principal = principal,
        capability = capability,
        scope = scope,
        actionId = actionId,
        reason = "launch maps"
    )

    @Test
    fun capability_presence_without_grant_never_reaches_executor() {
        val f = fixture()
        f.authority.registerCapability(CapabilityDescriptor(capability, provider))
        var calls = 0
        val composition = composition(f) { _, _ ->
            calls += 1
            ExecutionResult.Succeeded
        }

        assertIs<ExecutionResult.Rejected>(composition.execute(request()))
        assertEquals(0, calls)
        assertEquals(
            listOf("AUTHORITY_DENIED", "EXECUTION_REJECTED"),
            f.logs.snapshot().takeLast(2).map { it.marker }
        )
    }

    @Test
    fun direct_grant_executes_once_and_revoke_is_immediate() {
        val f = fixture()
        f.authority.registerCapability(CapabilityDescriptor(capability, provider))
        val ownership = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        ).ownership
        var calls = 0
        val composition = composition(f) { _, _ ->
            calls += 1
            ExecutionResult.Succeeded
        }

        assertEquals(ExecutionResult.Succeeded, composition.execute(request()))
        assertEquals(1, calls)

        assertTrue(ownership.revoke())
        assertIs<ExecutionResult.Rejected>(composition.execute(request()))
        assertEquals(1, calls)
    }

    @Test
    fun delegated_execution_dies_with_exact_direct_source() {
        val f = fixture()
        f.authority.registerCapability(CapabilityDescriptor(capability, provider))
        val source = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(planner, capability, scope, now.plusSeconds(120))
            )
        ).ownership
        assertIs<CapabilityAuthorityDelegationResult.Granted>(
            f.authority.delegate(
                AuthorityDelegationRequest(
                    delegator = planner,
                    delegate = executorPrincipal,
                    capability = capability,
                    scope = scope,
                    reason = "execute launch",
                    expiresAt = now.plusSeconds(60)
                )
            )
        )

        var calls = 0
        val composition = composition(f) { _, _ ->
            calls += 1
            ExecutionResult.Succeeded
        }

        assertEquals(ExecutionResult.Succeeded, composition.execute(request(executorPrincipal)))
        assertEquals(1, calls)
        assertTrue(source.revoke())
        assertIs<ExecutionResult.Rejected>(composition.execute(request(executorPrincipal)))
        assertEquals(1, calls)
    }

    @Test
    fun authority_and_execution_share_one_execution_correlation() {
        val f = fixture()
        f.authority.registerCapability(CapabilityDescriptor(capability, provider))
        f.authority.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        val composition = composition(f) { _, _ -> ExecutionResult.Succeeded }
        val before = f.logs.snapshot().size

        assertEquals(ExecutionResult.Succeeded, composition.execute(request()))

        val executionEvents = f.logs.snapshot().drop(before)
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_SUCCEEDED"),
            executionEvents.map { it.marker }
        )
        assertEquals(1, executionEvents.map { it.context.correlationId }.toSet().size)
        assertEquals(
            executionEvents.map { it.marker },
            f.diagnostics.snapshot().takeLast(2).map { it.code }
        )
    }

    @Test
    fun public_api_does_not_expose_executor_managers_or_raw_authority_policy() {
        val forbidden = setOf(
            ExecutionExecutor::class.java,
            ExecutionManager::class.java,
            ExecutionAuthorizer::class.java,
            AuthorityManager::class.java,
            AuthorityPolicy::class.java
        )
        val exposed = ExecutionComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "execution API must not expose raw internals: $exposed")
    }

    private fun composition(
        fixture: Fixture,
        executor: ExecutionExecutor
    ): ExecutionComposition = ExecutionComposition(
        foundation = fixture.foundation,
        capabilityAuthority = fixture.authority,
        executor = executor,
        actionCapabilities = mapOf(actionId to capability)
    )
}
