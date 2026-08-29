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

class LearningApplicationReadinessContractTest {
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
        createdAt: Instant = Instant.parse("2001-02-03T04:05:06Z")
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
    fun created_at_is_caller_supplied_and_preserved_unchanged() {
        val f = fixture("created-at")
        val expected = Instant.parse("2001-02-03T04:05:06Z")
        val application = intent(createdAt = expected)

        assertIs<LearningApplicationInstallResult.Installed>(f.composition.install(application))
        assertEquals(expected, f.composition.find(application.id)?.createdAt)
        assertEquals(expected, f.composition.inspect(application.id)?.intent?.createdAt)
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
        assertIs<LearningApplicationInstallResult.Installed>(second.composition.install(secondIntent))

        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstIntent.id))
        assertNotNull(second.composition.find(secondIntent.id))
        assertEquals(secondIntent, second.composition.find(secondIntent.id))
    }

    @Test
    fun equal_numeric_generations_across_compositions_are_local_not_shared_ownership() {
        val first = fixture("generation-first")
        val second = fixture("generation-second")
        val firstIntent = intent(target = LearningApplicationTarget.MEMORY)
        val secondIntent = intent(target = LearningApplicationTarget.KNOWLEDGE)

        val firstOwnership = assertIs<LearningApplicationInstallResult.Installed>(
            first.composition.install(firstIntent)
        ).ownership
        val secondOwnership = assertIs<LearningApplicationInstallResult.Installed>(
            second.composition.install(secondIntent)
        ).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertTrue(firstOwnership.remove())
        assertNotNull(second.composition.find(secondIntent.id))
        assertEquals(secondOwnership.generation, second.composition.inspect(secondIntent.id)?.generation)
    }

    @Test
    fun decision_policy_references_are_structural_only_without_hidden_lookup_or_approval_requirement() {
        val f = fixture("structural")
        val application = intent(
            decisionId = "missing-or-rejected-decision",
            decisionGeneration = 91L,
            policyId = "missing-policy",
            policyGeneration = 92L,
            target = LearningApplicationTarget.KNOWLEDGE
        )

        assertIs<LearningApplicationInstallResult.Installed>(f.composition.install(application))
        assertEquals(application, f.composition.find(application.id))
    }

    @Test
    fun target_is_structural_only_and_does_not_create_implicit_application_or_consolidation() {
        LearningApplicationTarget.entries.forEachIndexed { index, target ->
            val f = fixture("target-$index")
            val application = intent(id = "application-$index", target = target)

            assertIs<LearningApplicationInstallResult.Installed>(f.composition.install(application))
            assertEquals(target, f.composition.find(application.id)?.target)

            val forbidden = listOf(
                "authorized", "authorization", "approved", "rejected", "applied", "executed",
                "executionResult", "consolidated", "consolidationResult", "memoryRecordId",
                "knowledgeItemId", "truth", "confidence", "trust", "capabilityGrant",
                "downstreamMutation", "learnedState"
            )
            assertFalse(f.logs.snapshot().any { event ->
                event.metadata.keys.any { key -> forbidden.any { token -> key.contains(token, ignoreCase = true) } }
            })
        }
    }

    @Test
    fun generation_is_positive_local_lifecycle_identity_not_time_score_or_priority() {
        val f = fixture("generation")
        val application = intent(createdAt = Instant.parse("1999-12-31T23:59:59Z"))

        val ownership = assertIs<LearningApplicationInstallResult.Installed>(
            f.composition.install(application)
        ).ownership

        assertTrue(ownership.generation.value > 0L)
        assertFalse(ownership.generation.value == application.createdAt.epochSecond)
    }
}
