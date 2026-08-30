package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentLearningApplicationMutationCompositionContractTest {
    private val createdAt = Instant.parse("2026-08-30T16:25:00Z")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator {
                "persistent-learning-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun plan(
        id: String = "learning-mutation-1",
        key: String = "learning-key-1",
        content: String = "private learned memory"
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId("learning-application-1"),
            generation = LearningApplicationGeneration(3)
        ),
        principal = AuthorityPrincipal("assistant-core"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-$id"),
                provenance = MemoryProvenance(MemorySourceId("learning")),
                content = content,
                createdAt = createdAt.minusSeconds(1)
            )
        ),
        createdAt = createdAt
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        storeId: String = "learning-mutation-store"
    ): PersistentLearningApplicationMutationComposition =
        assertIs<PersistentLearningApplicationMutationOpenResult.Opened>(
            PersistentLearningApplicationMutationComposition.open(
                foundation = foundation(),
                storeId = PersistentStoreId(storeId),
                backend = backend
            )
        ).composition

    @Test
    fun prepare_is_visible_only_after_durable_commit_and_reopen_restores_exact_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val mutation = plan()
        val composition = open(backend)

        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        )
        assertEquals(mutation, composition.find(mutation.id))
        assertEquals(prepared.ownership.generation, composition.inspect(mutation.id)?.generation)
        assertEquals(mutation, composition.findByIdempotencyKey(mutation.idempotencyKey))

        val reopened = open(backend)
        assertEquals(mutation, reopened.find(mutation.id))
        assertEquals(prepared.ownership.generation, reopened.inspect(mutation.id)?.generation)
        assertEquals(mutation, reopened.findByIdempotencyKey(mutation.idempotencyKey))
    }

    @Test
    fun failed_durable_prepare_keeps_local_mutation_and_idempotency_state_absent() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        backend.failNextCommit()

        assertIs<PersistentLearningApplicationMutationPrepareResult.Failed>(composition.prepare(mutation))
        assertNull(composition.find(mutation.id))
        assertNull(composition.findByIdempotencyKey(mutation.idempotencyKey))
        assertTrue(composition.snapshot().isEmpty())

        val reopened = open(backend)
        assertFalse(reopened.contains(mutation.id))
        assertTrue(reopened.snapshot().isEmpty())
    }

    @Test
    fun durable_remove_commits_before_local_exact_removal_and_reopen_is_empty() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        val ownership = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership

        assertIs<PersistentLearningApplicationMutationResult.Committed>(ownership.remove())
        assertFalse(composition.contains(mutation.id))
        assertNull(composition.findByIdempotencyKey(mutation.idempotencyKey))
        assertFalse(open(backend).contains(mutation.id))
    }

    @Test
    fun failed_durable_remove_keeps_local_and_reopened_prepared_state_live() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        val ownership = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership
        backend.failNextCommit()

        assertIs<PersistentLearningApplicationMutationResult.Failed>(ownership.remove())
        assertEquals(mutation, composition.find(mutation.id))
        assertEquals(mutation, composition.findByIdempotencyKey(mutation.idempotencyKey))

        val reopened = open(backend)
        assertEquals(mutation, reopened.find(mutation.id))
        assertEquals(ownership.generation, reopened.inspect(mutation.id)?.generation)
    }

    @Test
    fun stale_owner_cannot_remove_newer_prepared_replacement_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val firstPlan = plan(content = "first private learning payload")
        val first = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(firstPlan)
        ).ownership
        assertIs<PersistentLearningApplicationMutationResult.Committed>(first.remove())

        val replacementPlan = plan(content = "replacement private learning payload")
        val replacement = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(replacementPlan)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<PersistentLearningApplicationMutationResult.Rejected>(first.remove())
        assertEquals(replacementPlan, composition.find(replacementPlan.id))
        assertEquals(replacement.generation, composition.inspect(replacementPlan.id)?.generation)

        val reopened = open(backend)
        assertEquals(replacementPlan, reopened.find(replacementPlan.id))
        assertEquals(replacement.generation, reopened.inspect(replacementPlan.id)?.generation)
    }

    @Test
    fun shared_backend_stale_composition_conflict_does_not_publish_mutation_locally() {
        val backend = InMemoryPersistentRecordBackend()
        val first = open(backend)
        val stale = open(backend)
        val committed = plan(id = "committed", key = "key-committed")
        val rejected = plan(id = "rejected", key = "key-rejected")

        assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(first.prepare(committed))
        assertIs<PersistentLearningApplicationMutationPrepareResult.Rejected>(stale.prepare(rejected))
        assertNull(stale.find(rejected.id))
        assertNull(stale.findByIdempotencyKey(rejected.idempotencyKey))

        val reopened = open(backend)
        assertEquals(committed, reopened.find(committed.id))
        assertFalse(reopened.contains(rejected.id))
    }
}
