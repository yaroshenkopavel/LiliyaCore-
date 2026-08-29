package pro.liliya.core.agent

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
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

class AgentDelegatedWorkBindingStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AgentDelegatedWorkBindingStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        var sequence = 0
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "delegated-binding-${++sequence}" }
        )
        return Fixture(logs, foundation, AgentDelegatedWorkBindingStore(foundation.observability))
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

    private fun context(f: Fixture, operation: String) = f.foundation.rootContext(
        operation = operation,
        component = "AgentDelegation"
    )

    @Test
    fun exact_binding_registers_reads_and_removes_one_shot() {
        val f = fixture()
        val value = binding()
        val registration = assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(value, context(f, "bindDelegatedWork"))
        ).registration

        assertEquals(value, f.store.find(value.autonomy))
        assertTrue(f.store.contains(value.autonomy))
        assertTrue(registration.remove(context(f, "unbindDelegatedWork")))
        assertFalse(f.store.contains(value.autonomy))
        assertFalse(registration.remove(context(f, "unbindDelegatedWorkAgain")))
    }

    @Test
    fun same_exact_autonomy_generation_cannot_bind_to_two_delegations() {
        val f = fixture()
        val first = binding(delegationId = "delegation-a")
        val conflicting = binding(delegationId = "delegation-b")

        assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(first, context(f, "bindFirst"))
        )
        assertIs<AgentDelegatedWorkRegistrationResult.Rejected>(
            f.store.register(conflicting, context(f, "bindConflict"))
        )
        assertEquals(first, f.store.find(first.autonomy))
    }

    @Test
    fun newer_autonomy_generation_is_a_distinct_exact_binding_key() {
        val f = fixture()
        val first = binding(autonomyGeneration = 7)
        val replacementGeneration = binding(autonomyGeneration = 8)

        assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(first, context(f, "bindGeneration7"))
        )
        assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(replacementGeneration, context(f, "bindGeneration8"))
        )

        assertEquals(first, f.store.find(first.autonomy))
        assertEquals(replacementGeneration, f.store.find(replacementGeneration.autonomy))
    }

    @Test
    fun stale_registration_cannot_remove_an_independent_new_generation_binding() {
        val f = fixture()
        val first = binding(autonomyGeneration = 7)
        val firstRegistration = assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(first, context(f, "bindFirst"))
        ).registration
        assertTrue(firstRegistration.remove(context(f, "removeFirst")))

        val replacement = binding(autonomyGeneration = 8)
        assertIs<AgentDelegatedWorkRegistrationResult.Registered>(
            f.store.register(replacement, context(f, "bindReplacement"))
        )

        assertFalse(firstRegistration.remove(context(f, "staleRemove")))
        assertEquals(replacement, f.store.find(replacement.autonomy))
    }

    @Test
    fun snapshots_are_deterministic_detached_structural_views() {
        val f = fixture()
        val second = binding(autonomyId = "autonomy-b", autonomyGeneration = 2)
        val first = binding(autonomyId = "autonomy-a", autonomyGeneration = 9)
        val later = binding(autonomyId = "autonomy-c", autonomyGeneration = 1)

        f.store.register(second, context(f, "bindB"))
        f.store.register(first, context(f, "bindA"))
        val earlierSnapshot = f.store.snapshot()
        f.store.register(later, context(f, "bindC"))

        assertEquals(listOf(first, second), earlierSnapshot)
        assertEquals(listOf(first, second, later), f.store.snapshot())
    }

    @Test
    fun concurrent_same_exact_autonomy_binding_has_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentDelegatedWorkRegistrationResult>()
        val workers = List(24) { index ->
            thread(start = false) {
                results += f.store.register(
                    binding(delegationId = "delegation-$index"),
                    context(f, "concurrentBind")
                )
            }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentDelegatedWorkRegistrationResult.Registered })
        assertEquals(23, results.count { it is AgentDelegatedWorkRegistrationResult.Rejected })
        assertEquals(1, f.store.snapshot().size)
    }

    @Test
    fun binding_api_and_observability_contain_no_permission_or_execution_semantics() {
        val f = fixture()
        val value = binding()
        f.store.register(value, context(f, "bindDelegatedWork"))

        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant"
        )
        val methodNames = AgentDelegatedWorkBinding::class.java.methods.map { it.name.lowercase() }
        assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.keys.any { key -> forbidden.any { token -> key.lowercase().contains(token) } }
        })
    }
}
