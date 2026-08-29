package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningApplicationCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: LearningApplicationComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, LearningApplicationComposition(foundation))
    }

    private fun intent(
        id: String = "application-1",
        decisionId: String = "decision-1",
        decisionGeneration: Long = 3L,
        policyId: String = "policy-1",
        policyGeneration: Long = 4L,
        target: LearningApplicationTarget = LearningApplicationTarget.MEMORY,
        createdAt: Instant = Instant.parse("2026-08-29T00:00:00Z")
    ) = LearningApplicationIntent(
        id = LearningApplicationId(id),
        decision = LearningDecisionReference(
            decisionId = LearningDecisionId(decisionId),
            generation = LearningDecisionGeneration(decisionGeneration)
        ),
        policy = LearningPolicyReference(
            policyId = LearningPolicyId(policyId),
            generation = LearningPolicyGeneration(policyGeneration)
        ),
        target = target,
        createdAt = createdAt
    )

    @Test
    fun install_read_and_remove_use_exact_ownership() {
        val f = fixture("basic")
        val application = intent()
        val ownership = assertIs<LearningApplicationInstallResult.Installed>(
            f.composition.install(application)
        ).ownership

        assertEquals(application, f.composition.find(application.id))
        assertEquals(ownership.generation, f.composition.inspect(application.id)?.generation)
        assertTrue(ownership.remove())
        assertNull(f.composition.find(application.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture("duplicate")
        val first = intent(target = LearningApplicationTarget.MEMORY)
        val second = intent(target = LearningApplicationTarget.KNOWLEDGE)

        assertIs<LearningApplicationInstallResult.Installed>(f.composition.install(first))
        assertIs<LearningApplicationInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture("stale")
        val stale = assertIs<LearningApplicationInstallResult.Installed>(
            f.composition.install(intent())
        ).ownership
        assertTrue(stale.remove())

        val replacement = intent(target = LearningApplicationTarget.KNOWLEDGE)
        val current = assertIs<LearningApplicationInstallResult.Installed>(
            f.composition.install(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun independent_compositions_isolate_same_application_id() {
        val first = fixture("first")
        val second = fixture("second")
        val firstIntent = intent(target = LearningApplicationTarget.MEMORY)
        val secondIntent = intent(target = LearningApplicationTarget.KNOWLEDGE)

        val firstOwnership = assertIs<LearningApplicationInstallResult.Installed>(
            first.composition.install(firstIntent)
        ).ownership
        val secondOwnership = assertIs<LearningApplicationInstallResult.Installed>(
            second.composition.install(secondIntent)
        ).ownership

        assertEquals(firstIntent, first.composition.find(firstIntent.id))
        assertEquals(secondIntent, second.composition.find(secondIntent.id))
        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstIntent.id))
        assertNotNull(second.composition.find(secondIntent.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun install_and_remove_use_fresh_root_contexts() {
        val f = fixture("context")
        val ownership = assertIs<LearningApplicationInstallResult.Installed>(
            f.composition.install(intent())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { event -> event.context.correlationId }.distinct()
        assertTrue(correlations.size >= 2)
    }

    @Test
    fun structural_references_and_target_do_not_create_application_or_downstream_semantics() {
        val f = fixture("boundary")
        val application = intent(
            decisionId = "missing-decision",
            decisionGeneration = 91L,
            policyId = "missing-policy",
            policyGeneration = 92L,
            target = LearningApplicationTarget.KNOWLEDGE
        )

        assertIs<LearningApplicationInstallResult.Installed>(f.composition.install(application))
        assertEquals(application, f.composition.find(application.id))

        val forbidden = listOf(
            "authorized", "authorization", "approved", "rejected", "applied", "executed",
            "executionResult", "consolidated", "consolidationResult", "memoryRecordId",
            "knowledgeItemId", "truth", "confidence", "trust", "capabilityGrant"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.keys.any { key -> forbidden.any { token -> key.contains(token, ignoreCase = true) } }
        })
    }

    @Test
    fun public_api_does_not_expose_raw_application_store_or_registration() {
        val methods = LearningApplicationComposition::class.java.methods
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningApplicationStore") })
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningApplicationRegistration") })
        assertFalse(methods.any { method ->
            method.parameterTypes.any { type ->
                type.name.contains("LearningApplicationStore") || type.name.contains("LearningApplicationRegistration")
            }
        })
    }
}
