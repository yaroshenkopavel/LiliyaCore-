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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningDecisionReadinessContractTest {
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
        candidateId: String = "candidate-1",
        candidateGeneration: Long = 1L,
        disposition: LearningDecisionDisposition = LearningDecisionDisposition.APPROVE,
        rationale: String = "private rationale",
        createdAt: Instant = Instant.parse("2001-02-03T04:05:06Z")
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
    fun created_at_is_caller_supplied_and_preserved_unchanged() {
        val f = fixture("created-at")
        val expected = Instant.parse("2001-02-03T04:05:06Z")
        val d = decision(createdAt = expected)

        assertIs<LearningDecisionInstallResult.Installed>(f.composition.install(d))
        assertEquals(expected, f.composition.find(d.id)?.createdAt)
        assertEquals(expected, f.composition.inspect(d.id)?.decision?.createdAt)
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
        assertIs<LearningDecisionInstallResult.Installed>(second.composition.install(secondDecision))

        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstDecision.id))
        assertNotNull(second.composition.find(secondDecision.id))
        assertEquals(secondDecision, second.composition.find(secondDecision.id))
    }

    @Test
    fun equal_numeric_generations_across_compositions_are_local_not_shared_ownership() {
        val first = fixture("generation-first")
        val second = fixture("generation-second")
        val firstDecision = decision(rationale = "first")
        val secondDecision = decision(rationale = "second")

        val firstOwnership = assertIs<LearningDecisionInstallResult.Installed>(
            first.composition.install(firstDecision)
        ).ownership
        val secondOwnership = assertIs<LearningDecisionInstallResult.Installed>(
            second.composition.install(secondDecision)
        ).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertTrue(firstOwnership.remove())
        assertNotNull(second.composition.find(secondDecision.id))
        assertEquals(secondOwnership.generation, second.composition.inspect(secondDecision.id)?.generation)
    }

    @Test
    fun candidate_reference_remains_structural_without_hidden_lookup() {
        val f = fixture("structural")
        val d = decision(
            candidateId = "candidate-does-not-exist",
            candidateGeneration = 999L
        )

        assertIs<LearningDecisionInstallResult.Installed>(f.composition.install(d))
        assertEquals("candidate-does-not-exist", f.composition.find(d.id)?.candidate?.candidateId?.value)
        assertEquals(999L, f.composition.find(d.id)?.candidate?.generation?.value)
    }

    @Test
    fun approve_presence_creates_no_implicit_policy_application_authorization_or_downstream_semantics() {
        val f = fixture("boundary")
        val secret = "never expose readiness rationale"
        val d = decision(
            disposition = LearningDecisionDisposition.APPROVE,
            rationale = secret
        )

        assertIs<LearningDecisionInstallResult.Installed>(f.composition.install(d))
        assertEquals(LearningDecisionDisposition.APPROVE, f.composition.find(d.id)?.disposition)

        val events = f.logs.snapshot()
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(d.toString().contains(secret))
        assertTrue(d.toString().contains("<redacted>"))

        val forbidden = listOf(
            "policy",
            "applied",
            "application",
            "consolidat",
            "authorized",
            "authorization",
            "memory",
            "knowledge",
            "personality",
            "self",
            "truth",
            "confidence",
            "trust",
            "authority",
            "capability",
            "execution"
        )
        assertFalse(events.any { event ->
            event.metadata.keys.any { key -> forbidden.any { token -> key.contains(token, ignoreCase = true) } }
        })
    }
}
