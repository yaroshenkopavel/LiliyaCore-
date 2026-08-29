package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearningApplicationMutationCompositionContractTest {
    private fun foundation(correlation: String): FoundationComposition = FoundationComposition(
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
        loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
        correlationIds = CorrelationIdGenerator { correlation }
    )

    private fun plan(
        id: String = "mutation-1",
        key: String = "idem-1",
        applicationGeneration: Long = 1L
    ): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId("application-1"),
            generation = LearningApplicationGeneration(applicationGeneration)
        ),
        principal = AuthorityPrincipal("learning-controller"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-1"),
                provenance = MemoryProvenance(MemorySourceId("learning-application")),
                content = "sensitive prepared payload",
                createdAt = Instant.parse("2026-08-29T09:10:00Z")
            )
        ),
        createdAt = Instant.parse("2026-08-29T09:11:00Z")
    )

    @Test
    fun prepare_exposes_exact_controlled_ownership() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-composition"))
        val value = plan()

        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(value)
        ).ownership

        assertSame(value, ownership.plan)
        assertSame(value, composition.find(value.id))
        assertEquals(ownership.generation, composition.inspect(value.id)?.generation)
        assertEquals(value, composition.findByIdempotencyKey(value.idempotencyKey))
        assertTrue(ownership.remove())
        assertNull(composition.find(value.id))
        assertNull(composition.findByIdempotencyKey(value.idempotencyKey))
    }

    @Test
    fun duplicate_idempotency_key_is_rejected_without_replacing_current_plan() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-duplicate"))
        val first = plan(id = "mutation-a", key = "shared-key")
        val second = plan(id = "mutation-b", key = "shared-key", applicationGeneration = 2L)

        assertIs<LearningApplicationMutationPrepareResult.Prepared>(composition.prepare(first))
        assertIs<LearningApplicationMutationPrepareResult.Rejected>(composition.prepare(second))
        assertSame(first, composition.find(first.id))
        assertFalse(composition.contains(second.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-stale"))
        val first = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership

        assertTrue(first.remove())
        val replacement = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan(applicationGeneration = 2L))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertTrue(composition.contains(replacement.plan.id))
        assertEquals(replacement.generation, composition.inspect(replacement.plan.id)?.generation)
    }

    @Test
    fun independent_compositions_do_not_share_prepared_mutations() {
        val first = LearningApplicationMutationComposition(foundation("mutation-first"))
        val second = LearningApplicationMutationComposition(foundation("mutation-second"))
        val value = plan()

        assertIs<LearningApplicationMutationPrepareResult.Prepared>(first.prepare(value))

        assertTrue(first.contains(value.id))
        assertFalse(second.contains(value.id))
        assertTrue(second.snapshot().isEmpty())
    }
}
