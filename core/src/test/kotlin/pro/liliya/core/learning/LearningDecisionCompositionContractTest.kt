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

class LearningDecisionCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: LearningDecisionComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, LearningDecisionComposition(foundation))
    }

    private fun decision(
        id: String = "decision-1",
        rationale: String = "caller rationale",
        disposition: LearningDecisionDisposition = LearningDecisionDisposition.APPROVE,
        candidateId: String = "candidate-1",
        candidateGeneration: Long = 1L,
        createdAt: Instant = Instant.parse("2026-08-29T10:00:00Z")
    ) = LearningDecision(
        id = LearningDecisionId(id),
        candidate = LearningCandidateReference(
            candidateId = LearningCandidateId(candidateId),
            generation = LearningGeneration(candidateGeneration)
        ),
        disposition = disposition,
        rationale = rationale,
        createdAt = createdAt
    )

    @Test
    fun install_read_and_remove_use_exact_ownership() {
        val f = fixture("basic")
        val d = decision()
        val ownership = assertIs<LearningDecisionInstallResult.Installed>(
            f.composition.install(d)
        ).ownership

        assertEquals(d, f.composition.find(d.id))
        assertEquals(ownership.generation, f.composition.inspect(d.id)?.generation)
        assertTrue(ownership.remove())
        assertNull(f.composition.find(d.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture("duplicate")
        val first = decision(rationale = "first")
        val second = decision(rationale = "second", disposition = LearningDecisionDisposition.REJECT)

        assertIs<LearningDecisionInstallResult.Installed>(f.composition.install(first))
        assertIs<LearningDecisionInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture("stale")
        val first = decision()
        val stale = assertIs<LearningDecisionInstallResult.Installed>(
            f.composition.install(first)
        ).ownership
        assertTrue(stale.remove())

        val replacement = decision(rationale = "replacement")
        val current = assertIs<LearningDecisionInstallResult.Installed>(
            f.composition.install(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun independent_compositions_isolate_same_decision_id() {
        val first = fixture("first")
        val second = fixture("second")
        val firstDecision = decision(rationale = "first rationale")
        val secondDecision = decision(rationale = "second rationale", disposition = LearningDecisionDisposition.REJECT)

        val firstOwnership = assertIs<LearningDecisionInstallResult.Installed>(
            first.composition.install(firstDecision)
        ).ownership
        val secondOwnership = assertIs<LearningDecisionInstallResult.Installed>(
            second.composition.install(secondDecision)
        ).ownership

        assertEquals(firstDecision, first.composition.find(firstDecision.id))
        assertEquals(secondDecision, second.composition.find(secondDecision.id))
        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstDecision.id))
        assertNotNull(second.composition.find(secondDecision.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun install_and_remove_use_fresh_root_contexts() {
        val f = fixture("context")
        val ownership = assertIs<LearningDecisionInstallResult.Installed>(
            f.composition.install(decision())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { event -> event.context.correlationId }.distinct()
        assertTrue(correlations.size >= 2)
    }

    @Test
    fun candidate_reference_is_structural_and_approve_creates_no_application_semantics() {
        val f = fixture("structural")
        val secret = "private rationale"
        val d = decision(
            candidateId = "candidate-does-not-exist",
            candidateGeneration = 999L,
            rationale = secret,
            disposition = LearningDecisionDisposition.APPROVE
        )

        assertIs<LearningDecisionInstallResult.Installed>(f.composition.install(d))
        assertEquals(d, f.composition.find(d.id))

        val events = f.logs.snapshot()
        assertTrue(events.any { event -> event.metadata["learningCandidateId"] == "candidate-does-not-exist" })
        assertTrue(events.any { event -> event.metadata["learningCandidateGeneration"] == "999" })
        assertTrue(events.any { event -> event.metadata["learningDecisionDisposition"] == "approve" })
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("applied", ignoreCase = true) ||
                    key.contains("consolidat", ignoreCase = true) ||
                    key.contains("authorized", ignoreCase = true) ||
                    key.contains("policy", ignoreCase = true) ||
                    key.contains("memory", ignoreCase = true) ||
                    key.contains("knowledge", ignoreCase = true) ||
                    key.contains("personality", ignoreCase = true) ||
                    key.contains("self", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true)
            }
        })
    }

    @Test
    fun public_api_does_not_expose_raw_decision_store_or_registration() {
        val methods = LearningDecisionComposition::class.java.methods
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningDecisionStore") })
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningDecisionRegistration") })
        assertFalse(methods.any { method ->
            method.parameterTypes.any { type ->
                type.name.contains("LearningDecisionStore") || type.name.contains("LearningDecisionRegistration")
            }
        })
    }
}
