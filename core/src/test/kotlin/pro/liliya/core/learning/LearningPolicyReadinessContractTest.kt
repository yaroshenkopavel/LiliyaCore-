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

class LearningPolicyReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: LearningPolicyComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, LearningPolicyComposition(foundation))
    }

    private fun policy(
        id: String = "policy-1",
        rule: String = "private policy rule",
        createdAt: Instant = Instant.parse("2001-02-03T04:05:06Z")
    ) = LearningPolicy(
        id = LearningPolicyId(id),
        rule = rule,
        createdAt = createdAt
    )

    @Test
    fun created_at_is_caller_supplied_and_preserved_unchanged() {
        val f = fixture("created-at")
        val expected = Instant.parse("2001-02-03T04:05:06Z")
        val p = policy(createdAt = expected)

        assertIs<LearningPolicyInstallResult.Installed>(f.composition.install(p))
        assertEquals(expected, f.composition.find(p.id)?.createdAt)
        assertEquals(expected, f.composition.inspect(p.id)?.policy?.createdAt)
    }

    @Test
    fun independent_compositions_isolate_same_policy_id() {
        val first = fixture("first")
        val second = fixture("second")
        val firstPolicy = policy(rule = "first rule")
        val secondPolicy = policy(rule = "second rule")

        val firstOwnership = assertIs<LearningPolicyInstallResult.Installed>(
            first.composition.install(firstPolicy)
        ).ownership
        assertIs<LearningPolicyInstallResult.Installed>(second.composition.install(secondPolicy))

        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstPolicy.id))
        assertNotNull(second.composition.find(secondPolicy.id))
        assertEquals(secondPolicy, second.composition.find(secondPolicy.id))
    }

    @Test
    fun equal_numeric_generations_across_compositions_are_local_not_shared_ownership() {
        val first = fixture("generation-first")
        val second = fixture("generation-second")
        val firstPolicy = policy(rule = "first")
        val secondPolicy = policy(rule = "second")

        val firstOwnership = assertIs<LearningPolicyInstallResult.Installed>(
            first.composition.install(firstPolicy)
        ).ownership
        val secondOwnership = assertIs<LearningPolicyInstallResult.Installed>(
            second.composition.install(secondPolicy)
        ).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertTrue(firstOwnership.remove())
        assertNotNull(second.composition.find(secondPolicy.id))
        assertEquals(secondOwnership.generation, second.composition.inspect(secondPolicy.id)?.generation)
    }

    @Test
    fun policy_presence_remains_structural_without_implicit_evaluation_or_decision_semantics() {
        val f = fixture("boundary")
        val secret = "never expose readiness policy rule"
        val p = policy(rule = secret)

        assertIs<LearningPolicyInstallResult.Installed>(f.composition.install(p))
        assertEquals(p, f.composition.find(p.id))

        val events = f.logs.snapshot()
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(p.toString().contains(secret))
        assertTrue(p.toString().contains("<redacted>"))

        val forbidden = listOf(
            "decision", "approve", "reject", "evaluat", "applied", "application",
            "consolidat", "authorized", "authorization", "memory", "knowledge",
            "personality", "self", "truth", "confidence", "trust", "authority",
            "capability", "execution"
        )
        assertFalse(events.any { event ->
            event.metadata.keys.any { key -> forbidden.any { token -> key.contains(token, ignoreCase = true) } }
        })
    }

    @Test
    fun policy_generation_is_positive_local_lifecycle_identity_not_time_or_score() {
        val f = fixture("generation")
        val p = policy(createdAt = Instant.parse("1999-12-31T23:59:59Z"))

        val ownership = assertIs<LearningPolicyInstallResult.Installed>(
            f.composition.install(p)
        ).ownership

        assertTrue(ownership.generation.value > 0L)
        assertFalse(ownership.generation.value == p.createdAt.epochSecond)
    }
}
