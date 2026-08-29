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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class OrchestrationReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: OrchestrationComposition
    )

    private fun fixture(prefix: String = "orchestration-readiness"): Fixture {
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
        decisionId: String = "decision-1",
        decisionGeneration: Long = 5,
        selectedOptionId: String = "option-a",
        description: String = "private orchestration intention",
        createdAt: Instant = Instant.parse("2026-08-29T14:00:00Z")
    ) = OrchestrationIntent(
        id = OrchestrationIntentId(id),
        decision = OrchestrationDecisionReference(
            decisionId = DecisionId(decisionId),
            generation = DecisionGeneration(decisionGeneration),
            selectedOptionId = DecisionOptionId(selectedOptionId)
        ),
        description = description,
        createdAt = createdAt
    )

    @Test
    fun ownership_remove_is_one_shot_and_repeated_remove_fails_closed() {
        val f = fixture()
        val value = intent()
        val ownership = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(value)).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertFalse(f.composition.contains(value.id))
    }

    @Test
    fun same_id_is_independent_across_compositions() {
        val first = fixture("first")
        val second = fixture("second")
        val value = intent()

        val firstOwnership = assertIs<OrchestrationInstallResult.Installed>(first.composition.install(value)).ownership
        val secondOwnership = assertIs<OrchestrationInstallResult.Installed>(second.composition.install(value)).ownership

        assertNotSame(firstOwnership, secondOwnership)
        assertTrue(firstOwnership.remove())
        assertFalse(first.composition.contains(value.id))
        assertTrue(second.composition.contains(value.id))
    }

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val f = fixture()
        val first = intent(id = "intent-a", createdAt = Instant.parse("2026-08-29T14:00:00Z"))
        val second = intent(id = "intent-b", createdAt = Instant.parse("2026-08-29T14:01:00Z"))

        assertIs<OrchestrationInstallResult.Installed>(f.composition.install(first))
        val snapshotBefore = f.composition.snapshot()
        val entriesBefore = f.composition.snapshotEntries()

        assertIs<OrchestrationInstallResult.Installed>(f.composition.install(second))

        assertEquals(listOf("intent-a"), snapshotBefore.map { it.id.value })
        assertEquals(listOf("intent-a"), entriesBefore.map { it.intent.id.value })
        assertEquals(listOf("intent-a", "intent-b"), f.composition.snapshot().map { it.id.value })
    }

    @Test
    fun orchestration_lifecycle_observability_contains_no_authority_capability_execution_or_autonomy_semantics() {
        val f = fixture()
        val secret = "never-observe-private-orchestration-intention"
        val value = intent(description = secret)
        val ownership = assertIs<OrchestrationInstallResult.Installed>(f.composition.install(value)).ownership
        ownership.remove()

        val forbiddenTokens = listOf(
            "approved",
            "approval",
            "authority",
            "authorized",
            "capability",
            "permission",
            "execution",
            "execute",
            "executed",
            "executor",
            "scheduled",
            "scheduler",
            "autonomy",
            "agent",
            "truth",
            "confidence",
            "trusted"
        )

        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { it == secret } ||
                event.metadata.keys.any { key ->
                    forbiddenTokens.any { token -> key.contains(token, ignoreCase = true) }
                }
        })
    }

    @Test
    fun exact_decision_provenance_is_data_only_and_survives_snapshot() {
        val f = fixture()
        val value = intent(
            decisionId = "decision-exact",
            decisionGeneration = 19,
            selectedOptionId = "selected-exact"
        )

        assertIs<OrchestrationInstallResult.Installed>(f.composition.install(value))
        val snapshot = f.composition.inspect(value.id)!!

        assertEquals("decision-exact", snapshot.intent.decision.decisionId.value)
        assertEquals(19, snapshot.intent.decision.generation.value)
        assertEquals("selected-exact", snapshot.intent.decision.selectedOptionId.value)
    }
}
