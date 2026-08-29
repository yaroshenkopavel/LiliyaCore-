package pro.liliya.core.orchestration

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrchestrationCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: OrchestrationComposition
    )

    private fun fixture(prefix: String = "orchestration"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, OrchestrationComposition(foundation))
    }

    private fun intent(
        id: String = "intent-1",
        description: String = "private orchestration intention"
    ) = OrchestrationIntent(
        id = OrchestrationIntentId(id),
        decision = OrchestrationDecisionReference(
            decisionId = DecisionId("decision-1"),
            generation = DecisionGeneration(5),
            selectedOptionId = DecisionOptionId("option-a")
        ),
        description = description,
        createdAt = Instant.parse("2026-08-29T14:00:00Z")
    )

    @Test
    fun install_exposes_exact_controlled_ownership() {
        val f = fixture()
        val value = intent()
        val ownership = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(value)).ownership

        assertEquals(value, ownership.intent)
        assertEquals(ownership.generation, f.composition.inspect(value.id)?.generation)
        assertEquals(value, f.composition.find(value.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(value.id))
    }

    @Test
    fun duplicate_install_rejects_without_replacement() {
        val f = fixture()
        val first = intent(description = "first private intention")
        val second = intent(description = "second private intention")

        assertIs<OrchestrationInstallResult.Installed>(f.composition.install(first))
        assertIs<OrchestrationInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(intent())).ownership
        assertTrue(stale.remove())

        val replacement = intent(description = "replacement private intention")
        val current = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val value = intent()

        val firstOwnership = assertIs<OrchestrationInstallResult.Installed>(first.composition.install(value)).ownership
        val secondOwnership = assertIs<OrchestrationInstallResult.Installed>(second.composition.install(value)).ownership

        assertTrue(first.composition.contains(value.id))
        assertTrue(second.composition.contains(value.id))
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(value.id))
        assertTrue(second.composition.contains(value.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun description_is_absent_from_lifecycle_metadata() {
        val f = fixture()
        val secret = "never-log-orchestration-intention"
        val value = intent(description = secret)

        val ownership = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(value)).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun remove_context_is_child_of_install_context() {
        val f = fixture()
        val ownership = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(intent())).ownership
        assertTrue(ownership.remove())

        val registered = f.logs.snapshot().first { it.message == "orchestration intent registered" }
        val removed = f.logs.snapshot().first { it.message == "orchestration intent removed" }

        assertEquals("installOrchestrationIntent", registered.context.operation)
        assertEquals("removeOrchestrationIntent", removed.context.operation)
        assertNotEquals(registered.context.correlationId, removed.context.correlationId)
        assertEquals(registered.context.correlationId, removed.context.parentCorrelationId)
    }
}
