package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearningApplicationMutationStoreContractTest {
    private fun foundation(): FoundationComposition = FoundationComposition(
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
        loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
        correlationIds = CorrelationIdGenerator { "mutation-store" }
    )

    private fun memoryPlan(
        id: String = "mutation-1",
        key: String = "idem-1",
        applicationGeneration: Long = 1L,
        content: String = "sensitive memory content",
        createdAt: Instant = Instant.parse("2026-08-29T08:21:00Z")
    ): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            LearningApplicationId("application-1"),
            LearningApplicationGeneration(applicationGeneration)
        ),
        principal = AuthorityPrincipal("learning-controller"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-1"),
                provenance = MemoryProvenance(MemorySourceId("learning-application")),
                content = content,
                createdAt = Instant.parse("2026-08-29T08:20:00Z")
            )
        ),
        createdAt = createdAt
    )

    private fun knowledgePlan(): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId("mutation-k"),
        application = LearningApplicationIntentReference(
            LearningApplicationId("application-k"),
            LearningApplicationGeneration(7L)
        ),
        principal = AuthorityPrincipal("learning-controller"),
        target = LearningApplicationTarget.KNOWLEDGE,
        idempotencyKey = LearningApplicationIdempotencyKey("idem-k"),
        payload = LearningApplicationMutationPayload.Knowledge(
            KnowledgeItem(
                id = KnowledgeItemId("knowledge-1"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("learning-application")),
                content = "sensitive knowledge content",
                createdAt = Instant.parse("2026-08-29T08:20:00Z")
            )
        ),
        createdAt = Instant.parse("2026-08-29T08:21:00Z")
    )

    @Test
    fun register_preserves_explicit_payload_without_applying_it() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val plan = memoryPlan()

        val result = assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(plan, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        )

        assertSame(plan, result.registration.plan)
        assertSame(plan, store.find(plan.id))
        assertEquals(plan, store.findByIdempotencyKey(plan.idempotencyKey))
        assertTrue(result.registration.generation.value > 0L)
    }

    @Test
    fun duplicate_mutation_id_is_rejected_even_with_different_idempotency_key() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val first = memoryPlan(id = "same-mutation", key = "idem-a")
        val second = memoryPlan(id = "same-mutation", key = "idem-b", applicationGeneration = 2L)

        assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(first, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        )
        assertIs<LearningApplicationMutationRegistrationResult.Rejected>(
            store.register(second, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        )
        assertSame(first, store.find(first.id))
        assertNull(store.findByIdempotencyKey(second.idempotencyKey))
    }

    @Test
    fun duplicate_idempotency_key_is_rejected_even_with_different_mutation_id() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val first = memoryPlan(id = "mutation-a", key = "same-key")
        val second = memoryPlan(id = "mutation-b", key = "same-key")

        assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(first, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        )
        assertIs<LearningApplicationMutationRegistrationResult.Rejected>(
            store.register(second, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        )
        assertNull(store.find(second.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement_after_exact_remove() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val original = memoryPlan()
        val first = assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(original, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        ).registration

        assertTrue(first.remove(foundation.rootContext("removeLearningApplicationMutation", "LearningApplicationMutation")))

        val replacementPlan = memoryPlan(applicationGeneration = 2L)
        val replacement = assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(replacementPlan, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        ).registration

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove(foundation.rootContext("removeLearningApplicationMutation", "LearningApplicationMutation")))
        assertEquals(replacementPlan, store.find(original.id))
    }

    @Test
    fun target_must_match_payload_type_and_payload_rendering_redacts_content() {
        val memory = memoryPlan(content = "secret-memory-value")
        val knowledge = knowledgePlan()

        assertFailsWith<IllegalArgumentException> {
            LearningApplicationMutationPlan(
                id = LearningApplicationMutationId("invalid-target"),
                application = memory.application,
                principal = memory.principal,
                target = LearningApplicationTarget.MEMORY,
                idempotencyKey = LearningApplicationIdempotencyKey("invalid-target-key"),
                payload = knowledge.payload,
                createdAt = memory.createdAt
            )
        }

        assertFalse(memory.toString().contains("secret-memory-value"))
        assertFalse(knowledge.toString().contains("sensitive knowledge content"))
        assertTrue(memory.toString().contains("memory-1"))
        assertTrue(knowledge.toString().contains("knowledge-1"))
    }

    @Test
    fun snapshots_are_deterministic_by_created_at_then_mutation_id() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val later = memoryPlan(
            id = "mutation-z",
            key = "idem-z",
            createdAt = Instant.parse("2026-08-29T08:23:00Z")
        )
        val sameTimeSecond = memoryPlan(
            id = "mutation-b",
            key = "idem-b",
            createdAt = Instant.parse("2026-08-29T08:22:00Z")
        )
        val sameTimeFirst = memoryPlan(
            id = "mutation-a",
            key = "idem-a",
            createdAt = Instant.parse("2026-08-29T08:22:00Z")
        )

        listOf(later, sameTimeSecond, sameTimeFirst).forEach { plan ->
            assertIs<LearningApplicationMutationRegistrationResult.Registered>(
                store.register(plan, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
            )
        }

        assertEquals(
            listOf("mutation-a", "mutation-b", "mutation-z"),
            store.snapshot().map { it.id.value }
        )
        assertEquals(
            listOf("mutation-a", "mutation-b", "mutation-z"),
            store.snapshotEntries().map { it.plan.id.value }
        )
    }

    @Test
    fun lifecycle_observability_does_not_expose_payload_content() {
        val logs = InMemoryLogWriter()
        val diagnosticSink = InMemoryDiagnosticSink()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnosticSink),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "mutation-store-privacy" }
        )
        val store = LearningApplicationMutationStore(foundation.observability)
        val secret = "payload-secret-that-must-not-appear"
        val plan = memoryPlan(content = secret)

        val registration = assertIs<LearningApplicationMutationRegistrationResult.Registered>(
            store.register(plan, foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation"))
        ).registration
        assertTrue(
            registration.remove(
                foundation.rootContext("removeLearningApplicationMutation", "LearningApplicationMutation")
            )
        )

        assertTrue(logs.snapshot().isNotEmpty())
        assertTrue(diagnosticSink.snapshot().isNotEmpty())
        assertFalse(logs.snapshot().joinToString().contains(secret))
        assertFalse(diagnosticSink.snapshot().joinToString().contains(secret))
    }

    @Test
    fun concurrent_same_idempotency_key_has_exactly_one_winner() {
        val foundation = foundation()
        val store = LearningApplicationMutationStore(foundation.observability)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                (1..24).map { index ->
                    Callable {
                        store.register(
                            memoryPlan(id = "mutation-$index", key = "shared-key"),
                            foundation.rootContext("prepareLearningApplicationMutation", "LearningApplicationMutation")
                        )
                    }
                }
            ).map { it.get() }

            assertEquals(1, results.count { it is LearningApplicationMutationRegistrationResult.Registered })
            assertEquals(23, results.count { it is LearningApplicationMutationRegistrationResult.Rejected })
            assertEquals(1, store.snapshot().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
