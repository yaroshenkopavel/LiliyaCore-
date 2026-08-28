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

class LearningPolicyCompositionContractTest {
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
        rule: String = "caller supplied policy rule",
        createdAt: Instant = Instant.parse("2026-08-29T13:00:00Z")
    ) = LearningPolicy(
        id = LearningPolicyId(id),
        rule = rule,
        createdAt = createdAt
    )

    @Test
    fun install_read_and_remove_use_exact_ownership() {
        val f = fixture("basic")
        val p = policy()
        val ownership = assertIs<LearningPolicyInstallResult.Installed>(
            f.composition.install(p)
        ).ownership

        assertEquals(p, f.composition.find(p.id))
        assertEquals(ownership.generation, f.composition.inspect(p.id)?.generation)
        assertTrue(ownership.remove())
        assertNull(f.composition.find(p.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture("duplicate")
        val first = policy(rule = "first")
        val second = policy(rule = "second")

        assertIs<LearningPolicyInstallResult.Installed>(f.composition.install(first))
        assertIs<LearningPolicyInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture("stale")
        val stale = assertIs<LearningPolicyInstallResult.Installed>(
            f.composition.install(policy())
        ).ownership
        assertTrue(stale.remove())

        val replacement = policy(rule = "replacement")
        val current = assertIs<LearningPolicyInstallResult.Installed>(
            f.composition.install(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
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
        val secondOwnership = assertIs<LearningPolicyInstallResult.Installed>(
            second.composition.install(secondPolicy)
        ).ownership

        assertEquals(firstPolicy, first.composition.find(firstPolicy.id))
        assertEquals(secondPolicy, second.composition.find(secondPolicy.id))
        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstPolicy.id))
        assertNotNull(second.composition.find(secondPolicy.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun install_and_remove_use_fresh_root_contexts() {
        val f = fixture("context")
        val ownership = assertIs<LearningPolicyInstallResult.Installed>(
            f.composition.install(policy())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { event -> event.context.correlationId }.distinct()
        assertTrue(correlations.size >= 2)
    }

    @Test
    fun rule_remains_private_and_policy_presence_creates_no_evaluation_or_decision_semantics() {
        val f = fixture("boundary")
        val secret = "private policy rule"
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
    fun public_api_does_not_expose_raw_policy_store_or_registration() {
        val methods = LearningPolicyComposition::class.java.methods
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningPolicyStore") })
        assertFalse(methods.any { method -> method.returnType.name.contains("LearningPolicyRegistration") })
        assertFalse(methods.any { method ->
            method.parameterTypes.any { type ->
                type.name.contains("LearningPolicyStore") || type.name.contains("LearningPolicyRegistration")
            }
        })
    }
}
