package pro.liliya.core.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentDelegatedWorkBindingCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AgentDelegatedWorkBindingComposition
    )

    private fun fixture(prefix: String = "binding-composition"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AgentDelegatedWorkBindingComposition(foundation))
    }

    private fun binding(
        delegationId: String = "delegation-1",
        delegationGeneration: Long = 3,
        childId: String = "child-1",
        childGeneration: Long = 5,
        autonomyId: String = "autonomy-1",
        autonomyGeneration: Long = 7
    ) = AgentDelegatedWorkBinding(
        delegation = ExactAgentDelegationReference(
            AgentDelegationId(delegationId),
            AgentDelegationGeneration(delegationGeneration)
        ),
        child = ExactAgentReference(AgentId(childId), AgentGeneration(childGeneration)),
        autonomy = ExactAutonomyReference(
            AutonomyProposalId(autonomyId),
            AutonomyGeneration(autonomyGeneration)
        )
    )

    @Test
    fun install_returns_exact_controlled_ownership_and_remove_is_one_shot() {
        val f = fixture()
        val value = binding()
        val ownership = assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(
            f.composition.install(value)
        ).ownership

        assertEquals(value, ownership.binding)
        assertEquals(value, f.composition.find(value.autonomy))
        assertTrue(ownership.remove())
        assertFalse(f.composition.contains(value.autonomy))
        assertFalse(ownership.remove())
    }

    @Test
    fun same_exact_autonomy_binding_rejects_without_replacement() {
        val f = fixture()
        val first = binding(delegationId = "delegation-first")
        val conflict = binding(delegationId = "delegation-conflict")

        assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(f.composition.install(first))
        assertIs<AgentDelegatedWorkBindingInstallResult.Rejected>(f.composition.install(conflict))
        assertEquals(first, f.composition.find(first.autonomy))
    }

    @Test
    fun same_exact_binding_is_isolated_across_compositions() {
        val first = fixture("binding-first")
        val second = fixture("binding-second")
        val value = binding()

        val firstOwnership = assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(
            first.composition.install(value)
        ).ownership
        val secondOwnership = assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(
            second.composition.install(value)
        ).ownership

        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(value.autonomy))
        assertTrue(second.composition.contains(value.autonomy))
        assertEquals(value, secondOwnership.binding)
    }

    @Test
    fun snapshot_is_deterministic_and_detached() {
        val f = fixture()
        val b = binding(autonomyId = "autonomy-b", autonomyGeneration = 2)
        val a = binding(autonomyId = "autonomy-a", autonomyGeneration = 9)
        val c = binding(autonomyId = "autonomy-c", autonomyGeneration = 1)

        f.composition.install(b)
        f.composition.install(a)
        val earlier = f.composition.snapshot()
        f.composition.install(c)

        assertEquals(listOf(a, b), earlier)
        assertEquals(listOf(a, b, c), f.composition.snapshot())
    }

    @Test
    fun install_and_remove_have_root_to_child_correlation() {
        val f = fixture()
        val value = binding()
        val ownership = assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(
            f.composition.install(value)
        ).ownership
        assertTrue(ownership.remove())

        val installed = f.logs.snapshot().first { it.marker == "AGENT_DELEGATED_WORK_BOUND" }
        val removed = f.logs.snapshot().first { it.marker == "AGENT_DELEGATED_WORK_UNBOUND" }
        assertEquals(installed.context.correlationId, removed.context.parentCorrelationId)
    }

    @Test
    fun public_composition_and_ownership_expose_no_permission_or_execution_methods() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant"
        )
        val methodNames = (
            AgentDelegatedWorkBindingComposition::class.java.methods +
                AgentDelegatedWorkBindingOwnership::class.java.methods
            ).map { it.name.lowercase() }

        assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
